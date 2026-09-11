# ── Debug info ────────────────────────────────────────────────────────────────
# Keep line numbers in stack traces for easier crash debugging.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Retrofit ──────────────────────────────────────────────────────────────────
# Retrofit uses reflection to create interface implementations at runtime.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
# Keep all Retrofit service interfaces defined in the app.
-keep interface com.dimapp.android.homeyautomotive.api.** { *; }

# ── Gson / JSON models ────────────────────────────────────────────────────────
# Gson uses reflection to read/write field names on data classes.
# Without these rules R8 renames fields and JSON deserialization silently fails or crashes.
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }

# Retain generic signatures of TypeToken and its subclasses.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Keep all class members annotated with @SerializedName
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep all DTO, storage, and model packages in their entirety
-keep class com.dimapp.android.homeyautomotive.api.** { *; }
-keep class com.dimapp.android.homeyautomotive.api.models.** { *; }
-keep class com.dimapp.android.homeyautomotive.model.** { *; }
-keep class com.dimapp.android.homeyautomotive.repository.models.** { *; }
-keep class com.dimapp.android.homeyautomotive.storage.** { *; }

# ── OkHttp ────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keep,allowobfuscation,allowshrinking interface kotlin.coroutines.Continuation
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Kotlin Serialization / Reflection ─────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlin.reflect.jvm.internal.**

# ── Car App Library (CAL) ─────────────────────────────────────────────────────
-keep class androidx.car.app.** { *; }
-keep interface androidx.car.app.** { *; }
-keep class com.dimapp.android.homeyautomotive.HomeyCarAppService { *; }
-keep class com.dimapp.android.homeyautomotive.screens.** { *; }

# ── Notifications & CarAppExtender ────────────────────────────────────────────
-keep class androidx.core.app.NotificationCompat** { *; }
-keep class androidx.core.app.NotificationManagerCompat { *; }
-keep class androidx.car.app.notification.** { *; }

# ── WorkManager, Geofencing & Location ────────────────────────────────────────
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.google.android.gms.location.** { *; }
-keep class com.dimapp.android.homeyautomotive.geofence.** { *; }
-keep class * extends android.content.BroadcastReceiver { *; }