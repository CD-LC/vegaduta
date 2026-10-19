# Store checklist - My App

Package / bundle id: `app.builder.generated`  |  Version: `0.0.1`

Publishing is a human step: you upload the build and submit the listing in
Google Play Console / App Store Connect yourself. Nothing in this repo has
your store credentials.

## 1. Identity
- [ ] Package id `app.builder.generated` is final (it can never change after first publish)
- [ ] App name and descriptions reviewed: `fastlane/metadata/`

## 2. Icons and splash
- [ ] Replace `assets/icon.png` with your real 1024x1024 PNG (no transparency
      for iOS). CI derives every Android/iOS icon size from this one file
      with `@capacitor/assets` (`npm run assets`).
- [ ] Play listing icon 512x512 and feature graphic 1024x500 uploaded in the console

## 3. Screenshots
- [ ] Android: at least 2 phone screenshots (Play accepts 320-3840 px sides)
- [ ] iOS: screenshots for the required device sizes (6.9-inch iPhone at minimum)

## 4. Android release build (signed AAB)
Add these GitHub repository secrets (Settings > Secrets and variables > Actions).
Without them the `android-release` job skips itself with a notice - the rest of
CI still passes.
- [ ] `ANDROID_KEYSTORE_BASE64` - `base64 -w0 upload-keystore.jks`
- [ ] `ANDROID_KEYSTORE_PASSWORD`
- [ ] `ANDROID_KEY_ALIAS`
- [ ] `ANDROID_KEY_PASSWORD`
- [ ] Enrol in Play App Signing so this is only the upload key
- [ ] Keep the keystore backed up outside GitHub; losing it blocks updates
- [ ] Target API 36 and 16 KB page-size settings are applied by the job
      (`scripts/android-release-config.mjs`); confirm in Play Console pre-launch report

## 5. Play Console declarations
- [ ] Data safety form matches `PRIVACY-POLICY.md` and what the app really does
- [ ] Privacy policy URL set (host `PRIVACY-POLICY.md`)
- [ ] Content rating questionnaire, target audience, ads declaration
- [ ] Accessibility: do NOT request the AccessibilityService API unless the app
      is a genuine accessibility tool; Play rejects other uses. This template
      does not need it.

## 6. iOS
- [ ] Apple Developer Program membership and an App Store Connect app record
- [ ] Signing certificates and provisioning profiles (the CI iOS job is an
      unsigned simulator build only)
- [ ] App privacy "nutrition label" and privacy policy URL
- [ ] `fastlane/metadata/ios/en-US/` reviewed (keywords, subtitle, notes)
