/* Copyright (C) 2018 Charles Muchene
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.charlesmuchene.adb.models

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.util.SparseArray
import com.charlesmuchene.adb.Adb
import com.charlesmuchene.adb.utilities.*
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withTimeout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * Adb device
 */
class AdbDevice(private val usbInterface: UsbInterface, val connection: UsbDeviceConnection) {

    var nextSocketId = 1
        private set
    var isConnected = false
        private set
    val inEndpoint: UsbEndpoint
    val outEndpoint: UsbEndpoint
    private var signatureSent = false
    private val inRequestPool = LinkedList<UsbRequest>()
    private val outRequestPool = LinkedList<UsbRequest>()

    val outRequest: UsbRequest
        get() = synchronized(outRequestPool) {
            return if (outRequestPool.isEmpty())
                UsbRequest().apply { initialize(connection, outEndpoint) }
            else outRequestPool.removeFirst()
        }

    private val inRequest: UsbRequest
        get() = synchronized(inRequestPool) {
            return if (inRequestPool.isEmpty())
                UsbRequest().apply { initialize(connection, inEndpoint) }
            else inRequestPool.removeFirst()
        }

    /**
     * An array of open sockets on this device
     */
    private val sockets = SparseArray<AdbSocket>()

    /**
     * Adb message producer
     */
    val adbMessageProducer by lazy { readAdbMessage() }

    init {
        val (inEp, outEp) = usbInterface.getBulkEndpoints()
        inEndpoint = inEp ?: throw IllegalStateException("Adb requires a non-null IN endpoint")
        outEndpoint = outEp ?: throw IllegalStateException("Adb requires a non-null OUT endpoint")
    }

    /**
     * Queue request with the given adb message
     *
     * @param message [AdbMessage] to queue on
     * @return `true` if the queue request was successful, `false` otherwise
     */
    private fun queueAdbMessage(message: AdbMessage): Boolean {
        synchronized(this) {
            val request = outRequest
            request.clientData = message
            if (request.platformQueue(message.header, MESSAGE_HEADER_LENGTH)) {
                if (message.hasPayload()) {
                    val dataRequest = outRequest
                    dataRequest.clientData = message
                    val result = dataRequest.platformQueue(message.payload, message.dataLength)
                    if (!result) releaseOutRequest(dataRequest)
                    return result
                }
                return true
            } else {
                releaseOutRequest(request)
                return false
            }
        }
    }

    /**
     * Close the adb device
     */
    fun close() {
        assert(sockets.size() == 0) // TODO Find out why and perform close
        isConnected = false
        signatureSent = false
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    /**
     * Read message header
     *
     * @param message [AdbMessage] to read to
     * @return `true` if the queue was successful, `false` otherwise
     */
    private fun readHeader(message: AdbMessage): Boolean {
        inRequest.run {
            clientData = message
            return platformQueue(message.header, MESSAGE_HEADER_LENGTH)
        }
    }

    /**
     * Read message payload
     *
     * @param message [AdbMessage] to read to
     * @return `true` if the queue was successful, `false` otherwise
     */
    private fun readPayload(message: AdbMessage): Boolean {
        inRequest.run {
            clientData = message
            return platformQueue(message.payload, message.dataLength)
        }
    }

    /**
     * Release out [UsbRequest] back to pool
     *
     * @param request [UsbRequest] to release
     */
    fun releaseOutRequest(request: UsbRequest) {
        synchronized(outRequestPool) {
            outRequestPool.add(request)
        }
    }

    /**
     * Release in [UsbRequest] back to pool
     *
     * @param request [UsbRequest] to release
     */
    private fun releaseInRequest(request: UsbRequest) {
        synchronized(inRequestPool) {
            inRequestPool.add(request)
        }
    }

    /**
     * Close the given socket
     *
     * @param socket [AdbSocket] to close
     */
    private fun closeSocket(socket: AdbSocket) {
        sockets.remove(socket.localId)
        nextSocketId--
    }

    /**
     * Read an adb message asynchronously
     */
    private fun readAdbMessage() = produce {
        withTimeout(ADB_REQUEST_TIMEOUT) {
            var currentData: AdbMessage? = null
            var currentCommand: AdbMessage? = AdbMessage()
            currentCommand?.let { readHeader(it) }

            while (true) {
                if (!isActive) break
                val request = connection.requestWait() ?: break
                val receivedMessage = request.clientData as AdbMessage
                request.clientData = null
                var dispatchedMessage: AdbMessage? = null
                if (receivedMessage === currentCommand) {
                    if (receivedMessage.hasPayload()) {
                        readPayload(receivedMessage)
                        currentData = receivedMessage
                    } else {
                        dispatchedMessage = receivedMessage
                    }
                    currentCommand = null
                } else if (receivedMessage === currentData) {
                    dispatchedMessage = receivedMessage
                    currentData = null
                }

                if (dispatchedMessage != null) {
                    currentCommand = AdbMessage()
                    readHeader(currentCommand)
                    send(dispatchedMessage)
                }

                if (request.endpoint === outEndpoint) {
                    releaseOutRequest(request)
                } else {
                    releaseInRequest(request)
                }
            }
        }
    }

    /**
     * Connect to the device. This performs the ADB Protocol connection
     * handshake.
     */
    @Throws(IllegalStateException::class)
    fun connect() {
        launch {
            val connectMessage = AdbMessage.generateConnectMessage()
            queueAdbMessage(connectMessage)
            var safetySentinel = 0
            auth_loop@ while (true) {
                val authMessage = adbMessageProducer.receive() ?: break
                when (authMessage.command) {
                    A_AUTH -> {
                        val (type, payload) = if (signatureSent) {
                            val publicKey = Adb.getPublicKey()
                            Pair(RSAPUBLICKEY, publicKey)
                        } else {
                            signatureSent = true
                            val token = authMessage.getPayload()
                            val signature = Adb.signToken(token)
                            Pair(SIGNATURE, signature)
                        }
                        val nextMessage = AdbMessage.generateAuthMessage(type, payload)
                        queueAdbMessage(nextMessage)
                    }

                    A_CNXN -> {
                        if (authMessage.isDeviceOnline()) {
                            logd("Device is online")
                            isConnected = true
                        } else {
                            isConnected = false
                            loge("Error performing device connection handshake ($authMessage)")
                        }
                        break@auth_loop
                    }

                    else -> {
                        val syncMessage = AdbMessage.generateSyncMessage()
                        queueAdbMessage(syncMessage)
                        loge("Invalid command: $authMessage")
                        queueAdbMessage(connectMessage)
                    }
                }
                if (++safetySentinel == 256) {
                    loge("Error with authentication. Done retrying.")
                    isConnected = false
                    break@auth_loop
                }
            }
            if (!isConnected) {
                loge("Device is not initialized properly. Retry initialization.")
                return@launch
            }
            val localFilename = "passenger.jpeg"
            val localPath = File(Adb.externalStorageLocation, localFilename).absolutePath
            sendFile(localPath)
        }
    }

    /**
     * Send a file
     *
     * @param localPath Local absolute file path
     * @param remotePath Remote absolute file path
     */
    fun sendFile(localPath: String, remotePath: String = "sdcard") {
        // TODO When you receive a WRTE, read the message and ack
        launch {
            val localFile = File(localPath)
            val localFilename = localFile.name
            val mode = 33188 // TODO Use local file permissions
            val localId = nextSocketId++
            val socket = AdbSocket(localId, this@AdbDevice)
            sockets.put(localId, socket)
            val openMessage = AdbMessage.generateOpenMessage(localId, "sync:")
            queueAdbMessage(openMessage)
            var responseMessage = adbMessageProducer.receive() ?: return@launch
            socket.remoteId = responseMessage.argumentZero

            var statBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + remotePath.length).order(ByteOrder.LITTLE_ENDIAN)
            statBuffer.putInt(A_STAT).putInt(remotePath.length).put(remotePath)
            var statMessage = AdbMessage.generateWriteMessage(socket.localId, socket.remoteId, statBuffer.array())
            queueAdbMessage(statMessage)
            responseMessage = adbMessageProducer.receive() ?: return@launch
            statBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + remotePath.length + 1).order(ByteOrder.LITTLE_ENDIAN)
            statBuffer.putInt(A_STAT).putInt(remotePath.length + 1).put("$remotePath/")
            statMessage = AdbMessage.generateWriteMessage(socket.localId, socket.remoteId, statBuffer.array())
            queueAdbMessage(statMessage)
            responseMessage = adbMessageProducer.receive() ?: return@launch

            val pathAndMode = "$remotePath/$localFilename,$mode"

            val pathAndModeLength = pathAndMode.length
            if (pathAndModeLength > MAX_PATH_LENGTH) {
                loge("The provided path is too long.")
                throw IllegalStateException("Destination path is too long")
            }
            val sendBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + pathAndModeLength)
                    .order(ByteOrder.LITTLE_ENDIAN)
            sendBuffer.putInt(A_SEND).putInt(pathAndModeLength).put(pathAndMode)
            val sendMessage = AdbMessage.generateWriteMessage(socket.localId, socket.remoteId, sendBuffer.array())
            queueAdbMessage(sendMessage)
            responseMessage = adbMessageProducer.receive() ?: return@launch

            val (lastModified, fileSize, stream) = openStream(localPath) ?: return@launch
            stream.use { file ->
                var bytesCopied = 0
                val dataArray = ByteArray(MAX_BUFFER_LENGTH - SYNC_REQUEST_SIZE)
                while (true) {
                    val bytesRead = file.read(dataArray)
                    if (bytesRead == -1) break
                    val dataBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                    dataBuffer.putInt(A_DATA).putInt(bytesRead).put(dataArray, 0, bytesRead)
                    val dataMessage = AdbMessage.generateWriteMessage(socket.localId, socket.remoteId, dataBuffer.array())
                    queueAdbMessage(dataMessage)
                    bytesCopied += bytesRead
                    responseMessage = adbMessageProducer.receive() ?: return@launch
                }
                val transferred = 100 * bytesCopied / fileSize
                logd("Transferred $transferred% of $localFilename to $remotePath")
            }
            val doneBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            doneBuffer.putInt(A_DONE).putInt(lastModified)
            val doneMessage = AdbMessage.generateWriteMessage(socket.localId, socket.remoteId, doneBuffer.array())
            queueAdbMessage(doneMessage)
            responseMessage = adbMessageProducer.receive() ?: return@launch
            responseMessage = adbMessageProducer.receive() ?: return@launch

            logd("Closing bridge...")
            queueAdbMessage(AdbMessage.generateCloseMessage(socket.localId, socket.remoteId))
            responseMessage = adbMessageProducer.receive() ?: return@launch
            closeSocket(socket)
            logd("Bridge closed")
        }
    }

}