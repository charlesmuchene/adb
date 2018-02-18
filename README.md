# Adb
Android app as an adb host. This implementation uses [USB-Host Mode](https://developer.android.com/guide/topics/connectivity/usb/host.html) apis available since Android 3.1.

The app registers for `USB Device Attached` events on the manifest to connect to the filtered Adb capable devices (as defined in the intent filter xml). The app also enumerates already connected devices during launch and attempts to connect to them.

### Caveat
Max payload for an Adb packet is `65,536` bytes but the app only supports a max `16,384` bytes as limited by the native usb IO implementation.

See:
> drivers/usb/core/devio.c

### Contribution
Feel free to open issues or fork the project.

Happy bridging :D

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