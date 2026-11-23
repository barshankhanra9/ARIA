# Security Summary

This security audit reviewed the entire NocturnePlayer (ARIA) Android repository prior to making it public on GitHub. No hardcoded third-party API keys, tokens, passwords, or personal developer file paths were found in the codebase. Security hardening was applied across repository hygiene, network transport, DNS/socket SSRF defenses, input and URI sanitization, MediaSession IPC boundary checks, and bounded I/O.

## What Was Hardened
- **Repository Hygiene & Secret Management (`.gitignore`, `.env.example`)**:
  - Added a comprehensive `.gitignore` excluding `.env`, `.env.local`, `.env.*.local`, release keystores (`*.jks`, `*.keystore`, `keystore.properties` while keeping `debug.keystore.base64`), `local.properties`, `build/`, `app/build/`, `.gradle/`, `.build-outputs/`, `*.apk`, `*.aab`, `node_modules/`, and SQLite/cache files (`*.db*`, `*.sqlite*`, `cache/`).
  - Created `.env.example` with placeholder release signing variables (`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) matching `app/build.gradle.kts` (`System.getenv(...)`). Note that this is a native Android/Kotlin project rather than a Node.js/web app (`process.env` / `import.meta.env` do not apply), and all music/lyrics APIs used are public and keyless.
- **SSRF, Cleartext HTTP, DNS Rebinding, & Host Spoofing Prevention (`InputAndUrlSecurity.kt`, `OkHttpDownloader.kt`, `RemoteConfigRepository.kt`, `StreamQualitySelector.kt`, `PlaybackService.kt`, `PaperComponents.kt`, all providers)**:
  - Replaced substring `isYouTubeHost` matching (`host.contains("youtube.com")`) with exact/subdomain validation (`isTrustedYouTubeHost`), and stopped sending a default `Referer: https://www.youtube.com/` header to non-YouTube audio hosts.
  - Added an SSRF-safe `Dns` resolver and `ssrfNetworkGuardInterceptor` to `OnlineHttpClient.client` blocking cleartext `http://` requests, embedded URL credentials, `.local`/`.internal`/metadata hostnames, obfuscated decimal/hex IPs, loopback, RFC 1918 private ranges, CGNAT (`100.64.0.0/10`), link-local/cloud metadata (`169.254.169.254`), and IPv6 ULA/mapped loopback addresses.
  - Validated all dynamic instance/discovery URLs, resolved audio stream URLs, artwork URLs, LRCLIB endpoints, and user-supplied `remoteProviderConfigUrl` values before saving or fetching.
- **Input, Path, & Import/Export Sanitization (`InputAndUrlSecurity.kt`, `OnlineProviderManager.kt`, `PlaylistPortability.kt`, `MainViewModel.kt`, `OnlineCoordinator.kt`, `OnlineLibraryRepository.kt`)**:
  - Sanitized search queries, track/playlist names, descriptions, and search history against control characters, bidi overrides, and oversized strings. Note that the UI is native Jetpack Compose (no `WebView` or HTML rendering), so browser DOM XSS and CORS do not apply, while M3U8 playlist export now strips newlines/injected directives (`#EXT...`) from titles, artists, and playlist names.
  - Validated `videoId`, `channelId`, `playlistId`, `trackId`, `userId`, and `songId` segments against path traversal (`/`, `\`, `..`, `?`, `#`, `&`) before constructing upstream provider API paths.
  - Bounded HTTP response body reads (`readBodyBounded`, 512 KB for remote config, 1 MB for lyrics, 4 MB for provider JSON) and file imports (`PlaylistPortability.readTextFromUri`, max 5 MB), clamped imported backup settings (`sanitizeUserSettings`), and gated `ArtworkCache`, `TagAndLyricsEngine`, and `PlaybackService` (`onAddMediaItems` and queue restore) against restricted `file:///data/...`, `/proc/...`, `/etc/...`, or `http://` URIs.
  - Added a 5-second automatic refresh cooldown on `RemoteConfigRepository` and a burst rate limiter in `OnlineProviderManager`.
- **Android Manifest & Dependency Audit**:
  - Set `android:allowBackup="false"` in `AndroidManifest.xml` to block cloud/ADB extraction of the Room database and DataStore preferences.
  - Audited `app/build.gradle.kts` / `build.gradle.kts`: `NewPipeExtractor` (`v0.27.1`) is fetched via JitPack (keep updated and consider Gradle verification metadata), release `isMinifyEnabled` is `false` (recommend enabling shrinking/obfuscation with ProGuard rules before Play release), and `AriaDatabase` uses `.fallbackToDestructiveMigration()` (recommend explicit migrations for future schema bumps).

## PoC Verification
All verification checks ran via Gradle on the JVM/Robolectric test runner (`gradle :app:testDebugUnitTest`):
- **`SecurityAuditUnitTest` (8/8 passed)**:
  - Verified blocking of cleartext `http://`, `127.0.0.1`, `169.254.169.254`, RFC 1918 private IPs, CGNAT, `[::1]`, `[fc00::1]`, decimal (`2130706433`) and hex (`0x7f.0.0.1`) IP obfuscations, `metadata.google.internal`, and credential-bearing URLs.
  - Verified `OnlineHttpClient.isYouTubeHost` rejects `youtube.com.evil.example` and `OnlineHttpClient.defaultMediaHeaders` does not leak YouTube `Referer`/`Origin` to JioSaavn/other hosts.
  - Verified default production `RemoteConfigRepository` and `OkHttpDownloader` reject `http://` and loopback/metadata SSRF targets without dispatching network requests.
  - Verified `PipedProvider`, `InvidiousProvider`, `JioSaavnProvider`, and `AudiusProvider` reject `../../admin?secret=1` traversal IDs, and `StreamQualitySelector` strips `http://` and private/metadata stream URLs.
  - Verified M3U8 directive/newline sanitization, `file:///data/...` URI rejection, backup settings clamping (`playbackSpeed`, `crossfadeSeconds`, `includedSafFolderUris`, `remoteProviderConfigUrl`), and bounded response reading (`IOException` on oversized payloads).
- **Existing Test Suites (22/22 passed)**:
  - `OnlineMusicEngineTest` (12/12 passed)
  - `AriaEngineUnitTest` (8/8 passed)
  - `AriaUnitAndCujTest` (2/2 passed)
