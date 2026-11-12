# The app uses Android framework JSON and Keystore; no reflection on application classes.
# UnionPay's JNI and SDK entry points must retain their vendor names.
-dontwarn com.unionpay.**
-keep class com.unionpay.** { *; }
-dontwarn com.huawei.**
-keep class com.huawei.** { *; }
-keep class com.umeng.** { *; }
-keep class org.repackage.** { *; }
-keep class com.uyumao.** { *; }
-keepclassmembers class * {
    public <init>(org.json.JSONObject);
}
