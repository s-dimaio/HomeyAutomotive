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
-keep interface com.dimapp.android.homeyautomotive.api.HomeyApiService { *; }
-keep interface com.dimapp.android.homeyautomotive.api.HomeyCompanionApiService { *; }

# ── Gson / JSON models ────────────────────────────────────────────────────────
# Gson uses reflection to read/write field names on data classes.
# Without these rules R8 renames all fields and JSON deserialization silently fails.
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }

# Retain generic signatures of TypeToken and its subclasses.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Keep all DTO / data model classes in the api package.
-keep class com.dimapp.android.homeyautomotive.api.StartAuthRequest { *; }
-keep class com.dimapp.android.homeyautomotive.api.StartAuthResponse { *; }
-keep class com.dimapp.android.homeyautomotive.api.PollAuthResponse { *; }
-keep class com.dimapp.android.homeyautomotive.api.CompanionToken { *; }
-keep class com.dimapp.android.homeyautomotive.api.UserPayload { *; }
-keep class com.dimapp.android.homeyautomotive.api.HomeyPayload { *; }
-keep class com.dimapp.android.homeyautomotive.api.RefreshAuthRequest { *; }
-keep class com.dimapp.android.homeyautomotive.api.RefreshAuthResponse { *; }

# Keep all other model/data classes that may be serialized/deserialized.
-keep class com.dimapp.android.homeyautomotive.model.** { *; }
-keep class com.dimapp.android.homeyautomotive.api.models.** { *; }

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