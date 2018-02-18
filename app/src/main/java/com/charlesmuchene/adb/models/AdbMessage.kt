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

import com.charlesmuchene.adb.utilities.A_CNXN
import com.charlesmuchene.adb.utilities.A_VERSION
import com.charlesmuchene.adb.utilities.MESSAGE_HEADER_LENGTH
import com.charlesmuchene.adb.utilities.MESSAGE_PAYLOAD
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adb message
 */
class AdbMessage {

    /**
     * Message header buffer
     */
    val headerBuffer: ByteBuffer = ByteBuffer.allocate(MESSAGE_HEADER_LENGTH)
            .order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Data payload buffer
     */
    private val dataBuffer: ByteBuffer = ByteBuffer.allocate(MESSAGE_PAYLOAD)
            .order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Command
     */
    val command: Int
        get() = headerBuffer.getInt(0)

    /**
     * Argument zero
     */
    val argumentZero: Int
        get() = headerBuffer.getInt(4)

    /**
     * Argument one
     */
    val argumentOne: Int
        get() = headerBuffer.getInt(8)

    /**
     * Length of the data payload
     */
    private val dataLength: Int
        get() = headerBuffer.getInt(12)

    /**
     * Data payload as string
     */
    private val dataPayloadAsString: String?
        get() = if (dataLength <= 0 || dataLength >= MESSAGE_PAYLOAD) null
        else String(dataBuffer.array(), 0, dataLength - 1)

    constructor()

    /**
     * Constructor
     *
     * @param payload [ByteArray] to construct the message with
     */
    constructor(payload: ByteArray) {
        headerBuffer.put(payload, 0, MESSAGE_HEADER_LENGTH)
//        if (hasDataPayload())
//            dataBuffer.put(payload, MESSAGE_HEADER_LENGTH, payload.size)
    }

    /**
     * Check if message has data payload
     *
     * @return `true` if this message has a data payload, `false` otherwise
     */
    fun hasDataPayload() = dataLength > 0

    /**
     * Get the message's payload
     *
     * @return [ByteArray] as the payload
     */
    fun getPayload(): ByteArray {
        return when {
            dataLength <= 0 -> return ByteArray(0)
            dataLength == MESSAGE_PAYLOAD -> dataBuffer.array()
            else -> dataBuffer.array().copyOfRange(0, dataLength)
        }
    }

    /**
     * Set up the message with a byte array payload
     *
     * @param command Adb command constant
     * @param argumentZero Argument zero
     * @param argumentOne Argument one
     * @param data Data payload as a [ByteArray]
     */
    operator fun set(command: Int, argumentZero: Int, argumentOne: Int, data: ByteArray?) {
        with(headerBuffer) {
            putInt(0, command)
            putInt(4, argumentZero)
            putInt(8, argumentOne)
            putInt(12, data?.size ?: 0)
            putInt(16, if (data == null) 0 else checksum(data))
            putInt(20, command.inv())
        }

        if (data != null) dataBuffer.put(data, 0, data.size)
    }

    /**
     * Set up the message with a byte buffer payload
     *
     * @param command Adb command constant
     * @param argumentZero Argument zero
     * @param argumentOne Argument one
     * @param data Data payload as a [ByteBuffer]
     */
    operator fun set(command: Int, argumentZero: Int, argumentOne: Int, data: ByteBuffer) {
        set(command, argumentZero, argumentOne, data.array())
    }

    /**
     * Set up the message with a string payload
     *
     * @param command Adb command constant
     * @param argumentZero Argument zero
     * @param argumentOne Argument one
     * @param data Data payload as string
     */
    operator fun set(command: Int, argumentZero: Int, argumentOne: Int, data: String) {
        val dataPayload = data + "\u0000"
        set(command, argumentZero, argumentOne, dataPayload.toByteArray())
    }

    /**
     * Set up the message with no data payload
     *
     * @param command Adb command constant
     * @param argumentZero Argument zero
     * @param argumentOne Argument one
     */
    operator fun set(command: Int, argumentZero: Int, argumentOne: Int) {
        set(command, argumentZero, argumentOne, null as ByteArray?)
    }

    /**
     * Checksum for the provided data
     *
     * @param data Data to perform checksum for
     */
    private fun checksum(data: ByteArray): Int {
        var result = 0
        for (index in data.indices) {
            var element = data[index].toInt()
            if (element < 0) element += 256
            result += element
        }
        return result
    }

    /**
     * Extract a string from the header buffer
     *
     * @param offset Starting index on the buffer
     * @param length Length of the read
     * @return The read string
     */
    private fun readString(offset: Int = 0, length: Int = 4): String {
        headerBuffer.clear()
        return String(headerBuffer.array(), offset, length)
    }

    override fun toString(): String {
        val commandName = readString()
        var string = "Message: $commandName Arg0: $argumentZero Arg1: $argumentOne " +
                "DataLength: $dataLength"
        if (hasDataPayload()) string += " Data: $dataPayloadAsString"

        return string
    }

    companion object {

        /**
         * Generate a connect message
         */
        fun generateConnectMessage(): AdbMessage {
            val message = AdbMessage()
            message[A_CNXN, A_VERSION, MESSAGE_PAYLOAD] = "host::charlo"
            return message
        }

    }

}