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

package com.charlesmuchene.adb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Environment
import com.charlesmuchene.adb.interfaces.AdbInterface
import com.charlesmuchene.adb.models.AdbDevice
import com.charlesmuchene.adb.utilities.getAdbInterface
import java.io.File

/**
 * Adb utilities
 */
object Adb : AdbInterface {

    private lateinit var keyPath: String
    lateinit var externalStorageLocation: File
        private set

    private external fun initializeAdb(path: String)
    external fun getPublicKey(path: String = keyPath): ByteArray
    external fun signToken(token: ByteArray, path: String = keyPath): ByteArray

    val devices = HashMap<String, AdbDevice>()

    private lateinit var usbManager: UsbManager

    /**
     * Initialize adb utilities
     *
     * @param usbManager [UsbManager] instance
     * @param keyPath Path to the key
     */
    fun initialize(usbManager: UsbManager, keyPath: String) {
        Adb.usbManager = usbManager
        Adb.keyPath = keyPath
        initializeAdb(keyPath)
        externalStorageLocation = setupPaths()
    }

    /**
     * Set up external storage path
     */
    private fun setupPaths(): File {
        // TODO Request for runtime permissions on mobile devices
        val file = File(Environment.getExternalStorageDirectory().path, "Adb")
        file.mkdirs()
        return file
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

    override fun push(localPath: String, remotePath: String) {
        val device = devices.values.firstOrNull() ?: return // TODO Use specific device
//        val localPath = File(Adb.externalStorageLocation, localFilename).absolutePath
        device.sendFile(localPath, remotePath)
    }

    override fun install(apkPath: String, install: Boolean) {
        // TODO Add install implementation
    }

}