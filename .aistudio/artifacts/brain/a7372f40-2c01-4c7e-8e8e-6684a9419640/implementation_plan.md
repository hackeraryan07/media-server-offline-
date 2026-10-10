# Video Player Selection (Internal vs. External) in TV Client

Allows users of the Android TV client to choose whether to play videos using the built-in internal player (LibVLC) or hand off playback to an external video player (such as external VLC, MX Player, Just Player, Kodi, or Nova Video Player) via the Android system chooser.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions were confirmed during the interactive interview:
> - **Player Choice Location**: Configured via a default preference in App Settings with an optional prompt/ask-every-time mode.
> - **External Player Dispatch**: Launches via the standard Android `Intent.createChooser` with `ACTION_VIEW` and `video/*` MIME type, giving the user full access to all installed media players on their Android TV.
> - **In-Player Quick Switch**: In addition to settings, the internal player's Settings menu will include a quick "Open in External Player" option for seamless handoff.

---

## 1. Overview & Core Concept

### What It Does
- Enables users to configure their preferred playback engine: **Internal Player (LibVLC)**, **External Player**, or **Always Ask**.
- When set to **External Player** or when **Always Ask** is chosen, videos are dispatched through an Android Intent with `ACTION_VIEW`, `video/*` MIME type, and streaming URL, allowing any installed Android TV media player to handle the stream.
- When playing inside the Internal Player, users can also trigger an "Open in External Player" action directly from the in-player controls menu.

### Target Audience
Android TV / Google TV users streaming media from the mobile server who either prefer the streamlined built-in LibVLC player with custom TV remote controls or prefer utilizing specialized external players (e.g., hardware-tuned decoders, custom subtitle renderers, or preferred UI).

---

## 2. User Experience & Visual Design

### Key User Flows

```
                    ┌────────────────────────┐
                    │     TV Browse /        │
                    │    Search Fragment     │
                    └───────────┬────────────┘
                                │
                        User clicks video
                                │
                    ┌───────────▼────────────┐
                    │ Check 'player_choice'  │
                    │       preference       │
                    └───────────┬────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        │ "internal"            │ "ask"                 │ "external"
        ▼                       ▼                       ▼
┌───────────────┐       ┌───────────────┐       ┌───────────────┐
│ PlayerActivity│       │ Choice Dialog │       │ System Intent │
│   (LibVLC)    │       │ (Internal vs. │       │    Chooser    │
└───────┬───────┘       │   External)   │       └───────────────┘
        │               └───────┬───────┘
  In-Player Menu                │
  "Open in External"            ├── Internal ──► PlayerActivity
        │                       └── External ──► Intent Chooser
        ▼
Intent Chooser
```

### Visual Identity & Theme
- **Settings Screen (`activity_settings.xml`)**:
  - Add a dedicated **"Default Video Player"** section styled to match the Leanback/TV palette (`#FFFFFF` text, transparent focus ripples, `20sp` headers, and `18sp` radio buttons).
  - Radio options:
    1. **Internal Player (LibVLC)** (Default)
    2. **External Player**
    3. **Always Ask**
- **Player Selection Dialog (`dialog_player_choice.xml`)**:
  - A clean, dark TV modal styled with rounded corners and high-contrast D-pad focus states.
  - Buttons: **Internal (LibVLC)**, **External Player**, with an optional checkbox/toggle to "Don't ask again" or set as default.
- **In-Player Menu**:
  - Add **"Open in External Player"** to the `btnSettings` popup menu in `PlayerActivity`.

---

## 3. Key Product Decisions & Trade-Offs

- **Standard Android Intent Chooser for External Playback**:
  - *Chosen Approach*: `Intent.createChooser(intent, "Open with")` with `FLAG_ACTIVITY_NEW_TASK` and `FLAG_GRANT_READ_URI_PERMISSION`.
  - *Why*: Compatible with all Android TV media players (VLC, MX Player, Nova, Just Player, Kodi) without hardcoding package names. If no external player is installed, gracefully notifies the user via Toast and falls back to internal playback.
- **Seamless In-Player Handoff**:
  - *Chosen Approach*: Expose "Open in External Player" in `PlayerActivity` settings menu.
  - *Why*: If a user starts a video in the internal player and discovers a specific audio or video track issue, they can immediately transfer playback to an external player without navigating back to browse or settings.

---

## 4. Technical Architecture & Component Mapping

```
┌────────────────────────────────────────────────────────┐
│                      Preferences                       │
│        SharedPreferences("app_settings")               │
│        Key: "default_player" -> "internal" |           │
│                                 "external" | "ask"     │
└───────────────────────────┬────────────────────────────┘
                            │
            ┌───────────────┴───────────────┐
            ▼                               ▼
┌────────────────────────┐      ┌────────────────────────┐
│   TvBrowseFragment     │      │    TvSearchFragment    │
│  (Click dispatcher)    │      │  (Click dispatcher)    │
└───────────┬────────────┘      └───────────┬────────────┘
            │                               │
            └───────────────┬───────────────┘
                            ▼
               ┌────────────────────────┐
               │    PlayerDispatcher    │
               │  - launchInternal()    │
               │  - launchExternal()    │
               │  - showChoiceDialog()  │
               └────────────┬───────────┘
                            │
            ┌───────────────┴───────────────┐
            ▼                               ▼
┌────────────────────────┐      ┌────────────────────────┐
│     PlayerActivity     │      │     External Player    │
│  (LibVLC Media Player) │      │  (Intent.ACTION_VIEW)  │
└────────────────────────┘      └────────────────────────┘
```

### Components to Update / Create:

1. **`tv/src/main/res/layout/activity_settings.xml`**:
   - Add "Default Video Player" header and RadioGroup (`radio_player_internal`, `radio_player_external`, `radio_player_ask`).
2. **`tv/src/main/java/com/example/tv/TvSettingsActivity.kt`**:
   - Load and persist the `default_player` preference (`"internal"`, `"external"`, or `"ask"`).
3. **`tv/src/main/java/com/example/tv/TvPlayerLauncher.kt` (New Helper)**:
   - Encapsulates playback launching logic:
     - Checks preference.
     - Launches `PlayerActivity` with playlist & video extras.
     - Creates external player Intent with MIME type resolution and starts chooser with error handling.
     - Displays TV-styled choice dialog if preference is set to `"ask"`.
4. **`tv/src/main/java/com/example/tv/TvBrowseFragment.kt` & `TvSearchFragment.kt`**:
   - Route item clicks through `TvPlayerLauncher`.
5. **`tv/src/main/java/com/example/tv/PlayerActivity.kt`**:
   - Add "Open in External Player" option to in-player settings dialog.
