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
            auth_loop@while (true) {
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

                    A_CLSE -> {
                        loge("Device requested to close bridge")
                        isConnected = false
                        break@auth_loop
                    }

                    else -> {
                        loge("Unknown command in: $authMessage")
                        break@auth_loop
                    }
                }
            }
            val lfilename = "me.txt"
            val lpath = File(Adb.externalStorageLocation, lfilename).absolutePath
            val rpath = "sdcard"
            if (!isConnected) {
                loge("Unauthorized device: Perform connection first.")
                return@launch
            }
            logd("Setting up send file socket...")
            val localId = nextSocketId++
            val socket = AdbSocket(localId, this@AdbDevice)
            sockets.put(localId, socket)
//        val job = launch {
            val openMessage = AdbMessage.generateOpenMessage(localId, "sync:")
            queueAdbMessage(openMessage)
            val responseMessage = adbMessageProducer.receive() ?: return@launch
//            socket.setRemoteId(responseMessage.argumentZero)
            loge("Setup message $responseMessage")
            socket.remoteId = responseMessage.argumentZero

            var b = ByteBuffer.allocate(4 + 4 + rpath.length).order(ByteOrder.LITTLE_ENDIAN)
//            b.put("STAT")
//            b.putInt(Integer.reverseBytes(rpath.length))
//            b.put(rpath)
            b.putInt(A_STAT).putInt(rpath.length).put(rpath)
            loge("Buffer Capacity: ${b.capacity()} path length = ${rpath.length}")
            val m = AdbMessage.generateWriteMessage(socket.localId, socket.remoteId, b.array())
            loge("Queueing $m")
            val q = queueAdbMessage(m)
            if (q) loge("success queueing") else loge("What do we do now")
//            val queued = socket.sendWriteMessage(A_STAT, remotePath.toByteArray())
//            if (queued) {
//                loge("Queued")
//            } else loge("Not queued no need reading")

            var message = /*device.*/adbMessageProducer.receive() //?: return@asyncExecute
            if (message == null) {
                loge("We got null")
                return@launch
            }
            assert(message.command == A_OKAY)
            logd("Received okay: $message")
            b = ByteBuffer.allocate(4 + 4 + rpath.length + 1).order(ByteOrder.LITTLE_ENDIAN)
//            b.put("STAT")
//            b.putInt(Integer.reverseBytes(rpath.length))
//            b.put(rpath)
            b.putInt(A_STAT).putInt(rpath.length + 1).put("$rpath/")
            val n = AdbMessage.generateWriteMessage(socket.localId, socket.remoteId, b.array())
            val r = queueAdbMessage(n)
            if (r) loge("success queueing second path") else loge("What do we do now yawe")
            message = adbMessageProducer.receive() ?: return@launch
            loge("For with path we got $message")
            loge("And we have a ${message.getFileStat()}")

            val okayMessage = AdbMessage.generateOkayMessage(socket.localId, socket.remoteId)
            queueAdbMessage(okayMessage)

            val mode = 33188
            val absRemotePath = "$rpath/$lfilename"
            val pathAndMode = "$absRemotePath,$mode"

            val (lastModified, fileSize, stream) = openStream(lpath) ?: return@launch

            if (fileSize < MAX_BUFFER_LENGTH) logd("Send small file")
            else logd("Send a large file")

            val pathAndModeLength = pathAndMode.length
            @Suppress("ConstantConditionIf")
            if (pathAndModeLength > MAX_PATH_LENGTH) {
                stream.close()
                loge("The provided path is too long.")
                throw IllegalStateException("Destination path is too long")
            }
            logd("Sending file...")

            val data = ByteArray(fileSize)
            val bytesRead = stream.read(data)
            stream.close()
            logd("$bytesRead bytes read")
            assert(bytesRead == fileSize, { "Error in assessing file size" })
//             Buffer length = 3 * sync_request_size (8) + path_mode_length + actual_data_length
            val bufferLength = 24 + pathAndModeLength + bytesRead
            val buffer = ByteBuffer.allocate(bufferLength).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(A_SEND)
                    .putInt(pathAndModeLength)
                    .put(pathAndMode)
                    .putInt(A_DATA)
                    .putInt(bytesRead)
                    .put(data)
                    .putInt(A_DONE)
                    .putInt(lastModified)
            val dataMessage = AdbMessage.generateWriteMessage(socket.localId, socket.remoteId, buffer.array())
            loge("Sending $dataMessage")
            queueAdbMessage(dataMessage)
            message = adbMessageProducer.receive() ?: return@launch
            assert(message.command == A_OKAY)
            loge("Got something: $message")
//            loge("Sending small file done") // TODO Make it a debug log

//            stream.close()
            queueAdbMessage(AdbMessage.generateQuitMessage(socket.localId, socket.remoteId))
            message = adbMessageProducer.receive() ?: return@launch
            loge("For Okay: Got $message ${message.getFileStat()}")
            message = adbMessageProducer.receive() ?: return@launch
            loge("Last okay $message")
            queueAdbMessage(AdbMessage.generateOkayMessage(socket.localId, socket.remoteId))
            queueAdbMessage(AdbMessage.generateQuitMessage(socket.localId, socket.remoteId))
            message = adbMessageProducer.receive() ?: return@launch
            loge("For quitting? $message")
            queueAdbMessage(AdbMessage.generateCloseMessage(socket.localId, socket.remoteId))
            message = adbMessageProducer.receive() ?: return@launch
            loge("For close? $message")
            closeSocket(socket)
            loge("Socket disposed")
        }
    }

    /**
     * Send a file
     *
     * @param localPath Local absolute file path
     * @param remotePath Remote absolute file path
     */
    fun sendFile(localPath: String, remotePath: String = "sdcard") {
//        if (!isConnected) {
//            loge("Unauthorized device: Perform connection first.")
//            return
//        }
//        logd("Setting up send file socket...")
//        val localId = nextSocketId++
//        val socket = AdbSocket(localId, this@AdbDevice)
//        sockets.put(localId, socket)
//        val job = launch {
//            val openMessage = AdbMessage.generateOpenMessage(localId, "sync:")
//            queueAdbMessage(openMessage)
//            val responseMessage = adbMessageProducer.receive() ?: return@launch
//            socket.setRemoteId(responseMessage.argumentZero)
//            loge("Setup message $responseMessage")
//            socket.remoteId = responseMessage.argumentZero
//
//            var b = ByteBuffer.allocate(4 + 4 + remotePath.length)
//            b.put("STAT")
//            b.putInt(Integer.reverseBytes(remotePath.length))
//            b.put(remotePath)
//
//            val m = AdbMessage.generateWriteMessage(socket.localId, socket.remoteId, b.array())
//            val q = queueAdbMessage(m)
//            if (q) loge("success queueing") else loge("What do we do now")
//            val queued = socket.sendWriteMessage(A_STAT, remotePath.toByteArray())
//            if (queued) {
//                loge("Queued")
//            } else loge("Not queued no need reading")

//            var message = /*device.*/adbMessageProducer.receive() //?: return@asyncExecute
//            if (message == null) {
//                loge("We got null")
//                return//@launch
//            }
//            assert(message.command == A_OKAY)
//            logd("Received okay: $message")
//            queueAdbMessage(AdbMessage.generateCloseMessage(socket.localId, socket.remoteId))
//            closeSocket(socket)
//            loge("Socket disposed")
            // TODO If directory, stat again with '/' else quit
//            socket.sendWriteMessage(A_STAT, "$remotePath/".toByteArray())

//            message = /*device.*/adbMessageProducer.receive() ?: return@launch
//            loge("We got $message")
//            assert(message.command == A_OKAY)

//            val pathAndMode = "$remotePath,33188"
//            val pathAndModeLength = pathAndMode.length
//            if (pathAndModeLength > MAX_PATH_LENGTH) throw IllegalStateException("Path too long")
//
//            socket.sendWriteMessage(A_SEND, pathAndMode.toByteArray(), pathAndModeLength)

//            val array = ByteArray(MESSAGE_PAYLOAD - SYNC_REQUEST_SIZE)
//            val (lastModified, fileSize, stream) = openStream(localPath) ?: return@launch
//
//            val data = ByteArray(fileSize)
//            val bytesRead = stream.read(data)
//            assert(bytesRead == fileSize) // for small payload
//            stream.close()
//            val bufferLength = SYNC_REQUEST_SIZE * 3 + pathAndModeLength + bytesRead
//            loge("Buffer length $bufferLength")
//            val buffer = ByteBuffer.allocate(bufferLength).order(ByteOrder.LITTLE_ENDIAN)
//            buffer.putInt(A_SEND)
//                    .putInt(pathAndModeLength)
//                    .put(pathAndMode)
//                    .putInt(A_DATA)
//                    .putInt(bytesRead)
//                    .put(data)
//                    .putInt(A_DONE)
//                    .putInt(lastModified)
//
//            val smallPayloadMessage = AdbMessage.generateWriteMessage(localId, socket.remoteId, buffer.array())
//            socket.write(smallPayloadMessage)
//            sendWriteMessage(A_)
//            socket.synchronousWrite(buffer.array())
//            message = /*device.*/adbMessageProducer.receive() ?: return@launch
//            assert(message.command == A_OKAY)
//            loge("Done sending file $message")
//            sendSubCommand(A_QUIT)

//        }
        // TODO Dispose this handler on error, device close??
//        job.invokeOnCompletion { throwable ->
//            if (throwable != null) closeSocket(socket)
//            else socket.sendFile(localPath, remotePath)
//        }

    }

}