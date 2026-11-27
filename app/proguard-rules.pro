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

# Keep Coil ImageLoaderFactory implementation on AwAnimeApp (checked via `is ImageLoaderFactory`)
-keep class coil.ImageLoaderFactory { *; }
-keep class com.carloj005.awanime.AwAnimeApp implements coil.ImageLoaderFactory {
    public coil.ImageLoader newImageLoader();
}

# Keep OkHttp / Okio optional platform classes cleanly
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 / ExoPlayer: keep reflectively instantiated HLS factory while allowing R8 to shrink unused modules
-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory {
    public <init>(...);
}
-dontwarn androidx.media3.**

# Room & WorkManager (R8 Full Mode in AGP 9 strips reflective Room *_Impl constructors and WorkerParameters)
-keep class * extends androidx.room.RoomDatabase {
    public <init>();
}
-keep class * extends androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
-keep class androidx.work.WorkerParameters { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.InputMerger {
    public <init>();
}
-keep class * implements androidx.startup.Initializer {
    public <init>();
}
-keep class * implements androidx.savedstate.SavedStateRegistry$AutoRecreated {
    public <init>();
}
-keep class com.carloj005.awanime.data.storage.DownloadWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.carloj005.awanime.data.notifications.FavoriteEpisodesCheckWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Strip verbose debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
