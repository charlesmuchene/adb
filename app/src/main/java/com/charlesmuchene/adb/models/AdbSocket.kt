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
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withTimeout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adb Socket
 */
class AdbSocket(val localId: Int, private val device: AdbDevice) {

    private val mode = 33188 // 0644
    /*private */var remoteId: Int = -1


    /**
     * Set the peer (remote) adb socket id
     */
//    fun setRemoteId(remoteId: Int) {
//        this.remoteId = remoteId
//    }

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
     * @return `true` if message queueing was successful, `false` otherwise
     */
    /*private */fun sendWriteMessage(subCommand: Int, data: ByteArray, length: Int = data.size)
            : Boolean {
        val buffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + length)
                .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(subCommand).putInt(length).put(data)
        val message = AdbMessage.generateWriteMessage(localId, remoteId, buffer.array())
        return write(message)
    }

    /**
     * Write data to device
     *
     * @param message Payload to send
     * @return `true` if success in queueing message, `false` otherwise
     */
    /*private */fun write(message: AdbMessage): Boolean {
        // TODO Still check for smaller payloads
        var request = device.outRequest
        request.clientData = this
        if (request.queue(message.header, MESSAGE_HEADER_LENGTH)) {
            if (message.hasPayload()) {
                request = device.outRequest
                request.clientData = this

                return if (request.queue(message.payload, message.dataLength)) {
                    true
                } else {
                    device.releaseOutRequest(request)
                    false
                }
            }
            return true
        } else {
            device.releaseOutRequest(request)
            return false
        }

//        transfer(message.header.array())
//        if (message.hasPayload()) sendPayload(message.getPayload())
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

    /**
     * Execute a block asynchronously
     *
     * @param block Block to execute
     */
    private fun asyncExecute(block: suspend () -> Unit) {
        launch {
            withTimeout(ADB_REQUEST_TIMEOUT) {
                //                synchronized(device) {
                block()
//                }
            }
        }
    }

    /**
     * Send file to device
     *
     * @param localPath Local absolute path of file to send
     * @param remotePath Remote path of the destination file
     *
     */
    fun sendFile(localPath: String, remotePath: String) {
        launch {
            val localFile = File(localPath)
            val localFilename = localFile.name
            val mode = 33188 // TODO Use local file permissions

            val openMessage = AdbMessage.generateOpenMessage(localId, "sync:")
            device.queueAdbMessage(openMessage)
            var responseMessage = device.adbMessageProducer.receive() ?: return@launch
            remoteId = responseMessage.argumentZero

            logd("Sending $localFilename")
            var statBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + remotePath.length).order(ByteOrder.LITTLE_ENDIAN)
            statBuffer.putInt(A_STAT).putInt(remotePath.length).put(remotePath)
            var statMessage = AdbMessage.generateWriteMessage(localId, remoteId, statBuffer.array())
            device.queueAdbMessage(statMessage)
            responseMessage = device.adbMessageProducer.receive() ?: return@launch
            statBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + remotePath.length + 1).order(ByteOrder.LITTLE_ENDIAN)
            statBuffer.putInt(A_STAT).putInt(remotePath.length + 1).put("$remotePath/")
            statMessage = AdbMessage.generateWriteMessage(localId, remoteId, statBuffer.array())
            device.queueAdbMessage(statMessage)
            responseMessage = device.adbMessageProducer.receive() ?: return@launch

            val pathAndMode = "$remotePath/$localFilename,$mode"

            val pathAndModeLength = pathAndMode.length
            if (pathAndModeLength > MAX_PATH_LENGTH) {
                loge("The provided path is too long.")
                throw IllegalStateException("Destination path is too long")
            }
            val sendBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + pathAndModeLength)
                    .order(ByteOrder.LITTLE_ENDIAN)
            sendBuffer.putInt(A_SEND).putInt(pathAndModeLength).put(pathAndMode)
            val sendMessage = AdbMessage.generateWriteMessage(localId, remoteId, sendBuffer.array())
            device.queueAdbMessage(sendMessage)
            responseMessage = device.adbMessageProducer.receive() ?: return@launch

            val (lastModified, fileSize, stream) = openStream(localFile) ?: return@launch

            stream.use { file ->
                var bytesCopied = 0
                val dataArray = ByteArray(MAX_BUFFER_LENGTH - SYNC_REQUEST_SIZE)
                while (true) {
                    val bytesRead = file.read(dataArray)
                    if (bytesRead == -1) break
                    val dataBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + bytesRead).order(ByteOrder.LITTLE_ENDIAN)
                    dataBuffer.putInt(A_DATA).putInt(bytesRead).put(dataArray, 0, bytesRead)
                    val dataMessage = AdbMessage.generateWriteMessage(localId, remoteId, dataBuffer.array())
                    device.queueAdbMessage(dataMessage)
                    bytesCopied += bytesRead
                    responseMessage = device.adbMessageProducer.receive() ?: return@launch
                }
                val transferred = 100 * bytesCopied / fileSize
                logd("Transferred $transferred% of $localFilename to $remotePath")
            }

            val doneBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            doneBuffer.putInt(A_DONE).putInt(lastModified)
            val doneMessage = AdbMessage.generateWriteMessage(localId, remoteId, doneBuffer.array())
            device.queueAdbMessage(doneMessage)
            responseMessage = device.adbMessageProducer.receive() ?: return@launch
            responseMessage = device.adbMessageProducer.receive() ?: return@launch

            logd("File sent!")
            device.queueAdbMessage(AdbMessage.generateCloseMessage(localId, remoteId))
            responseMessage = device.adbMessageProducer.receive() ?: return@launch
            device.closeSocket(this@AdbSocket)
            logd("Stream closed")
        }
    }
}