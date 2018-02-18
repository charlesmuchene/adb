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

#include <adb.h>
#include <vector>

#define TOKEN_SIZE 20
constexpr size_t MAX_PAYLOAD = 1024 * 1024;

/**
 * Generate adb key pair
 *
 * @param keyPath Key path
 */
void generateAdbKey(std::string keyPath);

/**
 * Sign the given token
 *
 * @param token Token to sign
 * @param length Length of the token
 * @param keyPath Path of the key
 * @return Signature pointer
 */
std::vector<char> signToken(const char * token, size_t length, std::string keyPath);

/**
 * Get public key
 *
 * @param keyPath Path of the key
 * @return Public key buffer
 */
std::vector<char> getPublicKey(std::string keyPath);