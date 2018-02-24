/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 * File is wildly modified from the original implementation to fit the intended application.
 *
 */

#include <adb.h>
#include <auth.h>
#include <string>
#include <openssl/nid.h>
#include <openssl/evp.h>
#include <openssl/bn.h>
#include <openssl/rsa.h>
#include <openssl/pem.h>
#include <sys/stat.h>
#include <crypto_utils/android_pubkey.h>
#include <fstream>
#include <iostream>
#include <memory>

/**
 * Write public key file to storage
 *
 * @param privateKey Private RSA key
 * @param keyPath Key path
 * @return `true` if the write was successful, `false` otherwise
 */
static bool writePublicKeyFile(RSA *privateKey, const std::string &keyPath) {

	uint8_t keyBuffer[ANDROID_PUBKEY_ENCODED_SIZE];
	if (!android_pubkey_encode(privateKey, keyBuffer, sizeof(keyBuffer))) {
		E("Failed to convert to custom adb public key");
		return false;
	}

	size_t expectedLength;
	if (!EVP_EncodedLength(&expectedLength, sizeof(keyBuffer))) {
		E("Public key too large to base64 encode");
		return false;
	}

	std::string content;
	content.resize(expectedLength);
	size_t actualLength = EVP_EncodeBlock(reinterpret_cast<uint8_t *>(&content[0]), keyBuffer,
	                                      sizeof(keyBuffer));
	content.resize(actualLength);

	content += " adb@charlo";

	std::string path(keyPath + ".pub");

	std::ofstream output(path.c_str());

	output << content;

	output.close();

	return true;
}

/**
 * Generate RSA key pair
 *
 * @param keyPath Key path
 * @return result of the key generation, 1 - success, 0 - failure
 */
static int generateKey(const std::string &keyPath) {

	mode_t oldMask;
	FILE *f = NULL;
	int ret = 0;

	EVP_PKEY *privateKey = EVP_PKEY_new();
	BIGNUM *exponent = BN_new();
	RSA *rsa = RSA_new();
	if (!privateKey || !exponent || !rsa) {
		E("Failed to allocate key");
		goto out;
	}

	BN_set_word(exponent, RSA_F4);
	RSA_generate_key_ex(rsa, 2048, exponent, NULL);
	EVP_PKEY_set1_RSA(privateKey, rsa);

	oldMask = umask(077);

	f = fopen(keyPath.c_str(), "w");
	if (!f) {
		E("Failed to open %s", keyPath.c_str());
		umask(oldMask);
		goto out;
	}

	umask(oldMask);

	if (!PEM_write_PrivateKey(f, privateKey, NULL, NULL, 0, NULL, NULL)) {
		E("Failed to write key");
		goto out;
	}

	if (!writePublicKeyFile(rsa, keyPath)) {
		E("Failed to write public key");
		goto out;
	}

	D("Success writing key pair to keyPath");

	ret = 1;

	out:
	if (f) fclose(f);
	EVP_PKEY_free(privateKey);
	RSA_free(rsa);
	BN_free(exponent);
	return ret;
}

/**
 * Sign the given token with key
 *
 * @param key Key to sign the token
 * @param token Token to be signed
 * @param tokenSize Size of the token
 * @param signature Signature buffer
 * @return The length of the signature
 */
static int adbAuthSign(RSA *key, const char *token, size_t tokenSize, char *signature) {
	if (tokenSize != TOKEN_SIZE) {
		E("Unexpected token size %zd", tokenSize);
		return 0;
	}

	unsigned int length;
	if (!RSA_sign(NID_sha1, reinterpret_cast<const uint8_t *>(token), tokenSize,
	              reinterpret_cast<uint8_t *>(signature), &length, key)) {
		return 0;
	}

	D("Successfully signed token: Signature length is %d", length);

	return (int) length;
}

/**
 * Read key from keyPath
 *
 * @param keyPath Key path
 * @param keyPointer Key buffer
 * @return `true` for success in reading key, `false` otherwise
 */
static bool readKeyFromFile(const std::string &keyPath, std::shared_ptr<RSA> &keyPointer) {

	std::unique_ptr<FILE, decltype(&fclose)> fp(fopen(keyPath.c_str(), "r"),
	                                            (int (&&)(FILE *)) fclose);
	if (!fp) {
		E("Failed to open '%s'", keyPath.c_str());
		return false;
	}

	RSA *key = RSA_new();
	if (!PEM_read_RSAPrivateKey(fp.get(), &key, nullptr, nullptr)) {
		E("Failed to read key");
		RSA_free(key);
		return false;
	}

	keyPointer = std::shared_ptr<RSA>(key, RSA_free);

	return true;
}

/**
 * Generate adb key pair
 *
 * @param keyPath Key path
 */
void generateAdbKey(std::string keyPath) {
	struct stat buf;
	if (stat(keyPath.c_str(), &buf) == -1)
		generateKey(keyPath);
	else
		W("Adb Key pair already exist");
}

/**
 * Sign the given token
 *
 * @param token Token to sign
 * @param length Length of the token
 * @param keyPath Path of the key
 * @return Signature
 */
std::vector<char> signToken(const char * token, size_t length, std::string keyPath) {

	std::vector<char> signature(MAX_PAYLOAD);
	std::shared_ptr<RSA> keyPointer;
	readKeyFromFile(keyPath, keyPointer);
	int len = adbAuthSign(keyPointer.get(), token, length, &signature[0]);
	signature.resize((unsigned int) len);
	return signature;
}

/**
 * Get public key
 *
 * @param keyPath Path of the key
 * @return Public key buffer
 */
std::vector<char> getPublicKey(std::string keyPath) {
	std::string content;
	std::ifstream input(keyPath);
	input >> content;
	input.close();
	content += '\0';
	D("Public key length: %d", content.size());
	std::vector<char> publicKey(content.begin(), content.end());
	return publicKey;
}