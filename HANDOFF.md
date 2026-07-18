UNITEDPLAYLISTS — PROJECT HANDOFF
=================================
For a fresh AI/dev picking up this project cold. Read this before touching code.
Last updated after: Spotify playback working, YouTube+SoundCloud on NewPipe scraper,
extractor auto-update wired, safeStorage key-rotation bug fixed.


0. WHAT THIS IS
---------------
A cross-service music app: one place to hold playlists that mix tracks from Spotify,
YouTube, and SoundCloud, and play each track through its own service. Local-first —
no hosted server, no user accounts.

Architecture (three pieces, one repo):
  - backend/        Java 21 + Spring Boot 3.5.3. Owns playlists, tokens, search,
                    playback "tickets". Runs as a LOCAL child process on a loopback
                    port, NOT a hosted server.
  - packages/core-ui   React 18 + Vite + TypeScript. The whole UI. Framework-agnostic
                       enough that Capacitor could wrap it for mobile later.
  - packages/desktop   Electron shell (Castlabs build). Spawns the Java backend,
                       owns the OS keychain + OAuth loopback + Widevine.

The backend NEVER touches audio bytes. It returns a "playback ticket" (which SDK/URL
to use); the client plays it. This is both the licensing-legal design AND the only
one that works — no service licenses server-side audio redistribution.


1. BUILD & RUN (DEV)
--------------------
Prereqs (verified working versions):
  - Java 21   (Spring Boot 3.5.3 needs 17+; code uses 21 features: virtual threads,
               Math.clamp, records/sealed. openjdk 21.0.11 confirmed.)
  - Maven 3.9+
  - Node 20+  (26.4.0 confirmed)
  - A freedesktop Secret Service running (see hiccup #6). On Linux without GNOME/KDE
    you also need the password-store override (already coded, see hiccup #6).
  - Spotify Premium + a Spotify app client ID (see section 3 for why Premium).
  - Castlabs Electron (installed via git URL; see hiccup #2).

First-time setup:
  cd /home/monke/Documents/United.Playlists
  npm install                    # installs core-ui + desktop deps
  # If Electron's binary is missing after install (empty node_modules/electron/dist):
  npm install-scripts approve electron     # its postinstall DOWNLOADS the ~220MB binary
  # If npm can't fetch Electron over git+ssh (Host key verification failed):
  git config --global url."https://github.com/".insteadOf "git@github.com:"

Build the backend jar (Electron spawns this):
  npm run backend:build          # == cd backend && mvn -B package -DskipTests
  # produces backend/target/backend-0.1.0-SNAPSHOT.jar (a PropertiesLauncher fat jar)

Run the app in dev (TWO terminals):
  # Terminal 1 — Vite dev server for the renderer (Electron loads http://localhost:5173)
  npm run dev --workspace @unitedplaylists/core-ui
  # Terminal 2 — Electron; it spawns the Java backend itself
  export UP_SPOTIFY_CLIENT_ID=your-spotify-client-id     # optional; Spotify unavailable without it
  npm run dev --workspace @unitedplaylists/desktop        # == electron .

  NOTE: `npm run dev` at the ROOT only starts Electron (terminal 2). You still need
  the Vite server (terminal 1) running first, or the window is blank in dev.

Tests:
  npm run backend:test           # == cd backend && mvn -B verify  (~260 tests)
  npm test                       # core-ui vitest (~92 tests)
  (cd packages/core-ui && npx tsc --noEmit)               # typecheck
  (cd packages/desktop && node --test src/*.test.js)      # 16 tests (oauth-callback,
                                                          #   backend, java-runtime)
  (cd packages/core-ui && npx vitest run --coverage)      # coverage

Build the renderer bundle (what packaged Electron loads from core-ui/dist):
  npm run build --workspace @unitedplaylists/core-ui
  # desktop `build` script just delegates to this — the Electron shell is plain JS,
  # nothing to bundle. (See hiccup #12: it USED to wrongly run `vite build` in
  # packages/desktop, which fails — there's no index.html there.)

Build installers (renderer + backend jar + Electron shell -> release/):
  npm run dist                   # installer for the host OS
  npm run dist:linux|:win|:mac   # a specific OS (build each on that OS / in CI)
  # Config is the root package.json "build" field; the app needs Java 21 on PATH
  # (no JRE bundled). Full details in PACKAGING.md.

Live/manual probes (network, disabled in CI — run by hand):
  cd backend && mvn -B test -Dtest=NewPipeLiveProbeTest -Dnewpipe.live=true

Handy env vars the backend reads (Electron sets most):
  UP_DATA_DIR         where the H2 db + token.key + newpipe/ cache live
  UP_TOKEN_KEY        base64 32-byte AES key for encrypting tokens at rest (REQUIRED;
                      backend refuses to start without it rather than store plaintext)
  UP_API_TOKEN        shared secret the renderer must send on every request
  UP_PORT             backend port (0 = ephemeral, chosen at runtime, printed to log)
  UP_SPOTIFY_CLIENT_ID   Spotify PKCE client id (also settable in-app; DB wins)
  UP_REDIRECT_URI     OAuth callback (default http://127.0.0.1:8420/callback)
  UP_PASSWORD_STORE   Electron keychain backend override (e.g. gnome-libsecret)


2. HICCUPS LEARNED THE HARD WAY (do not re-discover these)
----------------------------------------------------------
#1  ELECTRON CANNOT TARGET MOBILE. The original spec said "Electron + works on
    Android/iOS" — mutually exclusive. Desktop-only via Electron; mobile would be
    Capacitor wrapping the same core-ui later. Design keeps core-ui shell-agnostic
    for that reason.

#2  SPOTIFY PLAYBACK NEEDS WIDEVINE, WHICH STOCK ELECTRON LACKS. The Web Playback SDK
    plays DRM audio via EME; stock Electron ships no CDM (Google won't license it for
    redistribution). Symptom: "Failed to initialize player". Fix: use Castlabs'
    "Electron for Content Security" build:
      devDependencies.electron = git+https://github.com/castlabs/electron-releases.git#v43.0.0+wvcus
    main.js calls `await components.whenReady()` BEFORE opening any window (the SDK
    checks for a CDM at page load; a late CDM is too late). Builds are VMP-signed for
    dev; DISTRIBUTION needs production signing via castLabs EVS.

#3  npm's allowScripts PIN IS BY EXACT VERSION. Every Electron version bump silently
    skips the postinstall (which downloads the 220MB binary), leaving
    node_modules/electron/dist empty. Bit us THREE times. After any electron bump:
    `npm install-scripts approve electron`. The root package.json "allowScripts" also
    needs the new exact version.

#4  npm REWRITES github deps to git+ssh:// IN THE LOCKFILE regardless of what you
    write. On a machine with no SSH keys this fails with "Host key verification
    failed". Fix is the global git insteadOf config (section 1). Can't be pinned in
    the repo — hosted-git-info normalizes it.

#5  CORS. The renderer (Vite dev server / file://) and backend (ephemeral loopback
    port) are ALWAYS different origins. Every call carries a custom auth header →
    browser sends an OPTIONS preflight FIRST → preflight carries no credentials →
    LocalAuthFilter 401'd it → browser abandoned the real request → UI said "backend
    could not be reached" while the backend was fine. Fix: CorsFilter ordered BEFORE
    the auth filter, allowing only loopback + null/file:// origins; LocalAuthFilter
    also skips CORS preflights. 250 tests missed this because Java's HTTP client
    doesn't enforce CORS — only a browser does.

#6  LINUX KEYCHAIN. Electron safeStorage needs an OS keychain. Chromium only
    auto-detects one on GNOME/KDE; on LXQt/XFCE/i3 it silently falls back to
    plaintext, safeStorage.isEncryptionAvailable() returns false, and the app (rightly)
    refuses to start. Fix: main.js forces `--password-store=gnome-libsecret` (the
    portable Secret Service backend — works with ksecretd/kwallet/keepassxc too),
    overridable via UP_PASSWORD_STORE. kwallet6 specifically hung with dbus NoReply;
    gnome-libsecret worked.

#7  safeStorage KEY IS TIED TO THE APP NAME. safeStorage stores its key in the
    keychain under an entry named after the app. Changing app.setName() rotates that
    entry → the stored token.key can't be decrypted → "error while decrypting the
    ciphertext provided to safeStorage.decryptString". Fix: loadOrCreateTokenKey now
    catches the decrypt failure and regenerates (cost: reconnect Spotify once; YT/SC
    unaffected). If you'd rather NOT lose stored tokens, revert the app name before
    launch.

#8  MY KNOWLEDGE CUTOFF vs. LIVE APIs — the biggest lesson. Spotify ran a BREAKING
    Web API migration in FEBRUARY 2026 (after training cutoff). Debugged against a
    dead API for ages. Concrete changes now handled:
      - Search `limit` max cut 50 -> 10 (default 20 -> 5). Sending 20 = 400 "Invalid
        limit". SEARCH_PAGE_SIZE = 10.
      - GET /playlists/{id}/tracks REMOVED -> /playlists/{id}/items; the track moved
        from items[].track to items[].item (old field kept as fallback).
      - Playlist items `limit` max 100 -> 50.
      - user object lost email/country/product fields.
    RULE: for anything touching a live third-party API, CHECK CURRENT DOCS via
    web fetch. Do not trust recall.

#9  SPOTIFY 403 ON IMPORT was NOT auth. /me/playlists returns playlists you FOLLOW as
    well as own, but /playlists/{id}/items 403s for anything you don't own/collaborate
    on. One followed playlist (Discover Weekly) 403'd and, uncaught, killed the whole
    import. Fix: fetch /v1/me once, check ownership, only fetch tracks for owned/collab
    playlists, and catch per-playlist 403 -> mark "unreadable", never fail the batch.
    Unreadable count is surfaced in the UI ("N could not be read").

#10 SPOTIFY DEV MODE REQUIRES PREMIUM (since 9 Mar 2026) for EVERY call, not just
    playback. Free account = 403 everywhere. Also: the app owner must be on the app's
    User Management allowlist, "Web API" must be ticked, and redirect URI must be the
    IP LITERAL (http://127.0.0.1:8420/callback) — Spotify rejects "localhost" outright.

#11 WEB PLAYBACK SDK NEEDS 3 SCOPES: streaming, user-read-email, user-read-private.
    I removed the latter two thinking they were dead (the FIELDS were removed in the
    migration) — but the SDK validates the SCOPES on the token regardless. Result:
    "Invalid token scopes". Lesson: a scope can be load-bearing even when the data it
    guards is unused. Removing a scope requires reconnect (refresh preserves old
    scopes).

#12 DESKTOP BUILD SCRIPT ran `vite build` inside packages/desktop — no vite/config/
    index.html there, so "Could not resolve entry module index.html". The Electron
    shell needs no bundling. Fixed to delegate to core-ui's build.

#13 DOUBLE URL-ENCODING. Providers pre-encoded query strings then handed a String to
    RestClient, which encoded again: "rick astley" -> q=rick%2520astley -> Spotify
    searched literal "rick%20astley". Fix: build URIs via RestClient's UriBuilder
    callback (encodes exactly once), paginate by explicit offset.

#14 REDIRECT URI DRIFT. Two @Value defaults for the OAuth redirect disagreed (port
    842 vs 8420). Consolidated into one OAuthProperties record + a test asserting the
    advertised URI == the sent URI.

#15 TEST BLIND SPOTS: integration tests used Java's HTTP client (no CORS, no browser
    header restrictions), so they passed while the real browser flow was broken. When
    a feature only manifests in a browser, test the logic directly (see
    LocalAuthFilterTest, CorsIntegrationTest) — not just through a Java client.

#16 SCRAPER PROVIDERS MUST BE DISABLED IN TESTS. NewPipe makes live network calls.
    application-test.yml sets unitedplaylists.scraper.enabled=false and
    newpipe.auto-update=false so the suite never hits the network. Live behavior is in
    the hand-run NewPipeLiveProbeTest.

#17 window.prompt / window.alert / window.confirm ARE DISABLED IN ELECTRON. They
    silently return null (prompt) or do nothing — no error, no dialog. The old
    "+ New playlist" button called window.prompt for the name, so on desktop it
    looked like playlist creation was simply missing ("users can only import"). The
    backend create endpoint had worked all along. Fix: a real in-app modal
    (components/CreatePlaylistDialog). RULE: never reach for the window.* dialogs in
    the renderer — build a component, or (for a truly native dialog) go through the
    Electron main process via the preload bridge.


3. KEY DESIGN DECISIONS (the "why", so you don't undo them)
-----------------------------------------------------------
- PROVIDER ABSTRACTION is the spine. MusicProvider interface + ProviderRegistry
  (auto-discovers @Component beans). Adding a service = one class + a ProviderId enum
  value. There is deliberately NO playlist-write method — providers CANNOT push edits
  back to a service, enforcing "imported playlists are local copies" structurally.

- TWO PROVIDER KINDS:
    Authenticated (Spotify): OAuth, needs connect, has tokens.
    Anonymous (YouTube, SoundCloud): scraped via NewPipe, no auth/tokens/quota,
      requiresAuthentication()=false, always searchable, import-by-URL.
  SearchService searches connected authenticated providers + all available anonymous
  ones. PlaybackService passes ProviderCredentials.anonymous() to anonymous providers.

- YOUTUBE + SOUNDCLOUD USE NewPipeExtractor (scraping), NOT official APIs. Chosen
  deliberately: no quota, no OAuth, no tokens to expire, and REAL audio stream URLs
  (played by a plain <audio> element via DirectAudioAdapter). Cost: no account
  playlist access (import by URL instead), breaks when sites change, violates ToS,
  and rules out app-store distribution. This is a PERSONAL-USE build.

- EXTRACTOR AUTO-UPDATE: NewPipe is a COMPILE-TIME dependency — you can't hot-swap it
  in a running JVM. So ExtractorUpdateService checks GitHub (startup + daily),
  downloads the newest jar to <UP_DATA_DIR>/newpipe/, and the Electron launcher
  applies it on NEXT START by putting it first on the classpath via Spring Boot's
  -Dloader.path (the jar uses PropertiesLauncher / <layout>ZIP</layout>). First URL on
  the classpath shadows the bundled BOOT-INF/lib copy. Verified: app boots with the
  override jar and search runs through it. Bundled fallback version lives in TWO
  places that must stay in sync: pom.xml <newpipe.version> and
  application.yml unitedplaylists.newpipe.bundled-version.

- SECURITY: backend binds loopback AND requires a per-run shared secret (loopback is
  NOT access control — any local process/website can reach 127.0.0.1). Tokens
  encrypted at rest (AES-256-GCM) with a key in the OS keychain. Renderer gets only
  short-lived access tokens, never refresh tokens. OAuth sign-in in the real browser
  (services block embedded webviews; embedded would expose the password). Renderer:
  context isolation, no node integration, strict CSP, 4-call preload bridge.

- PLAYER FACADE: one Player over multiple SDK adapters (SpotifyAdapter = Web Playback
  SDK; DirectAudioAdapter = <audio> for YT/SC). Facade swaps adapters mid-playlist so
  a mixed playlist "just works". Adding a service = one adapter.

  The facade owns everything the UI treats as "the player": transport, the QUEUE
  (setQueue + playQueueItem + moveInQueue/removeFromQueue/addToQueue, all keeping the
  playing-track index correct through edits), SHUFFLE (playShuffled + setShuffle,
  Fisher-Yates, single up-front shuffle — reshuffles only the tracks still AHEAD so
  the current one keeps playing), PROGRESS (position/buffered/duration polled once a
  second via refreshProgress, only while playing), and NEXT-TRACK PREFETCH. Queue +
  index + shuffle live in PlayerState, so the transport bar and the queue panel are
  just subscribers — no separate store.

  ADAPTER CONTRACT additions the facade relies on:
    - getBufferedMs()/getDurationMs(): drive the progress bar's "loaded ahead" span
      and the real (not metadata-estimate) duration. Spotify returns null for
      buffered on purpose — the Web Playback SDK exposes no buffer window, so the UI
      HIDES that layer rather than inventing one. DirectAudio reads audio.buffered
      (the contiguous range covering the play head) and audio.duration.
    - prepare(ticket) [OPTIONAL, best-effort, must never throw]: pre-load the next
      track. The facade fetches the NEXT track's ticket as soon as the current one
      starts, caches it (keyed by track so a queue edit discards a stale prefetch),
      and calls prepare(). DirectAudioAdapter pre-loads the stream into a SECOND
      <audio> element and promotes it on play instead of reloading — this is what
      makes YT/SC track-to-track transitions near-seamless. Spotify no-ops (its SDK
      can only play "now", not warm a specific next URI). On advance the facade reuses
      the cached ticket, skipping the network round-trip.


4. ROADMAP / WHAT'S LEFT
------------------------
Done: playlists (create via in-app modal / edit / reorder / delete, mixed-service),
  Spotify (OAuth + import + search + playback via Web Playback SDK), YouTube +
  SoundCloud (scraped search + import-by-URL + direct-audio playback), in-app
  credential entry, extractor auto-update, all the security hardening above.
  PLAYER UX: seekable progress bar with a buffered indicator, shuffle (per-playlist
  + a transport toggle), an editable queue panel (jump / reorder / remove), and
  next-track pre-buffering for smoother same-service transitions. See the PLAYER
  FACADE design note above for how these hang together.

Not done / next:
  1. APPLE MUSIC — stubbed (AppleMusicProvider throws, isSetupSupported=false). Needs
     a PAID Apple Developer membership for a MusicKit key, an ES256 developer token
     (JWT signed server-side), and a Music User Token via MusicKit JS on the client.
     The stub documents the shape; it's a real chunk of work gated behind $99/yr.
  2. PACKAGING / DISTRIBUTION — DONE for Linux, configured for all three desktop OSes.
     See PACKAGING.md. `npm run dist` builds an installer for the host OS (Linux
     AppImage + deb verified; win NSIS and mac dmg build on their own OS / CI). The
     electron-builder config lives in the root package.json "build" field; the backend
     jar ships as resources/backend.jar; the renderer is bundled into the asar. The app
     requires Java 21 on PATH (checked at startup, friendly dialog if missing/old) —
     no JRE is bundled, though main.js still prefers resources/jre/bin/java if one is
     added later. STILL OPEN: production Widevine VMP signing (packaging/afterPack.cjs
     runs it best-effort — needs a castLabs EVS account for Spotify playback in
     distributed builds) and OS code-signing/notarization. Note this build
     scrapes YT/SC, which blocks app-store distribution regardless.
     APP ICON: done — packaging/icon.png (1024², generated from packaging/icon.svg,
     the vinyl-wreath emblem). electron-builder auto-derives .icns/.ico/Linux sizes
     from it since buildResources=packaging; the running window icon is
     packages/desktop/src/assets/icon.png (see createWindow in main.js).
  3. MOBILE — core-ui is deliberately shell-agnostic; Capacitor could wrap it. But
     mobile playback needs native Spotify/MusicKit SDK plugins, and a hosted backend
     becomes a real conversation (tokens can't just live on-device the same way).
  4. GAPLESS PLAYBACK across services — next-track PREFETCH now warms the upcoming
     track (DirectAudioAdapter pre-loads it into a second <audio>), so YT/SC ->
     YT/SC transitions are near-seamless. Two gaps remain: (a) crossing SDKs
     (Spotify <-> DirectAudio) still swaps adapters and has an audible gap — Spotify's
     SDK can't be pre-warmed for a specific next URI; (b) even same-service is
     "near-instant", not sample-accurate gapless (that needs Web Audio crossfade /
     MediaSource, a bigger job).
  5. YOUTUBE MUSIC (not just YouTube) — no public API; NewPipe reaches YouTube proper,
     not the YT Music premium catalog.
  6. Nice-to-haves: playlist reordering across services already works; could add
     cross-service "find this track on another service", dedup, offline metadata
     refresh, drag-and-drop, keyboard shortcuts.


5. FILE MAP (backend/src/main/java/dev/unitedplaylists/)
--------------------------------------------------------
  domain/        Playlist, PlaylistEntry, Track, TrackRef, ProviderId,
                 ServiceConnection, ProviderSetting  (records/entities; local-only)
  provider/      MusicProvider (the SPI), ProviderRegistry, ProviderException,
                 PlaybackTicket/Method, ImportedPlaylist, HttpSupport (error mapping),
                 ProviderCredentials
    spotify/     SpotifyProvider, SpotifyOAuthClient, SpotifyProperties
    newpipe/     NewPipeDownloader, NewPipeExtractionService, NewPipeProvider (base),
                 NewPipeInitializer, ExtractorUpdateService
    youtube/     YoutubeScraperProvider   (extends NewPipeProvider)
    soundcloud/  SoundCloudProvider       (extends NewPipeProvider)
    apple/       AppleMusicProvider       (stub)
    oauth/       OAuthClient, OAuthProperties, Pkce, TokenSet, AuthorizationRequest
  service/       SearchService (parallel fan-out on virtual threads), PlaylistService,
                 ImportService, PlaybackService, ConnectionService,
                 ProviderSettingsService, EnvironmentCredentials, OAuthFlowService
  web/           PlaylistController, SearchController, PlaybackController,
                 ConnectionController, ExtractorController, GlobalExceptionHandler,
                 dto/Dtos
  security/      TokenCipher (AES-256-GCM)
  config/        AppConfig, SecurityConfig (CORS + shared-secret filter),
                 LocalAuthFilter
  db/migration/  V1__initial_schema.sql, V2__provider_settings.sql (Flyway)

Frontend (packages/core-ui/src/):
  api/           client.ts (typed backend client), types.ts
  player/        Player.ts (facade: transport + queue + shuffle + progress + prefetch),
                 SpotifyAdapter.ts, DirectAudioAdapter.ts (2nd <audio> for pre-buffer),
                 types.ts (PlayerState carries queue/index/shuffle/buffered; the
                 PlayerAdapter contract incl. getBufferedMs/getDurationMs/prepare)
  views/         SearchView, PlaylistView (Play all + Shuffle), ConnectionsView,
                 ProviderSetupForm
  components/    PlayerBar (transport + ProgressBar + shuffle/queue toggles),
                 ProgressBar (seekable, played+buffered layers, commit-on-release),
                 QueuePanel (jump/reorder/remove drawer),
                 CreatePlaylistDialog (modal; replaces the Electron-dead window.prompt),
                 ServiceBadge
  util/          time.ts (formatDuration, shared by ProgressBar + PlaylistView)
  App.tsx, main.tsx (bootstrap; reads backend info from the Electron bridge)

Desktop (packages/desktop/src/):
  main.js        Electron main: password-store, Widevine, token.key, verifyJava,
                 spawn backend (with loader.path for extractor updates), OAuth
                 loopback, IPC
  backend.js     BackendProcess: spawns java, reads chosen port from stdout
  java-runtime.js  pure Java-version helpers (parse/compare) used by main.js's
                 startup check; unit-tested without Electron
  oauth-callback.js   one-shot loopback listener for the OAuth redirect
  preload.cjs    the 4-call bridge exposed to the renderer

Packaging (root):
  packaging/afterPack.cjs   electron-builder hook: best-effort Castlabs VMP signing
  package.json "build"      electron-builder config (targets, backend.jar resource)


6. GOTCHAS FOR THE NEXT SESSION
-------------------------------
- The backend jar MUST be rebuilt (npm run backend:build) after backend changes;
  Electron runs the built jar, not live classes.
- Two version constants for NewPipe must stay in sync (pom + application.yml).
- app.setName vs userData path currently mismatch ("United.Playlists" vs
  "UnitedPlaylists") — harmless (path is explicit) but tidy up if it bugs you.
- Scraping breaks without warning when YouTube/SoundCloud change; the auto-updater is
  the mitigation, but a manual `newpipe.version` bump in pom is the fallback.
- When adding an authenticated provider: implement MusicProvider + OAuthClient, add a
  ProviderId, add a PlaybackMethod + a PlayerAdapter, and add a Flyway migration only
  if you need new columns. The registry + search + playback pick it up automatically.
- Reference docs live in README.md (user-facing), this file (dev-facing), and
  PACKAGING.md (how to build installers, Java 21 requirement, per-OS notes, VMP signing).
