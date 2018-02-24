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
     * Adb thread
     */
    private val adbThread = AdbThread()

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
     * Connect to the device. This performs the ADB Protocol connection
     * handshake.
     */
    fun connect() {
        adbThread.start()
        val connectMessage = AdbMessage.generateConnectMessage()
        val result = queueAdbMessage(connectMessage)
        if (!result) {
            adbThread.interrupt()
            close()
            loge("Error queueing connect message")
        }
    }

    /**
     * Process the dispatched message
     *
     * @param message [AdbMessage] to process
     */
    private fun dispatchMessage(message: AdbMessage) {

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

                val nextMessage = AdbMessage.generateAuthMessage(type, payload)
                queueAdbMessage(nextMessage)

            }

            A_CNXN -> {

                if (message.isDeviceOnline()) {
                    logd("Device is online")
                    isConnected = true
                }

                adbThread.interrupt()

                push()
            }

            A_CLSE -> {
                logd("Close the connection")
                isConnected = false
                close()
            }

            else -> {
                logw("Received an unknown command in message: $message")
            }
        }
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
        adbThread.interrupt()
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
     * Adb Thread
     */
    private inner class AdbThread : Thread("AdbThread") {
        override fun run() {
            super.run()
            var currentCommand: AdbMessage? = AdbMessage()
            var currentData: AdbMessage? = null
            readHeader(currentCommand!!)

            while (true) {
                if (isInterrupted) break
                val request = connection.requestWait() ?: break
                val message = request.clientData as AdbMessage
                request.clientData = null
                var dispatchedMessage: AdbMessage? = null
                if (message === currentCommand) {
                    if (message.hasPayload()) {
                        readPayload(message)
                        currentData = message
                    } else {
                        dispatchedMessage = message
                    }
                    currentCommand = null
                } else if (message === currentData) {
                    dispatchedMessage = message
                    currentData = null
                }

                if (dispatchedMessage != null) {
                    currentCommand = AdbMessage()
                    readHeader(currentCommand)
                    dispatchMessage(dispatchedMessage)
                }

                if (request.endpoint === outEndpoint) {
                    releaseOutRequest(request)
                } else {
                    releaseInRequest(request)
                }
            }
        }
    }

}