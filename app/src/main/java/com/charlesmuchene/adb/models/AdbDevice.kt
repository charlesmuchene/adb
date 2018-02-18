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
import android.os.Build
import com.charlesmuchene.adb.interfaces.AdbInterface
import com.charlesmuchene.adb.utilities.MAX_BUFFER_LENGTH
import com.charlesmuchene.adb.utilities.MESSAGE_HEADER_LENGTH
import com.charlesmuchene.adb.utilities.getBulkEndpoints
import com.charlesmuchene.adb.utilities.logd
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import java.util.*
import kotlin.concurrent.thread

/**
 * Adb device
 */
class AdbDevice(private val usbInterface: UsbInterface, private val connection: UsbDeviceConnection)
    : AdbInterface {

    private val inEndpoint: UsbEndpoint
    private val outEndpoint: UsbEndpoint
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
    private val adbThread = thread {
        runBlocking {
            launch {
                readAdbMessage()
            }.join()
        }
    }

    init {
        val (inEp, outEp) = usbInterface.getBulkEndpoints()
        inEndpoint = inEp ?: throw IllegalStateException("Adb requires a non-null IN endpoint")
        outEndpoint = outEp ?: throw IllegalStateException("Adb requires a non-null OUT endpoint")
    }

    /**
     * Connect to the device
     */
    fun connect() {
        val connectMessage = AdbMessage.generateConnectMessage()
        logd("Connection message: $connectMessage")
        write(connectMessage)

        val authMessage = read()
        logd(authMessage.toString())
    }

    /**
     * Close the adb device
     */
    fun close() {
        // TODO Stop all ongoing actions
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    /**
     * Write data to device
     *
     * @param message Payload to send
     *
     * TODO Perform in aux thread
     */
    private fun write(message: AdbMessage) {
        transfer(message.header.array())
        if (message.hasPayload())
            sendLargePayload(message.getPayload())
    }

    /**
     * Split and send large payload
     *
     * @param data Payload to send
     *
     * TODO Perform in aux thread
     */
    private fun sendLargePayload(data: ByteArray) {
        val payload = ByteArray(MAX_BUFFER_LENGTH)
        val size = data.size
        val chunks = (size / MAX_BUFFER_LENGTH) + if (size % MAX_BUFFER_LENGTH != 0) 1 else 0
        val stream = data.inputStream()

        for (chunk in 0 until chunks) {
            val length = stream.read(payload)
            if (length != -1)
                transfer(payload, length)
        }
    }

    /**
     * Transfer data to device
     *
     * @param data Data buffer
     * @param length The size of data to send
     *
     * TODO Perform in aux thread
     */
    private fun transfer(data: ByteArray, length: Int = data.size) {
        val transferredBytes = connection.bulkTransfer(outEndpoint, data, length, 1000)
        logd("Transferred ${(transferredBytes / length) * 100}% of payload")
    }

    /**
     * Read message payload from device
     *
     * @return [AdbMessage] as the read payload
     *
     * TODO Perform in aux thread
     */
    private fun read(): AdbMessage? {

        return null
    }

    /**
     * Read adb message
     */
    private fun readAdbMessage() {
        var dispatchedMessage: AdbMessage?
        var currentCommand: AdbMessage? = AdbMessage()
        var currentData: AdbMessage? = null
        readHeader(currentCommand!!)

        while (true) {
            val request = connection.requestWait() ?: return
            val message = request.clientData as AdbMessage
            request.clientData = null
            dispatchedMessage = null

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

            if (dispatchedMessage != null)
                break

            if (request.endpoint === outEndpoint) {
                releaseOutRequest(request)
            } else {
                releaseInRequest(request)
            }
        }

        // TODO Use dispatched message

    }

    /**
     * Read message header
     *
     * @param message [AdbMessage] to read to
     * @return `true` if the queue was successful, `false` otherwise
     */
    private fun readHeader(message: AdbMessage): Boolean {
        val request = inRequest
        request.clientData = message
        @Suppress("DEPRECATION")
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            request.queue(message.header)
        else request.queue(message.header, MESSAGE_HEADER_LENGTH)
        releaseInRequest(request)
        return result
    }

    /**
     * Read message payload
     *
     * @param message [AdbMessage] to read to
     * @return `true` if the queue was successful, `false` otherwise
     */
    private fun readPayload(message: AdbMessage): Boolean {
        val request = inRequest
        request.clientData = message
        @Suppress("DEPRECATION")
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            request.queue(message.payload)
        else request.queue(message.payload, message.dataLength)
        releaseInRequest(request)
        return result
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

    override fun push(localPath: String, remotePath: String) {
        // TODO Add push file implementation
    }

    override fun install(apkPath: String, install: Boolean) {
        // TODO Add install implementation
    }
}