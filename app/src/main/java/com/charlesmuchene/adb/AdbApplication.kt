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

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import com.charlesmuchene.adb.utilities.Adb
import timber.log.Timber

/**
 * Adb application
 */
class AdbApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val keyPath = filesDir.absolutePath
        Adb.initialize(usbManager, keyPath)

        Timber.plant(Timber.DebugTree())
    }

    companion object {
        init {
            System.loadLibrary("adb")
        }
    }
}