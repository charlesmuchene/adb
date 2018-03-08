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
import com.charlesmuchene.adb.interfaces.AdbProtocol
import com.charlesmuchene.adb.utilities.*
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.launch
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adb device
 */
class AdbDevice(private val usbInterface: UsbInterface, val connection: UsbDeviceConnection)
    : AdbProtocol {

    private val job = Job()
    private var isConnected = false
    private var signatureSent = false
    private val inEndpoint: UsbEndpoint
    private val outEndpoint: UsbEndpoint
    private var nextSocketId = AtomicInteger()
    private val channel by lazy { adbChannel() }
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
        isConnected = false
        signatureSent = false
        job.cancel()
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
        if (nextSocketId.get() > 0)
            nextSocketId.getAndDecrement()
    }

    /**
     * Create a channel to receive [AdbMessage]
     *
     * @return A channel to receive adb messages
     */
    private fun adbChannel() = produce<AdbMessage> {
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
    fun initialize() {
        launch(parent = job) {
            while (!isConnected) {
                val message = channel.receive()
                when (message.command) {
                    A_AUTH, A_CNXN -> authenticate(message)
                    A_CLSE -> {
                        sockets.get(message.argumentOne)?.let(::closeSocket)
                        logw("Closed dangling socket in authentication")
                    }
                    else -> {
                        loge("Unexpected message: -> $message")
                        queueAdbMessage(AdbMessage.generateSyncMessage())
                    }
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
                    val path = File(Adb.externalStorageLocation, "app-debug.apk").absolutePath
                    install(path)
//                    push(path, "sdcard")
                } else {
                    isConnected = false
                    loge("Error performing device connection handshake ($message)")
                }
            }
        }
    }

    override fun push(localPath: String, remotePath: String): Job {
        val localId = nextSocketId.incrementAndGet()
        val socket = AdbSocket(localId, this, channel)
        sockets.put(localId, socket)
        return socket.push(localPath, remotePath)
    }

    override fun install(apkPath: String, launch: Boolean) {
        launch {
            push(apkPath, AdbSocket.tmpDir).join()
        }.invokeOnCompletion {
            val localId = nextSocketId.incrementAndGet()
            val socket = AdbSocket(localId, this, channel)
            sockets.put(localId, socket)
            socket.install(apkPath, launch)
        }
    }
}