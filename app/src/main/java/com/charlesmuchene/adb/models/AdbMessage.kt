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

import com.charlesmuchene.adb.utilities.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adb message
 */
class AdbMessage {

    /**
     * Message header buffer
     */
    val header: ByteBuffer = ByteBuffer.allocate(MESSAGE_HEADER_LENGTH)
            .order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Data payload buffer
     */
    val payload: ByteBuffer = ByteBuffer.allocate(MESSAGE_PAYLOAD)
            .order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Command
     */
    val command: Int
        get() = header.getInt(0)

    /**
     * Argument zero
     */
    val argumentZero: Int
        get() = header.getInt(4)

    /**
     * Argument one
     */
    val argumentOne: Int
        get() = header.getInt(8)

    /**
     * Length of the data payload
     */
    val dataLength: Int
        get() = header.getInt(12)

    /**
     * Data payload as string
     */
    private val dataPayloadAsString: String?
        get() {
            return if (dataLength <= 0 || dataLength > MESSAGE_PAYLOAD) null
            else {
                String(payload.array(), 0, dataLength)
            }
        }

    constructor()

    /**
     * Constructor
     *
     * @param header [ByteBuffer] to construct the message with
     */
    constructor(header: ByteBuffer) {
        this.header.put(header)
    }

    /**
     * Add a payload to this message.
     *
     * @param payload [ByteBuffer] to add
     */
    fun addPayload(payload: ByteBuffer) {
        header.put(payload)
    }

    /**
     * Check if message has data payload
     *
     * @return `true` if this message has a data payload, `false` otherwise
     */
    fun hasPayload() = dataLength > 0

    /**
     * Get the message's payload
     *
     * @return [ByteArray] as the payload
     */
    fun getPayload(): ByteArray {
        return when {
            !hasPayload() -> ByteArray(0)
            dataLength == MESSAGE_PAYLOAD -> payload.array()
            else -> payload.array().copyOfRange(0, dataLength)
        }
    }

    /**
     * Get the message's total payload: header + payload
     *
     * @return [ByteArray] of the message's header and payload
     */
    fun asPayload(): ByteArray {
        return when {
            !hasPayload() -> header.array()
            else -> header.array() + payload.array()
        }
    }

    /**
     * Check if devices is online as reported back in this message
     *
     * @return `true` if device is online, `false` otherwise
     */
    fun isDeviceOnline(): Boolean {
        return if (dataPayloadAsString == null) false
        else dataPayloadAsString?.startsWith("device") ?: false
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
        with(header) {
            putInt(0, command)
            putInt(4, argumentZero)
            putInt(8, argumentOne)
            putInt(12, data?.size ?: 0)
            putInt(16, if (data == null) 0 else checksum(data))
            putInt(20, command.inv())
        }

        if (data != null) payload.put(data, 0, data.size)

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
     * Get the file stat read in this message
     *
     * @return [FileStat]
     */
    fun getFileStat(): FileStat? = if (hasPayload()) FileStat(payload) else null

    override fun toString(): String {
        val commandName = String(header.array(), 0, 4)
        var string = "Message: $commandName Arg0: $argumentZero Arg1: $argumentOne " +
                "DataLength: $dataLength"
        if (hasPayload()) string += " Data: $dataPayloadAsString"

        return string
    }

    companion object {

        /**
         * Generate a connect message
         *
         * @return [AdbMessage] instance
         */
        fun generateConnectMessage(): AdbMessage {
            val message = AdbMessage()
            message[A_CNXN, A_VERSION, MESSAGE_PAYLOAD] = "host::charlo"
            return message
        }

        /**
         * Generate an auth message
         *
         * @param type A public key or signature
         * @param payload Payload
         * @return [AdbMessage] instance
         */
        fun generateAuthMessage(type: Int, payload: ByteArray): AdbMessage {
            val message = AdbMessage()
            message[A_AUTH, type, 0] = payload
            return message
        }

        /**
         * Generate open message
         *
         * @param localId Local socket id
         * @param command Command to open socket for
         * @return [AdbMessage] instance
         */
        fun generateOpenMessage(localId: Int, command: String): AdbMessage {
            val message = AdbMessage()
            message[A_OPEN, localId, 0] = command
            return message
        }

        /**
         * Generate write message
         *
         * @param localId Local socket id
         * @param remoteId Remote (peer) socket id
         * @param data Payload
         * @return [AdbMessage] instance
         */
        fun generateWriteMessage(localId: Int, remoteId: Int, data: ByteBuffer): AdbMessage {
            val message = AdbMessage()
            message[A_WRTE, localId, remoteId] = data
            return message
        }

        /**
         * Generate okay message
         *
         * @param localId Local socket id
         * @param remoteId Remote (peer) socket id
         * @return [AdbMessage] instance
         */
        fun generateOkayMessage(localId: Int, remoteId: Int): AdbMessage {
            val message = AdbMessage()
            message[A_OKAY, localId] = remoteId
            return message
        }

        /**
         * Generate close message
         *
         * @param localId Local socket id
         * @param remoteId Remote (peer) socket id
         * @return [AdbMessage] instance
         */
        fun generateCloseMessage(localId: Int, remoteId: Int): AdbMessage {
            val message = AdbMessage()
            message[A_CLSE, localId] = remoteId
            return message
        }

        /**
         * Generate a quit message
         *
         * @param localId Local socket id
         * @param remoteId Remote (peer) socket id
         */
        fun generateQuitMessage(localId: Int, remoteId: Int): AdbMessage {
            val message = AdbMessage()
            message[A_QUIT, localId] = remoteId
            return message
        }

        /**
         * Generate a sync message
         *
         * @return [AdbMessage] instance
         */
        fun generateSyncMessage(): AdbMessage {
            val message = AdbMessage()
            message[A_SYNC, 0] = 0
            return message
        }

    }

}