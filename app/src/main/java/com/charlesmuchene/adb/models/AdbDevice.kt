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
    private fun connect() {
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

    override fun push(localPath: String, remotePath: String) {
        // TODO Add push file implementation
    }

    override fun install(apkPath: String, install: Boolean) {
        // TODO Add install implementation
    }
}