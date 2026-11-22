# ==============================================================================
# AW Anime - R8 / ProGuard Rules (Release Minification & Hardening)
# ==============================================================================

# Keep kotlinx.serialization generated serializers & companion objects
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.carloj005.awanime.data.model.**$$serializer { *; }
-keepclassmembers class com.carloj005.awanime.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.carloj005.awanime.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers enum com.carloj005.awanime.data.model.** { *; }

# Keep OkHttp / Okio optional platform classes cleanly
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Strip verbose debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
