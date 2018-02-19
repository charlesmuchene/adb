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

#include "include/adb.h"
#include <auth.h>

void Java_com_charlesmuchene_adb_utilities_Adb_initializeAdb(JNIEnv *env, jobject, jstring path) {

	D("Initializing adb...");
	const char *keyPath = (std::string(env->GetStringUTFChars(path, 0)) + "/adbkey").c_str();
	generateAdbKey(keyPath);
	env->ReleaseStringUTFChars(path, keyPath);

}

jbyteArray Java_com_charlesmuchene_adb_utilities_Adb_signToken(JNIEnv *env, jobject,
                                                               jbyteArray token,
                                                               jstring path) {
	const char *keyPath = (std::string(env->GetStringUTFChars(path, 0)) + "/adbkey").c_str();
	int length = env->GetArrayLength(token);
	char tokenBuffer[length];
	env->GetByteArrayRegion(token, 0, length, reinterpret_cast<jbyte *>(tokenBuffer));
	std::vector<char> signature = signToken(tokenBuffer, (size_t) length, keyPath);
	env->ReleaseStringUTFChars(path, keyPath);
	int len = signature.size();
	jbyteArray array = env->NewByteArray(len);
	env->SetByteArrayRegion(array, 0, len, (const jbyte *) &signature[0]);
	return array;
}

jbyteArray Java_com_charlesmuchene_adb_utilities_Adb_getPublicKey(JNIEnv *env, jobject,
                                                                  jstring path) {
	const char *pathUTFChars = env->GetStringUTFChars(path, 0);
	auto constructedPath = std::string(pathUTFChars) + "/adbkey.pub";
	const char *keyPath = constructedPath.c_str();
	std::vector<char> publicKey = getPublicKey(keyPath);
	env->ReleaseStringUTFChars(path, pathUTFChars);
	int len = publicKey.size();
	jbyteArray array = env->NewByteArray(len);
	env->SetByteArrayRegion(array, 0, len, (const jbyte *) &publicKey[0]);
	return array;
}