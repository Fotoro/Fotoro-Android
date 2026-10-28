# Fotoro for Android

The Android companion for a self-hosted Fotoro server. It signs in with the same Google/Supabase account as the web dashboard, pairs this phone with the account's Tailscale server, shows the local camera roll, and backs up original photos and videos.

The Library has **On phone** and **On server** views. The server view pages through `/api/v1/media`, uses small cached thumbnails and larger previews, and streams video by URL. It can still show items after they are no longer on the phone, provided the server and Tailscale route are reachable.

## Configure and run

1. Start the linked server on your computer with `./fotoroctl start`. Keep Tailscale connected on both devices. The current pairing flow uses the server's private `*.ts.net` address; the website's `/api/pairing` route must also be able to reach the running server through its configured relay.
2. In Google Cloud Console, create an **Android** OAuth client for package `com.nc.fotoro` and the SHA-1 of the signing certificate. Use the **same Google Cloud project** and Web OAuth client as the existing Fotoro website. Register every debug and release signing certificate used to install the app.
3. Add these publishable values to the ignored `local.properties` file (or matching environment variables):

   ```properties
   FOTORO_GOOGLE_WEB_CLIENT_ID=your-existing-web-oauth-client-id
   FOTORO_SUPABASE_URL=https://your-project.supabase.co
   FOTORO_SUPABASE_ANON_KEY=your-publishable-supabase-key
   ```

   The Google Web client ID must match the ID accepted by the website's `/api/auth/verify` endpoint. Never place the Supabase service role key in the Android app.

4. Build with `./gradlew :app:assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`. Sign in with Google, pair the phone, then grant photo and video access.

For the debug certificate SHA-1, run `./gradlew signingReport` and find the `debug` variant. A Google sign-in error before the website request usually means the Android OAuth client or SHA-1 is missing.

The `release` build currently produces an unsigned APK. Configure a release signing key and register its SHA-1 with the Android OAuth client before distribution.

## How backup works

- The server stays the source of truth. The app asks `/api/v1/device-assets/status` about up to 200 MediaStore assets at a time, and only hashes files the server says it needs. It uploads originals in 4 MiB chunks with the server's resumable upload API. An interrupted upload resumes from the server's reported byte offset. MediaStore generation tracking skips unchanged full scans; a full reconciliation still runs daily. Pending second-copy items are checked in bounded batches to avoid hammering the server.
- WorkManager runs a periodic check when battery is not low and the selected network is available. Automatic backup defaults to unmetered Wi-Fi; users can allow mobile data in Backup settings. Android schedules periodic work at its discretion; 15 minutes is the requested interval, not a delivery guarantee. While the app is visible, a MediaStore observer queues another check after new media appears. Manual **Back up now** can use any connected network.
- Transfers run off the UI thread and show progress in a foreground notification. Each run yields after eight minutes so Android can schedule later work. The server performs its own second-copy backup; the app reports **on server** and **two copies** separately.
- Fotoro never deletes media from the phone. If Android grants access to selected photos only, it can back up only those selections. Background backup requires the phone's Tailscale VPN and home server to be reachable.

## Code layout

`AppViewModel` owns screen state; `data/` contains the encrypted credential store, network API, and MediaStore catalog; `sync/` contains WorkManager scheduling and resumable transfer; `ui/` contains the Compose onboarding, gallery, viewer, and backup controls. Credentials are encrypted with Android Keystore and excluded from Android backup.
