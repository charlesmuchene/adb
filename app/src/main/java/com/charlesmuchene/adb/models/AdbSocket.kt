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
 * Adb Socket
 */
class AdbSocket(val localId: Int, private val device: AdbDevice) {

    private val mode = 33188 // 0644
    private var remoteId: Int = -1

    /**
     * Open socket for stream
     *
     * @param command Command to open stream for
     */
    fun openSocket(command: String) {
        val message = AdbMessage.generateOpenMessage(localId, command)
        write(message)
    }

    /**
     * Send file to device
     *
     * @param remotePath Remote path of the destination file
     * @param localPath Local absolute path of file to send
     */
    private fun sendFile(remotePath: String, localPath: String) {

        sendWriteMessage(A_STAT, remotePath.toByteArray())

        // TODO Read -- Expect OKAY

        // TODO If directory, stat again with '/' else quit
        sendWriteMessage(A_STAT, "$remotePath/".toByteArray())

        // TODO Read -- Expect OKAY

        val pathAndMode = "$remotePath,$mode"
        val pathAndModeLength = pathAndMode.length
        if (pathAndModeLength > MAX_PATH_LENGTH) throw IllegalStateException("Path too long")

        sendWriteMessage(A_SEND, pathAndMode.toByteArray(), pathAndModeLength)

        val array = ByteArray(MESSAGE_PAYLOAD - SYNC_REQUEST_SIZE)
        val (lastModified, _, stream) = openStream(localPath)
        var bytesCopied = 0

        while (true) {
            val bytesRead = stream.read(array, 0, array.size)
            if (bytesRead == -1) break
            sendWriteMessage(A_DATA, array.copyOfRange(0, bytesRead), bytesRead)
            bytesCopied += bytesRead
            // TODO Report progress using file length
        }

        stream.close()
        sendSubCommand(A_DONE, lastModified)

        // TODO Read -- Expect OKAY

        sendSubCommand(A_QUIT)

        // TODO Read -- Expect OKAY
    }

    /**
     * Send sub command with payload
     *
     * @param subCommand Sub-command to send
     * @param data Data as a int; default is -4 for no - data
     */
    private fun sendSubCommand(subCommand: Int, data: Int = -4) {
        val buffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + data)
                .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(subCommand)
        if (data != -4) buffer.putInt(data)
        val message = AdbMessage.generateWriteMessage(localId, remoteId, buffer.array())
        write(message)
    }

    /**
     * Send write message
     *
     * @param subCommand Command to send
     * @param data Path to stat
     * @param length Length of the data
     */
    private fun sendWriteMessage(subCommand: Int, data: ByteArray, length: Int = data.size) {
        val buffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + length)
                .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(subCommand).putInt(length).put(data)
        val message = AdbMessage.generateWriteMessage(localId, remoteId, buffer.array())
        write(message)
    }

    /**
     * Write data to device
     *
     * @param message Payload to send
     *
     * TODO Perform in aux thread
     */
    private fun write(message: AdbMessage) {
        // TODO Still check for smaller payloads
        transfer(message.header.array())
        if (message.hasPayload()) sendPayload(message.getPayload())
    }

    /**
     * Split and send payload
     *
     * @param data Payload to send
     *
     * TODO Perform in aux thread
     */
    private fun sendPayload(data: ByteArray) {
        val payload = ByteArray(MAX_BUFFER_LENGTH)
        val size = data.size
        val chunks = (size / MAX_BUFFER_LENGTH) + if (size % MAX_BUFFER_LENGTH != 0) 1 else 0
        val stream = data.inputStream()

        for (chunk in 0 until chunks) {
            val length = stream.read(payload)
            if (length != -1)
                transfer(payload, length)
        }
    }

    /**
     * Transfer data to device
     *
     * @param data Data buffer
     * @param length The size of data to send
     *
     * TODO Perform in aux thread
     */
    private fun transfer(data: ByteArray, length: Int = data.size) {
        val transferredBytes = device.connection.bulkTransfer(device.outEndpoint,
                data, length, 10)
        logd("Transferred ${(transferredBytes / length) * 100}% of payload")
    }

    /**
     * Read message payload from device
     *
     * TODO Perform in aux thread
     */
    fun read(length: Int = MESSAGE_HEADER_LENGTH): AdbMessage? {
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var dataRead = 0
        do {
            val receivedBytes = device.connection.bulkTransfer(device.inEndpoint,
                    buffer.array(), dataRead, length - dataRead, 10)
            if (receivedBytes == -1) break
            dataRead += receivedBytes
        } while (dataRead < length)

        // Do we need to flip?

        if (dataRead < length) return null

        val message = AdbMessage(buffer) // TODO Is message valid

        if (!message.hasPayload()) return message

        val payloadLength = message.dataLength
        val payload = ByteBuffer.allocate(payloadLength).order(ByteOrder.LITTLE_ENDIAN)
        dataRead = 0

        do {
            val receivedBytes = device.connection.bulkTransfer(device.inEndpoint,
                    payload.array(), dataRead, payloadLength - dataRead, 10)
            if (receivedBytes == -1) break
            dataRead += receivedBytes
        } while (dataRead < payloadLength)

        message.addPayload(payload)

        return message
    }

    /**
     * Send okay message
     * TODO Read okay message
     */
    fun sendOkay() {
        val message = AdbMessage.generateOkayMessage(localId, remoteId)
        write(message)
    }

    /**
     * Send close message
     */
    fun sendClose() {
        val message = AdbMessage.generateCloseMessage(localId, remoteId)
        write(message)
    }

    /**
     * Handle the given message based on the protocol
     *
     * @param message [AdbMessage] instance to process
     */
    fun dispatchMessage(message: AdbMessage) {
        when (message.command) {
            A_OKAY -> logd(message.toString())
            A_WRTE -> logd(message.toString())
            else -> logw("Could not handle $message command")
        }
    }
}