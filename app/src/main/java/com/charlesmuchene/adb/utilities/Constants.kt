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

/**
 * Maximum payload constants
 */
const val MAX_BUFFER_PAYLOAD = 16_384

/**
 * Adb message constants
 */
const val MESSAGE_HEADER_PAYLOAD = 24
const val MESSAGE_DATA_PAYLOAD = 65_536

/**
 * Adb sync request constants
 */
const val A_SYNC = 0x434e5953
const val A_CNXN = 0x4e584e43
const val A_OPEN = 0x4e45504f
const val A_OKAY = 0x59414b4f
const val A_CLSE = 0x45534c43
const val A_WRTE = 0x45545257
const val A_AUTH = 0x48545541
const val A_STAT = 0x54415453
const val A_SEND = 0x444E4553
const val A_RECV = 0x56434552
const val A_QUIT = 0x54495551
const val A_FAIL = 0x4c494146
const val A_DONE = 0x454e4f44
const val A_DATA = 0x41544144
const val A_LIST = 0x5453494c