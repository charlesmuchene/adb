# Adb
This is an Android app, ADB host implementation, fully written in [Kotlin](kotlinlang.org) <3 (save for the native sources, for now). It uses [USB-Host Mode](https://developer.android.com/guide/topics/connectivity/usb/host.html) apis available since Android 3.1.

The app listens for `USB Device Attached` events, as registered on the manifest, to connect to the filtered Adb capable devices (see the intent filter xml). It also enumerates and attempts connection to already plugged devices during launch.

### Authentication
During adb protocol connection handshake, the Android device sends a token for the host to sign using public key encryption -- RSA. Android uses a custom format of the public key that is stored by device after a successful auth-dance for subsequent verification.

An RSA keypair is generated natively using a [build](https://github.com/google/boringssl/blob/master/BUILDING.md#building-for-android) of [BoringSSL](https://github.com/google/boringssl) for Android. The app uses an `armeabi-v7a` static boringssl crypto lib but you can also find a `arm64-v8a` in the boringssl folder under native sources. NB: Make sure to change the app's target abi in the module's build.gradle if you make the swap.

### Caveat
Max payload for an Adb packet is `65,536` bytes but the app only supports a max `16,384` bytes as limited by the native usb IO implementation.

See:
> drivers/usb/core/devio.c

### Contribution and releases
---
All development (both new features and bug fixes) is performed in develop branch. This way master sources always contain sources of the most recently released version. Please send PRs with bug fixes to develop branch

The develop `branch` is pushed to `master` during release.

### License

```
Copyright (C) 2018 Charles Muchene

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```