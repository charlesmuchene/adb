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
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adb Socket
 */
class AdbSocket(val localId: Int, private val device: AdbDevice) {

    private var remoteId: Int = -1

    /**
     * Send open with the given command
     *
     * @param command command to send
     */
    private fun sendOpen(command: String) {
        device.queueAdbMessage(AdbMessage.generateOpenMessage(localId, command))
    }

    /**
     * Send okay message
     */
    private fun sendOkay() {
        device.queueAdbMessage(AdbMessage.generateOkayMessage(localId, remoteId))
    }

    /**
     * Send close message
     */
    private fun sendClose() {
        device.queueAdbMessage(AdbMessage.generateCloseMessage(localId, remoteId))
    }

    /**
     * Send buffer asynchronously
     *
     * @param buffer [ByteBuffer] to send
     */
    private fun send(buffer: ByteBuffer) {
        device.queueAdbMessage(AdbMessage.generateWriteMessage(localId, remoteId, buffer))
    }

    /**
     * Read adb message
     *
     * @return [AdbMessage] instance
     */
    private suspend fun read(): AdbMessage? = device.adbMessageProducer.receive()

    /**
     * Send file to device
     *
     * @param localPath Local absolute path of file to send
     * @param remotePath Remote path of the destination file
     */
    fun sendFile(localPath: String, remotePath: String) {
        launch {
            // TODO Make sending file robust using loop and reading writes from device
            val localFile = File(localPath)
            val localFilename = localFile.name
            val mode = 33188 // TODO Use local file permissions
            sendOpen("sync:")
            var responseMessage = read() ?: return@launch
            remoteId = responseMessage.argumentZero

            logd("Sending $localFilename")
            val statBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + remotePath.length)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(A_STAT)
                    .putInt(remotePath.length)
                    .put(remotePath)
            send(statBuffer)
            responseMessage = read() ?: return@launch

            val pathAndMode = "$remotePath/$localFilename,$mode"

            val pathAndModeLength = pathAndMode.length
            if (pathAndModeLength > MAX_PATH_LENGTH) {
                loge("The provided path is too long.")
                throw IllegalStateException("Destination path is too long")
            }

            val (lastModified, fileSize, stream) = openStream(localFile) ?: return@launch
            val smallPayloadSize = 3 * SYNC_REQUEST_SIZE + fileSize + pathAndModeLength
            if (smallPayloadSize <= MAX_BUFFER_LENGTH) {
                sendSmallFile(pathAndMode, lastModified, fileSize, smallPayloadSize, stream)
            } else {
                val successful = sendLargeFile(pathAndMode, lastModified, fileSize, stream)
                if (!successful) return@launch
            }

            responseMessage = read() ?: return@launch
            responseMessage = read() ?: return@launch

            logd("File sent!")
            sendClose()
            responseMessage = read() ?: return@launch
            logd("Stream closed")
        }.invokeOnCompletion {
            device.closeSocket(this@AdbSocket)
        }
    }

    /**
     * Send a large file
     *
     * @param pathAndMode The combination of the remote path and mode
     * @param lastModified Timestamp of the last modified time of the copied file
     * @param fileSize Size of the local file
     * @param stream [FileInputStream] of the local file
     * @return `true` if sending file was successful `false` otherwise
     */
    private suspend fun sendLargeFile(pathAndMode: String, lastModified: Int, fileSize: Int,
                                      stream: FileInputStream): Boolean {
        val pathAndModeLength = pathAndMode.length
        val sendBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + pathAndModeLength)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(A_SEND)
                .putInt(pathAndModeLength)
                .put(pathAndMode)
        send(sendBuffer)
        var responseMessage = read() ?: return false

        stream.use { file ->
            var bytesCopied = 0
            val dataArray = ByteArray(MAX_BUFFER_LENGTH - SYNC_REQUEST_SIZE)
            while (true) {
                val bytesRead = file.read(dataArray)
                if (bytesRead == -1) break
                val dataBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE + bytesRead)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(A_DATA)
                        .putInt(bytesRead)
                        .put(dataArray, 0, bytesRead)
                send(dataBuffer)
                bytesCopied += bytesRead
                responseMessage = read() ?: return false
            }
            val transferred = 100 * bytesCopied / fileSize
            logd("Transferred $transferred% of the file")
        }

        val doneBuffer = ByteBuffer.allocate(SYNC_REQUEST_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(A_DONE)
                .putInt(lastModified)
        send(doneBuffer)

        return true
    }

    /**
     * Send a small payload file. Adb implementation recommends to combine header and
     * payload in the send buffer for a small file as it is efficient.
     *
     * @param pathAndMode The combination of the remote path and mode
     * @param lastModified Timestamp of the last modified time of the copied file
     * @param fileSize Size of the local file
     * @param bufferSize Size of the payload buffer
     * @param stream [FileInputStream] of the local file
     */
    @Throws(IOException::class)
    private fun sendSmallFile(pathAndMode: String, lastModified: Int, fileSize: Int,
                              bufferSize: Int, stream: FileInputStream) {
        val data = ByteArray(fileSize)
        stream.use { it.read(data) }
        val pathAndModeLength = pathAndMode.length
        val dataBuffer = ByteBuffer.allocate(bufferSize)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(A_SEND)
                .putInt(pathAndModeLength)
                .put(pathAndMode)
                .putInt(A_DATA)
                .putInt(fileSize)
                .put(data)
                .putInt(A_DONE)
                .putInt(lastModified)
        send(dataBuffer)
    }
}