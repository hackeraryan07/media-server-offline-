# Proguard rules for TV module
-dontoptimize
-keep class com.example.tv.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
-keep class org.videolan.libvlc.util.** { *; }
-keep class org.videolan.R$* { *; }
-keepclassmembers class org.videolan.** { *; }
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
