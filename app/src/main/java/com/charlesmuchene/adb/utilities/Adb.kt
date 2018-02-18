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
import com.charlesmuchene.adb.interfaces.AdbInterface
import com.charlesmuchene.adb.models.AdbDevice
import com.charlesmuchene.adb.models.AdbMessage

/**
 * Adb utilities
 */
object Adb : AdbInterface {

    private var keyPath = ""

    private external fun initializeAdb(path: String)
    external fun getPublicKey(path: String = keyPath): ByteArray
    external fun signToken(token: ByteArray, path: String = keyPath): ByteArray

    val devices = HashMap<String, AdbDevice>()

    private lateinit var usbManager: UsbManager

    /**
     * Initialize adb utilities
     *
     * @param context [Context] instance
     */
    fun initialize(context: Context) {
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        keyPath = context.filesDir.absolutePath
        initializeAdb(keyPath)
    }

    /**
     * Adds an [UsbDevice] to the devices list
     *
     * @param device [UsbDevice] to use for constructing [AdbDevice]
     */
    fun addDevice(device: UsbDevice) {
        val usbInterface = device.getAdbInterface() ?: return
        val connection = usbManager.openDevice(device) ?: return
        val success = connection.claimInterface(usbInterface, false)
        if (success) {
            val adbDevice = AdbDevice(usbInterface, connection)
            devices[device.deviceName] = adbDevice
            adbDevice.connect()
        } else {
            connection.close()
        }
    }

    /**
     * Remove [UsbDevice]
     *
     * @param device [UsbDevice] to remove
     */
    fun removeDevice(device: UsbDevice) {
        val adbDevice = devices.remove(device.deviceName) ?: return
        adbDevice.close()
    }

    /**
     * Enumerate and add already connected adb devices
     */
    fun addExistingDevices() {
        usbManager.deviceList.values.forEach(this::addDevice)
    }

    /**
     * Close all devices
     */
    fun disconnect() {
        devices.values.forEach { it.close() }
    }

    /**
     * Write data to device
     *
     * @param device [AdbDevice] to write data to
     * @param message Payload to send
     *
     * TODO Perform in aux thread
     */
    private fun write(device: AdbDevice, message: AdbMessage) {
        transfer(device, message.header.array())
        if (message.hasPayload())
            sendLargePayload(device, message.getPayload())
    }

    /**
     * Read message payload from device
     *
     * @return [AdbMessage] as the read payload
     *
     * TODO Perform in aux thread
     */
    private fun read(): AdbMessage? {
        // TODO Add implementation
        return null
    }

    /**
     * Split and send payload
     *
     * @param device [AdbDevice] to send payload to
     * @param data Payload to send
     *
     * TODO Perform in aux thread
     */
    private fun sendLargePayload(device: AdbDevice, data: ByteArray) {
        val payload = ByteArray(MAX_BUFFER_LENGTH)
        val size = data.size
        val chunks = (size / MAX_BUFFER_LENGTH) + if (size % MAX_BUFFER_LENGTH != 0) 1 else 0
        val stream = data.inputStream()

        for (chunk in 0 until chunks) {
            val length = stream.read(payload)
            if (length != -1)
                transfer(device, payload, length)
        }
    }

    /**
     * Transfer data to device
     *
     * @param device [AdbDevice] to transfer data to
     * @param data Data buffer
     * @param length The size of data to send
     *
     * TODO Perform in aux thread
     */
    private fun transfer(device: AdbDevice, data: ByteArray, length: Int = data.size) {
        val transferredBytes = device.connection.bulkTransfer(device.outEndpoint,
                data, length, 10)
        logd("Transferred ${(transferredBytes / length) * 100}% of payload")
    }

    override fun push(localPath: String, remotePath: String) {
        // TODO Add push file implementation
    }

    override fun install(apkPath: String, install: Boolean) {
        // TODO Add install implementation
    }

}