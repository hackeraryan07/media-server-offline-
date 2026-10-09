# Proguard / R8 optimization rules for Mobile Stream Server

-dontoptimize

# Keep database entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep class com.example.db.** { *; }

# Keep server models and local streaming classes
-keep class com.example.server.** { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# Media3 ExoPlayer
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }

# Glide
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class * implements com.bumptech.glide.module.GlideModule

# General Android attributes
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
