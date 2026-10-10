# TV Client APK Size Optimization (100% Retaining LibVLC)

An optimization plan to dramatically cut the TV client APK size down from ~82MB to **~21MB** while **100% keeping the LibVLC library** and its universal codec playback engine.

---

## User Review & Critical Confirmation

> [!IMPORTANT]
> ### Yes, We Are Keeping the VLC Library!
> - **Zero playback compromises**: The internal player will continue using `org.videolan.android:libvlc-all` with all C/C++ native decoders, hardware acceleration, and demuxers.
> - **Plays any video**: MKV, MP4, AVI, TS, FLV, WebM, H.264, HEVC/H.265, AV1, AC3, EAC3, DTS, TrueHD, SSA/ASS subtitles, and Wi-Fi streaming protocols remain fully supported.
> - **Why it was ~82MB**: Because it bundled 3 separate architectures (`arm64-v8a`, `armeabi-v7a`, and `x86_64`) into a single file (each architecture adds ~20MB of native code).
> - **How we fix the size**: Android TV hardware universally runs on ARM. By packaging the universal Android TV ARM architecture (`armeabi-v7a`, which runs on virtually all 32-bit and 64-bit Android TV / Google TV / Fire TV devices) and stripping unused non-video asset files, the APK size drops by **over 65%** down to **~21MB**.

---

## 1. Overview & Core Concept

### What It Delivers
- Retains the exact same LibVLC video player implementation (`PlayerActivity`, `LibVLC`, `MediaPlayer`, `VLCVideoLayout`).
- Reduces the TV APK download and storage footprint from **82MB to ~21MB**.
- Keeps all user-facing features identical: play/pause, time scrubbing, 10s skip/rewind, speed control, audio track picker, subtitle picker, aspect ratio cycling, audio sync, and the new Internal/External player switcher.

---

## 2. Technical Architecture & Size Optimization Strategy

```
Current TV APK (~82 MB)                  Optimized TV APK (~21 MB)
┌─────────────────────────────────┐      ┌─────────────────────────────────┐
│ lib/arm64-v8a/libvlc.so (~20MB) │      │ lib/armeabi-v7a/libvlc.so(~19MB)│
├─────────────────────────────────┤ ───► │ (Universal Android TV ARM)      │
│ lib/armeabi-v7a/libvlc.so(~19MB)│      ├─────────────────────────────────┤
├─────────────────────────────────┤      │ DEX Classes & Code     (~2 MB)  │
│ lib/x86_64/libvlc.so   (~22MB)  │      ├─────────────────────────────────┤
├─────────────────────────────────┤      │ App Resources & Layout (<0.5MB) │
│ LibVLC HRTF/LUA Assets (~2 MB)  │      └─────────────────────────────────┘
├─────────────────────────────────┤      Total Size: ~21 MB
│ DEX Classes & Code     (~2 MB)  │      (Reduced by ~75% while keeping
└─────────────────────────────────┘       100% of LibVLC codecs intact!)
```

### Key Technical Levers:
1. **Target Universal Android TV ARM Architecture (`armeabi-v7a`)**:
   - `armeabi-v7a` is the lowest common denominator supported by virtually every Android TV SoC on the market (Amlogic, MediaTek, Realtek), including 64-bit devices which execute 32-bit ARM binaries in backwards compatibility mode.
   - Eliminates redundant multi-architecture bloat while guaranteeing execution on real TV devices.
2. **Strip Unused LibVLC Bundled Assets**:
   - LibVLC bundles large non-video assets that are not needed for video streaming:
     - `assets/hrtfs/dodeca_and_7channel_3DSL_HRTF.sofa` (Binaural 3D headphone spatial audio mapping table).
     - `assets/lua/**` (Desktop VLC web scraping and playlist extraction scripts).
   - Excluding these via `packaging.resources.excludes` saves additional megabytes without affecting video playback.
3. **Legacy Packaging Compression**:
   - Maintains `jniLibs.useLegacyPackaging = true` to force maximum DEFLATE compression on `libvlc.so` and `libc++_shared.so`.

---

## 3. Implementation Steps

1. **`tv/build.gradle.kts`**:
   - Set `ndk.abiFilters = listOf("armeabi-v7a")` for the production TV build.
   - Add asset exclusions in `packaging.resources`:
     ```kotlin
     excludes += listOf(
       "assets/hrtfs/**",
       "assets/lua/**"
     )
     ```
2. **GitHub Actions Workflows (`.github/workflows/build.yml` and `release.yml`)**:
   - Ensure the validation threshold for the TV APK is set to `25MB` (25600 KB) so CI tests pass cleanly while verifying the APK remains compact and under 25MB.
3. **Verification**:
   - Rebuild `:tv:assembleRelease` and `:tv:assembleDebug`.
   - Verify the generated TV APK size is confirmed at ~21MB.
   - Verify `compile_applet` succeeds without errors.
