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
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.launch
import java.util.*

/**
 * Adb device
 */
class AdbDevice(private val usbInterface: UsbInterface, val connection: UsbDeviceConnection) {

    private var nextSocketId = 1
    private var isConnected = false
    private val inEndpoint: UsbEndpoint
    private val outEndpoint: UsbEndpoint
    private var signatureSent = false
    private val inRequestPool = LinkedList<UsbRequest>()
    private val outRequestPool = LinkedList<UsbRequest>()

    private val outRequest: UsbRequest
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
     * Adb message receiver channel
     */
    val adbMessageReceiverChannel by lazy { readAdbMessage() }

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
    fun queueAdbMessage(message: AdbMessage): Boolean {
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
        adbMessageReceiverChannel.cancel()
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
    private fun releaseOutRequest(request: UsbRequest) {
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
    fun closeSocket(socket: AdbSocket) {
        sockets.remove(socket.localId)
        nextSocketId--
    }

    /**
     * Query for an adb message asynchronously
     *
     * @return A producer of adb messages
     */
    private fun readAdbMessage() = produce<AdbMessage> {
        var incomingData: AdbMessage? = null
        var incomingCommand: AdbMessage? = AdbMessage()
        incomingCommand?.let(::readHeader)

        while (true) {
            if (!isActive) break
            val request = connection.requestWait() ?: break
            val receivedMessage = request.clientData as AdbMessage
            request.clientData = null
            var dispatchedMessage: AdbMessage? = null
            if (receivedMessage === incomingCommand) {
                if (receivedMessage.hasPayload()) {
                    readPayload(receivedMessage)
                    incomingData = receivedMessage
                } else {
                    dispatchedMessage = receivedMessage
                }
                incomingCommand = null
            } else if (receivedMessage === incomingData) {
                dispatchedMessage = receivedMessage
                incomingData = null
            }

            if (dispatchedMessage != null) {
                incomingCommand = AdbMessage()
                readHeader(incomingCommand)
                send(dispatchedMessage)
            }

            if (request.endpoint === outEndpoint) {
                releaseOutRequest(request)
            } else {
                releaseInRequest(request)
            }
        }
    }

    /**
     * Initialize ADB connection. This invocation starts a coroutine to read
     * messages from device.
     */
    @Throws(IllegalStateException::class)
    fun initialize() {
        launch {
            adbMessageReceiverChannel.consumeEach { message ->
                loge("Working on $message")
                when (message.command) {
                    A_AUTH, A_CNXN -> authenticate(message)
                    A_OKAY, A_CLSE, A_WRTE, A_OPEN -> consumeMessage(message)
                    else -> loge("We are in trouble :D -> $message")
                }
            }
        }
        queueAdbMessage(AdbMessage.generateConnectMessage())
    }

    /**
     * Perform device authentication
     *
     * @param message [AdbMessage] with authentication payload
     */
    private fun authenticate(message: AdbMessage) {
        when (message.command) {
            A_AUTH -> {
                val (type, payload) = if (signatureSent) {
                    val publicKey = Adb.getPublicKey()
                    Pair(RSAPUBLICKEY, publicKey)
                } else {
                    signatureSent = true
                    val token = message.getPayload()
                    val signature = Adb.signToken(token)
                    Pair(SIGNATURE, signature)
                }
                queueAdbMessage(AdbMessage.generateAuthMessage(type, payload))
            }

            A_CNXN -> {
                if (message.isDeviceOnline()) {
                    logd("Device is online")
                    isConnected = true
                    installer()
                } else {
                    isConnected = false
                    loge("Error performing device connection handshake ($message)")
                }
            }
        }
    }

    /**
     * Process the device message response
     *
     * @param message [AdbMessage] from device
     */
    private fun consumeMessage(message: AdbMessage) {
        sockets.get(message.argumentOne)?.processMessage(message)
    }

    /**
     * Send a file
     *
     * @param localPath Local absolute file path
     * @param remotePath Remote absolute file path
     */
    fun sendFile(localPath: String, remotePath: String = "sdcard") {
        val localId = nextSocketId++
        val socket = AdbSocket(localId, this)
        sockets.put(localId, socket)
        socket.sendFile(localPath, remotePath)
    }

    fun installer() {
        try {
            loge("Installing")
            val localId = nextSocketId++
            val socket = AdbSocket(localId, this)
            sockets.put(localId, socket)
            queueAdbMessage(AdbMessage.generateOpenMessage(localId, "shell: ls"))
//            socket.installApk()
        } catch (e: Exception) {
            loge(e.localizedMessage)
        }
    }
}