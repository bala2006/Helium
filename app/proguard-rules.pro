# Helium release shrinking rules.

# --- kotlinx.serialization ---------------------------------------------------------
# Keep generated serializers for the domain model and AI schemas.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.sekhar.helium.**$$serializer { *; }
-keepclassmembers class com.sekhar.helium.** {
    *** Companion;
}
-keepclasseswithmembers class com.sekhar.helium.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Room --------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Media3 ------------------------------------------------------------------------
-dontwarn androidx.media3.**

# --- OkHttp / Ktor -----------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
