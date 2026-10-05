# Proguard rules for remoteaudio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
