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
import com.charlesmuchene.adb.interfaces.AdbInterface
import com.charlesmuchene.adb.utilities.MAX_BUFFER_LENGTH
import com.charlesmuchene.adb.utilities.MESSAGE_HEADER_LENGTH
import com.charlesmuchene.adb.utilities.getBulkEndpoints
import com.charlesmuchene.adb.utilities.logd
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adb device
 */
class AdbDevice(private val usbInterface: UsbInterface, private val connection: UsbDeviceConnection)
    : AdbInterface {

    private val inEndpoint: UsbEndpoint
    private val outEndpoint: UsbEndpoint

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
        transfer(message.headerBuffer.array())
        if (message.hasDataPayload())
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
        // Read message header
        val header = ByteBuffer.allocate(MESSAGE_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        var dataRead = 0
        do {
            val bytesRead = connection.bulkTransfer(inEndpoint, header.array(), dataRead,
                    MESSAGE_HEADER_LENGTH - dataRead, 10)
            if (bytesRead <= 0) return null
            dataRead += bytesRead
        } while (dataRead < MESSAGE_HEADER_LENGTH)

        header.flip()

        // TODO Read data, if any

        return AdbMessage(header.array())
    }

    override fun push(localPath: String, remotePath: String) {
        // TODO Add push file implementation
    }

    override fun install(apkPath: String, install: Boolean) {
        // TODO Add install implementation
    }
}