# Proguard rules for TV module
-keep class com.example.tv.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class androidx.media3.** { *; }
-keep class io.github.anilbeesetti.nextlib.** { *; }
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
