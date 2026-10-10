# Proguard rules for TV module
-dontoptimize
-keep class com.example.tv.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class androidx.media3.** { *; }
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
