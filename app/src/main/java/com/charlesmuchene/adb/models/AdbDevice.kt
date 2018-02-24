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
    private fun closeSocket(socket: AdbSocket) {
        sockets.remove(socket.localId)
        nextSocketId--
    }

    fun push() {
        val socket = AdbSocket(nextSocketId++, this)
        sockets.put(socket.localId, socket)
        socket.openSocket("sync:")
        val message = socket.read()

        loge("We got $message")

        socket.sendClose()
        closeSocket(socket)
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
    fun connect() {
        launch {
            val connectMessage = AdbMessage.generateConnectMessage()
            queueAdbMessage(connectMessage)
            val producer = readAdbMessage()
            val authMessage = producer.receive() ?: return@launch
            assert(authMessage.command != A_AUTH)
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
            val connectionMessage = producer.receive() ?: return@launch
            assert(connectionMessage.command != A_CNXN)
            if (connectionMessage.isDeviceOnline()) {
                logd("Device is online")
                isConnected = true
            } else {
                loge("Error performing adb device connection handshake")
            }

        }
    }

}