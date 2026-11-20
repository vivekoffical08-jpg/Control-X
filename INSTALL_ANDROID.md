# Install Control X on Android

## Why Play Protect can block the APK

Control X is currently distributed as an unsigned debug APK. It requests powerful Android capabilities:

- Microphone
- Overlay
- Accessibility
- Camera/torch
- Battery optimization exception

Google Play Protect can treat a first-time sideloaded APK with these capabilities as unsafe until the app is signed, submitted, and reviewed through Google Play.

The block does not indicate an APK build failure. The APK is built successfully by GitHub Actions.

## Safe developer testing

For the fastest test on your own device:

1. Download `app-debug.apk` from the latest release.
2. Open **Settings > Security & privacy**.
3. Allow installation from the app used to open the APK, such as Chrome or Files.
4. If Play Protect blocks the installation:
   - Open **Google Play Store**.
   - Tap the profile icon.
   - Open **Play Protect**.
   - Open its settings.
   - Temporarily turn off app scanning only for testing.
5. Install the APK again.
6. Re-enable Play Protect after testing.

Do not leave Play Protect disabled as a permanent solution. The recommended route for other users is Google Play testing or release.

## Alternative USB installation

With USB debugging enabled and a connected computer:

```powershell
adb install -r app-debug.apk
```

ADB installation can avoid a browser/file-manager unknown-app prompt, but Play Protect may still scan the package depending on device policy.

## Recommended production distribution

To remove this warning for normal users:

1. Create a Google Play Console developer account.
2. Create a signed Android App Bundle (`.aab`) instead of distributing the debug APK.
3. Enroll in Google Play App Signing.
4. Complete account, privacy, and accessibility declarations.
5. Submit Control X for Internal Testing, then Closed Testing.
6. Ask Google to review the accessibility use case and explain that the app only acts after explicit user action.
7. Increase testing to a production release after Play approval.

Debug APKs should only be used for controlled testing on devices you own.
