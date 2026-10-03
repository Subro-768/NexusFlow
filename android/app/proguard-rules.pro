# ProGuard / R8 rules for the release build.
#
# The release build enables minification, which the debug build never did. That
# is the point of shipping one: obfuscation shrinks the APK and removes unused
# code, but it also silently breaks anything that resolves a class or member by
# name at runtime. Everything kept here is something that *is* looked up by name.

# ── ZXing (QR encode + the embedded scanner's CaptureActivity) ───────────────
# The scanner is referenced from the manifest by its fully-qualified name and
# instantiated reflectively by the library, so neither the class nor its no-arg
# constructor may be renamed or removed.
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
-keepclassmembers class * extends android.app.Activity {
    <init>();
}
# Barcode format enums are read by name from CaptureActivity's extras.
-keepclassmembers enum com.google.zxing.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── OkHttp / Okio ───────────────────────────────────────────────────────────
# Optional platform integrations are looked up reflectively and only used when
# present, so R8 must not conclude they are unreachable.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepclassmembers class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ── Kotlin coroutines ───────────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**

# ── Keep our own entry points ───────────────────────────────────────────────
# Activities, services and receivers named in the manifest: R8 keeps the class
# but would otherwise strip members the platform calls only reflectively.
-keep class com.resumabletransfer.app.MainActivity { *; }
-keep class com.resumabletransfer.app.TransferForegroundService { *; }
-keep class com.resumabletransfer.app.ReceiveActionReceiver { *; }
-keep class com.resumabletransfer.app.server.EmbeddedTransferServer { *; }

# Enum valueOf/values are used when a status is restored from prefs.
-keepclassmembers enum com.resumabletransfer.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Diagnostics ─────────────────────────────────────────────────────────────
# Keep line numbers so a crash report from a release build is still readable,
# but hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile