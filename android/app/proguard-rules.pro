# MetaRayBan Glasses App ProGuard Rules

# Keep data classes for serialization
-keep class com.metarayban.glasses.data.model.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil
-dontwarn coil.**
