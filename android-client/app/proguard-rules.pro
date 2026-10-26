# Add project specific ProGuard rules here.
-keep class com.yuddhasphere.map.MapBridge { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
