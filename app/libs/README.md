# wechat-qrcode-lite 0.1.0

Pinned local AAR from
https://github.com/Mercury000/wechat-qrcode-lite/releases/tag/v0.1.0.

- File: `wechat-qrcode-lite-0.1.0-arm64-v8a.aar`
- SHA-256: `5a1bbdb66aa57e74996e680731b0dd41c5f7fa095cefc8435c25a2a1d5a99887`
- Contains the ARM64 native library, four fixed models, Java/JNI bridge and
  upstream license notices.
- AboutLibraries metadata in `app/config/aboutlibraries/` adds the local AAR to
  the References page. Its entry links to the release's upstream attribution
  notice, and its license metadata includes the full Apache 2.0 text for offline
  builds. No extra license or notice files are copied into application assets.
- JNI libraries are compressed in the APK via `packaging.jniLibs.useLegacyPackaging`.
- Camera open/close, capture controls and frame copying run on the camera
  worker. Native QR recognition runs on a separate decoder worker, with one
  reusable grayscale buffer and at most one frame in flight. Decoder
  initialization starts after the first preview frame; disposal closes it
  on its own worker after pending recognition. Initialization failures show
  the manual device-entry prompt.
- APKs only include `arm64-v8a`, matching the pinned decoder AAR. ARM32, x86 and
  x86_64 builds are not supported.
- ZXing remains only for generating payment QR codes and barcodes.

The first library release is a prerelease. Device/camera recognition is being
evaluated in this app. The source and build scripts are available in the linked
repository; the AAR is retained locally so application builds need no extra
GitHub download.

## UnionPay SDK Mini 3.5.16

Pinned vendor binaries from the Android development package linked by the
[UnionPay SDK download page](https://open.unionpay.com/tjweb/acproduct/list?apiSvcId=450&index=5),
`云闪付APP支付开发包（安卓）3.5.16（20250911）.zip`.

- JAR: `unionpay-sdk-mini-3.5.16.jar` (SDK_TYPE=02, VERSION=3.5.16).
- JAR SHA-256: `b7ef8f882d8f1746bbdbfe9e4ed0ae2767af8f4f5205a61817e3fbb0445a0ab1`.
- Native library: `app/src/main/jniLibs/arm64-v8a/libentryexpro.so`.
- Native SHA-256: `de3223daa80a4b61c121071ca37141ccf82854ec2e945c7142ac7d61e0a5436e`.
- Vendor-owned SDK; no open-source license is asserted for these binaries.
- Uses `startPay(activity, null, null, tn, "00")` for ordinary laundry channel 52.
  Merchant signing remains on the backend. SDK results trigger backend order
  queries and do not themselves confirm payment.
