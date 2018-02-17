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

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Find an adb interface from the device descriptors
 *
 * @return [UsbInterface] or null if device doesn't have any
 */
fun UsbDevice.getAdbInterface(): UsbInterface? {
    return (0 until interfaceCount)
            .map { getInterface(it) }
            .firstOrNull {
                it.interfaceClass == 255
                        && it.interfaceSubclass == 66
                        && it.interfaceProtocol == 1
            }
}

/**
 * Find bulk transfer [UsbEndpoint] for the given [UsbInterface].
 * The endpoints can be null if the [UsbInterface] doesn't have bulk [UsbEndpoint]s.
 *
 * @return [Pair] of any bulk [UsbEndpoint]s found.
 */
fun UsbInterface.getBulkEndpoints(): Pair<UsbEndpoint?, UsbEndpoint?> {
    var inEp: UsbEndpoint? = null
    var outEp: UsbEndpoint? = null
    (0 until endpointCount)
            .map { getEndpoint(it) }
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            .forEach {
                if (it.direction == UsbConstants.USB_DIR_IN) inEp = it
                else outEp = it
            }
    return Pair(inEp, outEp)
}