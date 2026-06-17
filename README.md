# Orbit

An Android app for calling the people you keep meaning to call.

Orbit organizes your contacts into mood and context-based lists — "inner orbit," "late night," "people who ground me" — and surfaces one person at a time with a simple yes-or-no decision. It removes the "who should I call right now?" friction, so reaching out happens more often.

## Status

Android only, built with Kotlin and Jetpack Compose. Minimum SDK 31 (Android 12); compile and target SDK 35.

## Privacy

Everything stays on your device. Orbit has no servers, no analytics, and no cloud sync. Your lists, notes, and call-history summaries are stored in an encrypted database (SQLCipher), with the key held in the Android Keystore.

## Build

```bash
cd android
./gradlew assembleDebug      # build a debug APK
./gradlew installDebug       # install to a connected device
./gradlew compileDebugKotlin # fast type-check
```

Requires JDK 17 and the Android SDK.

## License

Source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE). You're welcome to read, learn from, and modify the code for noncommercial purposes. Commercial use is reserved.
