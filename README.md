# Tamil Subtitle AI (starter APK project)

A starter Android app for Vivo V21e 5G / Android 13. It uses Android MediaProjection to capture the visible screen, ML Kit Text Recognition to read visible subtitle text, and ML Kit Translation to translate English to Tamil. It displays the translation in a floating overlay.

## Important limitations
- This is an experimental starter, not a guaranteed universal OTT translator.
- Netflix, Prime Video, JioHotstar and other streaming apps may block screen capture using DRM / secure surfaces. In that case OCR sees a black/blank area and cannot read subtitles.
- This first version reads English text only. It does not yet detect Korean/French/Spanish automatically.
- It translates detected text fragments; timing, line selection and repeated subtitle cleanup need further tuning.
- Android screen capture requires the user to approve each session.
- Some Vivo battery/background restrictions may stop the foreground service. Set battery usage to unrestricted if needed.
- ML Kit downloads its English-to-Tamil translation model when first used; internet is required for model download.
- Do not use screen capture to bypass DRM or copy protected content.

## Build with GitHub Actions
1. Create a GitHub repository and upload the contents of this folder (not the outer folder).
2. Open the repository's **Actions** tab.
3. Select **Build Android APK** and press **Run workflow** (or push to main).
4. Open the completed workflow run, scroll to **Artifacts**, and download `Tamil-Subtitle-AI-debug`.
5. Extract the ZIP and install `app-debug.apk` on the Vivo phone. Allow installation from the browser/files app when Android asks.

## Permissions
The app needs:
- Display over other apps: floating Tamil subtitle box.
- Screen capture: user-approved MediaProjection session.
- Foreground service: keep the scanner alive while the OTT app is open.
- Internet: download ML Kit translation model.

## Package
`com.karthick.tamilsubtitleai`
