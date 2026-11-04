# wechat-qrcode-lite 0.1.0

Pinned local AAR from
https://github.com/Mercury000/wechat-qrcode-lite/releases/tag/v0.1.0.

- File: `wechat-qrcode-lite-0.1.0-arm64-v8a.aar`
- SHA-256: `5a1bbdb66aa57e74996e680731b0dd41c5f7fa095cefc8435c25a2a1d5a99887`
- Contains the ARM64 native library, four fixed models, Java/JNI bridge and
  upstream license notices.
- AboutLibraries metadata in `app/config/aboutlibraries/` adds the local AAR to
  the References page. Its entry links to the release's upstream attribution
  notice, and its license metadata links to the Apache 2.0 text. No extra license
  or notice files are copied into application assets.
- JNI libraries are compressed in the APK via `packaging.jniLibs.useLegacyPackaging`.
- Camera QR recognition exclusively uses this native decoder on the camera
  worker. The app reuses it and closes it on that worker when the scanner is
  disposed. Initialization failures show the manual device-entry prompt.
- APKs only include `arm64-v8a`, matching the pinned decoder AAR. ARM32, x86 and
  x86_64 builds are not supported.
- ZXing remains only for generating payment QR codes and barcodes.

The first library release is a prerelease. Device/camera recognition is being
evaluated in this app. The source and build scripts are available in the linked
repository; the AAR is retained locally so application builds need no extra
GitHub download.
