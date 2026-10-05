# Remote Audio size-optimized Proguard rules
-dontobfuscate
-dontwarn java.lang.invoke.**
-keep class com.example.remoteaudio.AudioSyncService { *; }
-keep class com.example.remoteaudio.MainActivity { *; }
