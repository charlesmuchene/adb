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

#include <jni.h>
#include <string>
#include <auth.h>
#include <android/log.h>

#define LOG_TAG "adb"

#define LOG(severity, ...) \
	__android_log_print(severity, LOG_TAG, __VA_ARGS__)

#define E(...) LOG(ANDROID_LOG_ERROR, __VA_ARGS__)
#define D(...) LOG(ANDROID_LOG_DEBUG, __VA_ARGS__)
#define W(...) LOG(ANDROID_LOG_WARN, __VA_ARGS__)

extern "C" {
/**
 * Initialize adb. This invocation generates the RSA key pair if they don't already exist.
 *
 * @param path Path to the key
 */
void Java_com_charlesmuchene_adb_AdbApplication_initializeAdb(JNIEnv *, jobject, jstring path);

/**
 * Sign the provided token
 *
 * @param token Token to sign
 * @param path Path to the key
 * @return Signature buffer
 */
jbyteArray Java_com_charlesmuchene_adb_utilities_Adb_signToken(JNIEnv *, jobject, jbyteArray token,
                                                     jstring path);

/**
 * Retrieve the public key
 *
 * @param path Path to the key
 * @return Public key buffer
 */
jbyteArray Java_com_charlesmuchene_adb_utilities_Adb_getPublicKey(JNIEnv *, jobject, jstring path);

}