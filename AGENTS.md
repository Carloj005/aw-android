# AGENTS.md — AW Anime (Android Native)

Project instructions and engineering invariants for Antigravity / AI coding agents working on `Carloj005/aw-android` from any machine (Linux or Windows).

## 1. Project Overview & Toolchain
- **App**: AW Anime (`com.carloj005.awanime`) — native Android anime streaming client targeting `https://animeunity.cx` (auto-detecting TLD changes).
- **Stack**: Kotlin 2.3, Jetpack Compose, Material You 3 Expressive, Media3 ExoPlayer (HLS/MP4), OkHttp 4, Coil 2, WorkManager 2.10, `kotlinx.serialization`, `kotlinx.coroutines`.
- **SDK & Build**: `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`, `buildToolsVersion = "36.1.0"`, **JDK 21** (`jvmToolchain(21)`), AGP `9.0.1` (R8 Full Mode enabled for release).
- **Website (GitHub Pages)**: Served from `main:/docs` at `https://carloj005.github.io/aw-android/` (`docs/index.html`, `docs/styles.css`, `docs/app.js`, `docs/favicon.svg`, `docs/.nojekyll`).
- **Latest baseline**: `v1.4.3` (`versionCode = 13`).

## 2. Architecture Map
All source files live under `app/src/main/kotlin/com/carloj005/awanime/`:
- `AwAnimeApp.kt` + `AppContainer.kt`: Application entry point (`ImageLoaderFactory`) and manual DI container (`by lazy`, zero static singletons in UI/ViewModels).
- `MainActivity.kt`: Single-activity (`launchMode="singleTop"`), edge-to-edge M3 shell, 5-tab `NavigationBar` (`Home`, `Uscite`, `Cerca`, `Libreria`, `Download`), PiP controller + `RemoteAction` broadcast receiver (`RECEIVER_NOT_EXPORTED`), notification & AnimeUnity deep-link router.
- `data/model/Models.kt`: Immutable `@Serializable` models (`AnimeItem`, `AnimeStatus`, `AppSettings`, `WatchHistoryEntry`, `CalendarDayGroup`, `DownloadEntry`, `SkipSegment`, etc.) + `resolveResumeTarget` + `WatchHistoryEntry.isCaughtUp` / `shouldShowInContinueWatching`.
- `data/network/`:
  - `HttpClients.kt`: Single shared OkHttp `Dispatcher` and base client derived via `.newBuilder()` (isolated session cookies vs cookieless third-party vs compact media pool vs download pool).
  - `DohDns.kt`: DNS-over-HTTPS fallback (Cloudflare / Google) + 10-min TTL cache + IPv4/IPv6 anti-SSRF + `MemoryCookieJar`.
  - `UrlSecurityPolicy.kt`: Strict anchored regex allowlists for `animeunity.*`, embed hosts (`vixcloud`/`vix-content`/`vixsrc`), media URLs, and the 4 official GitHub release hosts.
  - `NetworkMonitor.kt`: Offline fast-fail & unmetered Wi-Fi checks.
  - `AnimeUnityClient.kt` + `AnimeUnityParser.kt` + `JsonExtensions.kt`: Laravel/Inertia + REST client (`/api/home/latest-episodes`, `/api/calendar`, `/api/archive`, `/api/search`, `/api/anime/{id}/episodes`) and pure JSON parsers.
  - `VixCloudExtractor.kt`: Extracts HLS 1080p variants, direct MP4 URLs, and VixCloud acoustic skip timestamps.
  - `SkipTimeRemoteDataSource.kt` + `SkipTimeValidator.kt`: Parallel AniSkip v2 + Anime-Skip GraphQL (JSON `variables`) multi-source consensus engine.
  - `SearchUtils.kt`: Chronological franchise sorting, fuzzy typo fallback, episode natural sort key.
- `data/storage/`:
  - `JsonFileStore.kt`: Thread-safe async JSON persistence on `Dispatchers.IO`, conflated writes, atomic `.tmp` rename, `.corrupt.bak` recovery.
  - `SettingsRepository.kt`, `FavoritesRepository.kt`, `HistoryRepository.kt` (lossless per-episode `mergeImport` + `syncKnownEpisodeAvailability`), `TrackedNotificationsRepository.kt` (per-anime bell opt-in/mute + discovered sequel tracking).
  - `DownloadRepository.kt` + `DownloadWorker.kt`: Foreground `WorkManager` downloads with HTTP `Range` resume, canonical path-traversal checks, and immediate `call.cancel()`.
- `data/notifications/`: `FavoriteEpisodeNotificationManager.kt` + `FavoriteEpisodesCheckWorker.kt` + `SequelTracker.kt` (per-anime bell filtering, silent first-run baseline, automatic next-season & future-sequel detection/auto-save, Android 13+ `POST_NOTIFICATIONS`, deep-link tap to `DetailScreen`).
- `data/integrations/`:
  - `AnimeUnityDeepLink.kt`: Security-validated parser for `animeunity.*` links (`/anime/<id>-<slug>`, `/anime/<id>-<slug>/<videoId>`, `/calendario`, `/archivio`, `/top-anime`) + `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS` helper.
  - `SuiteTvIntegration.kt`: Native Suite TV app launch (`com.nicolasbordeaux.suitetv`, copying cleaned title to clipboard + Toast when `/search` App Link isn't claimed, web fallback if uninstalled) + Trakt v2 watched-history JSON export.
  - `BugReportHelper.kt`: Pre-filled GitHub Issue URL builder and clipboard diagnostic report generator (zero PII).
- `data/update/AppUpdater.kt`: Fail-closed GitHub Releases updater enforcing HTTPS, exact 4 GitHub hosts, mandatory `.apk.sha256` match, and mandatory APK signing certificate match against installed app.
- `ui/`: `AppViewModel.kt`, `PlayerViewModel.kt`, `screens/` (`HomeScreen`, `CalendarScreen`, `SearchScreen`, `DetailScreen`, `HistoryScreen`, `DownloadsScreen`, `SettingsScreen`, `PlayerScreen`, `PlayerOverlays`), `components/AnimeCards.kt`, `theme/`.
- `util/CoroutineUtils.kt`: `runCatchingCancellable` (always rethrows `CancellationException`).

## 3. Critical Anti-Regression Rules (Do Not Break)
1. **R8 Full Mode (AGP 9.0.1)**:
   - `app/proguard-rules.pro` MUST keep reflective constructors for `androidx.room.RoomDatabase` / `androidx.work.impl.WorkDatabase_Impl`, `androidx.work.WorkerParameters`, `ListenableWorker`, `InputMerger`, `androidx.startup.Initializer`, `SavedStateRegistry$AutoRecreated`, `HlsMediaSource$Factory`, and `AwAnimeApp implements coil.ImageLoaderFactory`.
   - Keep `AwAnimeApp.container` initialized `by lazy` (never `lateinit`) for cold-start `WorkManager` / `ContentProvider` safety.
   - Whenever modifying `proguard-rules.pro` or adding libraries using reflection/WorkManager/Room, inspect `classes.dex` in `app-release.apk` with `dexdump -d` to verify reflective `<init>` methods were not stripped.
2. **Real HLS Streaming**:
   - Extensionless VixCloud `/playlist/<id>` URLs MUST set `MimeTypes.APPLICATION_M3U8` on `MediaItem.Builder` so Media3 uses `HlsMediaSource` instead of falling back to MP4 (`limit_conn=2` -> HTTP 503 on rapid seeks).
3. **Player PiP & Gestures**:
   - Pause ExoPlayer on `Lifecycle.Event.ON_STOP` (never `ON_PAUSE`) so Picture-in-Picture stays playing.
   - Keep safe inner vertical swipe zones (`10%–35%` brightness, `65%–90%` volume, `18%–82%` Y) so system gesture edges remain unobstructed.
4. **Security & Concurrency**:
   - Never weaken `AppUpdater` fail-closed SHA-256 / signer / GitHub host checks or `DohFallbackDns` IPv4/IPv6 anti-SSRF rules (`::ffff:0:0/96`, `::/96`, `100::/64`, NAT64 `64:ff9b::/96`).
   - Never add disk I/O on the Main Thread; persist via `JsonFileStore` on `Dispatchers.IO`.
   - Always use `runCatchingCancellable` inside coroutines and `collectAsStateWithLifecycle()` in Compose.
   - Keep `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` `<include>`-only (Android Lint rejects `<exclude>` outside `<include>` paths).
5. **Continue Watching**:
   - Home "Continua a guardare" filters by `entry.shouldShowInContinueWatching` (`!entry.isCaughtUp`); keep full history in Libreria -> Cronologia.
6. **Secrets**:
   - Never commit `*.jks`, `signing.properties`, tokens, or print raw signing passwords.

## 4. Build, Test & Release Workflow
### Local verification (Linux / macOS / Windows)
```bash
./gradlew testDebugUnitTest assembleDebug assembleRelease --no-daemon
```
- Requires JDK 21 (`JAVA_HOME`) and Android SDK 36 (`ANDROID_HOME`).
- Local release signing is optional: if `~/.aw-android-signing/signing.properties` is absent, local `assembleRelease` falls back to debug signing, while **GitHub Actions signs every tagged release with the official `CN=AW Anime` keystore** from repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

### Publishing a new release (`vX.Y.Z`)
1. Bump `versionCode` (+1) and `versionName` (`"X.Y.Z"`) in `app/build.gradle.kts`.
2. Add short, plain-Italian user-facing notes under `## vX.Y.Z` in `RELEASE_NOTES.md` (grouped in `✨ Novità` and `🛠️ Correzioni`, zero technical jargon, zero raw `**`/backticks/URLs — automatically published as the GitHub Release body by `.github/workflows/release.yml`), plus update technical details in `README.md` and fallback version in `docs/index.html` and `docs/app.js`.
3. Run `./gradlew testDebugUnitTest assembleRelease --no-daemon` (0 test failures, `lintVitalRelease` clean).
4. Commit to `main`, create annotated tag `git tag -a vX.Y.Z -m "..."`, and push `git push origin main vX.Y.Z`.
5. Wait for GitHub Actions workflows `Build & Publish Android Release APK`, `Android CI`, and `pages build and deployment` to finish with `success`, and confirm `releases/latest` includes both `AW-Anime-vX.Y.Z.apk` (signed `CN=AW Anime`) and `AW-Anime-vX.Y.Z.apk.sha256`.
