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

package com.charlesmuchene.adb.utilities

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.charlesmuchene.adb.models.AdbDevice

/**
 * Adb utilities
 */
object Adb {

    val devices = HashMap<String, AdbDevice>()

    private lateinit var usbManager: UsbManager

    /**
     * Initialize adb utilities
     *
     * @param context [Context] instance
     */
    fun initialize(context: Context) {
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    /**
     * Adds an [UsbDevice] to the devices list
     *
     * @param device [UsbDevice] to use for constructing [AdbDevice]
     */
    fun addDevice(device: UsbDevice) {
        val usbInterface = device.getAdbInterface() ?: return
        val connection = usbManager.openDevice(device)
        devices[device.deviceName] = AdbDevice(usbInterface, connection)
    }

    /**
     * Remove [UsbDevice]
     *
     * @param device [UsbDevice] to remove
     */
    fun removeDevice(device: UsbDevice) {
        devices.remove(device.deviceName)
    }

}