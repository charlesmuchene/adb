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
import com.charlesmuchene.adb.utilities.MAX_BUFFER_PAYLOAD
import com.charlesmuchene.adb.utilities.getBulkEndpoints

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
        // TODO Connect
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
     * Send data to device
     *
     * @param message Payload to send
     */
    private fun send(message: AdbMessage) {
        if (message.isSmallPayload()) {
            transfer(message.getTotalPayload())
        } else {
            transfer(message.headerBuffer.array())
            if (message.hasDataPayload())
                sendLargePayload(message.dataBuffer.array())
        }
    }

    /**
     * Split and send large payload
     *
     * @param data Payload to send
     */
    private fun sendLargePayload(data: ByteArray) {
        val payload = ByteArray(MAX_BUFFER_PAYLOAD)
        val size = data.size
        val chunks = (size / MAX_BUFFER_PAYLOAD) + if (size % MAX_BUFFER_PAYLOAD != 0) 1 else 0
        val stream = data.inputStream()

        for (chunk in 0..chunks) {
            val length = stream.read(payload)
            if (length != -1)
                transfer(payload, length)
        }
    }

    /**
     * Transfer data to device
     *
     * @param data Data buffer
     */
    private fun transfer(data: ByteArray, length: Int = data.size) {
        connection.bulkTransfer(outEndpoint, data, length, 100)
    }

    override fun push(localPath: String, remotePath: String) {
        // TODO Add push file implementation
    }

    override fun install(apkPath: String, install: Boolean) {
        // TODO Add install implementation
    }
}