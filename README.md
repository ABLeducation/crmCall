# ABL Calls

Android app that tracks calls on a salesperson's SIM and syncs them to the ABL portal (Sales → Calls).

- Uploads the phone's call log (outgoing, incoming, missed, rejected) to `/api/sales/call-sync/upload/`.
- Shows a pop-up after each call to log the outcome, notes and next step against the school.
- Uploads recordings saved by the phone's own dialer, for calls matched to a school only.

Sign in with the portal Employee ID and password. The app then uses its own device token, which can be revoked from the portal.

## Build

Needs Android Studio (or JDK 17 + Android SDK 35).

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # configure your own signing key first
```

The default API is `DEFAULT_BASE_URL` in `app/build.gradle.kts`. It can be changed on the sign-in screen, e.g. `http://192.168.x.x:8000/api` for local testing (debug builds allow plain HTTP).

To publish an update: raise `versionCode` / `versionName`, build with the same signing key, and replace `CRM-Frontend/public/downloads/abl-calls.apk`.

Distribute the APK directly or through device management. Google Play restricts apps that read the call log.
