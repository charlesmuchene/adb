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

package com.charlesmuchene.adb.interfaces

import com.charlesmuchene.adb.models.AdbDevice

/**
 * Adb interface.
 *
 * Defines adb 'actions' that an [AdbDevice] can perform
 */
interface AdbInterface {

    /**
     * Push a file to connect adb device
     *
     * @param localPath Path of the local file
     * @param remotePath Path of the remote file
     */
    fun push(localPath: String, remotePath: String)

    /**
     * Install an apk to the connected adb device
     *
     * @param apkPath Path of the apk on the host
     * @param install `true` to install the apk otherwise just install
     */
    fun install(apkPath: String, install: Boolean)
}