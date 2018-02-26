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

import java.nio.ByteBuffer

/**
 * Remote path stat
 */
class FileStat(payload: ByteBuffer) {
    val id: String = String(payload.array(), 0, 4)
    val mode: Int = payload.getInt(4)
    val size: Int = payload.getInt(8)
    val time: Int = payload.getInt(12)
    val nameLength: Int = payload.getInt(16)

    override fun toString(): String {
        return "FileStat(id='$id', mode=$mode, size=$size, time=$time, nameLength=$nameLength)"
    }

}