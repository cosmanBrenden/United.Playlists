# UnitedPlaylists

UnitedPlaylists allows you to create and listen to playlists using songs from different music streaming services.

Songs from an artist you like are often on one service but not another, and a playlist you have built up over years is painful to move. UnitedPlaylists keeps one library of playlists that can mix tracks from every service you use, and plays each track through the service it came from.

## Status

Desktop (Windows, macOS, Linux). Spotify, YouTube, and SoundCloud work; Apple Music is stubbed. Packaged installers build for all three platforms (see [Installing](#installing)); Linux AppImage and `.deb` are verified. See [Roadmap](#roadmap).

## How it works

```
┌─────────────────────── Electron ────────────────────────┐
│  Renderer (React)                                        │
│    Player facade ──┬── SpotifyAdapter     → Web Playback │
│                    └── DirectAudioAdapter → <audio>      │
│                            ▲                             │
│  Main process              │ audio, direct from service │
│    spawns backend, OAuth loopback, Java 21 check         │
└────────────────────────────┼────────────────────────────┘
                             │ HTTP (loopback + secret)
                    ┌────────┴─────────┐
                    │  Java backend    │  metadata, playlists,
                    │  (Spring Boot)   │  OAuth tokens, search
                    └────────┬─────────┘
                             │
        Spotify Web API  ·  YouTube + SoundCloud (NewPipe, scraped)
```

Two things are worth understanding before reading the code.

**The backend never touches audio.** None of these services license server-side redistribution of their streams, so a backend that proxied audio would be infringing copyright. Instead `/api/v1/playback/ticket` returns a *playback ticket* — which SDK to use and what to pass it — and the client's SDK streams directly from the service under the user's own account. This is why playback needs a paid subscription on Spotify: we are not serving the audio, Spotify is.

**Imported playlists are copies.** The app never writes back to the origin service. This is enforced structurally: `MusicProvider` has no playlist-write method, so a provider cannot write back even by mistake. Editing an imported playlist changes your copy only.

## Requirements

**To run an installed build**, you need only:

- **Java 21+** on your PATH. The app checks for it at startup and shows a plain "install Java 21" dialog if it is missing or too old — nothing else has to be installed.

**To build or run from source**, you additionally need:

- **Maven 3.9+** and **Node 20+**

Spotify is the only service that needs credentials, and only if you want to use it:

- A **Spotify client ID** — free, from the [Spotify developer dashboard](https://developer.spotify.com/dashboard). The account that owns the app must have **Spotify Premium**: since March 2026 a Development Mode app on a free account gets 403 on every request. Register `http://127.0.0.1:8420/callback` as its redirect URI.

**YouTube and SoundCloud need no setup at all** — no API key, OAuth, or account. They are reached anonymously through NewPipe (see [How YouTube and SoundCloud work](#how-youtube-and-soundcloud-work)) and are usable the moment the app starts.

**Spotify playback needs Spotify Premium.** Free accounts can browse, import and search, but the Web Playback SDK will not play for them.

### Why Electron comes from a git URL

`packages/desktop` depends on [Castlabs' Electron for Content Security](https://github.com/castlabs/electron-releases) rather than stock Electron. Spotify's Web Playback SDK plays DRM-protected audio through EME, which needs a Widevine CDM — and stock Electron ships none, because Google does not license it for redistribution. Without it the SDK fails at startup with `Failed to initialize player` and no indication why. Castlabs' build fetches the CDM at runtime via Chromium's Component Updater; `main.js` waits for `components.whenReady()` before opening a window, since the SDK looks for a CDM as the page loads.

The builds are VMP-signed for development, so nothing extra is needed to run from source. Distributing a packaged app needs production signing through [castLabs' EVS](https://github.com/castlabs/electron-releases/wiki/EVS). On Linux, Widevine works but persistent licenses do not — irrelevant here, since nothing is downloaded for offline use.

**If `npm install` fails to fetch Electron** with `Host key verification failed` or `Could not read from remote repository`: npm rewrites GitHub dependencies to `git+ssh://` and needs SSH keys. Point git at HTTPS instead:

```bash
git config --global url."https://github.com/".insteadOf "git@github.com:"
```

**If Electron installs but has no binary** (`node_modules/electron/dist` is empty), your npm's `allowScripts` policy blocked its postinstall — that download *is* the postinstall. The root `package.json` allowlists it by exact version, so it needs updating whenever Electron is upgraded:

```bash
npm install-scripts approve electron
```

## Running it

On Linux you need a **freedesktop Secret Service** running — `gnome-keyring`, `ksecretd`, KWallet, or KeePassXC all provide one. The app stores your token-encryption key there and refuses to start without it, rather than writing the key in plaintext beside the database it protects.

Chromium only auto-detects a keychain on GNOME and KDE; on anything else (LXQt, XFCE, i3) it silently falls back to plaintext, so the app explicitly asks for the `gnome-libsecret` store. Despite the name that is the portable choice — it speaks the Secret Service API that every provider above implements. Override with `UP_PASSWORD_STORE` (`kwallet6`, `kwallet5`, `basic`, or `auto` to let Chromium choose).

```bash
npm install
npm run backend:build

export UP_SPOTIFY_CLIENT_ID=your-spotify-client-id   # optional; Spotify only

# Terminal 1: the UI dev server
npm run dev --workspace @unitedplaylists/core-ui

# Terminal 2: Electron, which starts the Java backend itself
npm run dev --workspace @unitedplaylists/desktop
```

Spotify with no client ID configured reports itself unavailable rather than failing when you try to connect it. YouTube and SoundCloud always work.

## Installing

Build a double-click installer for the current OS:

```bash
npm run dist          # installer for the host OS, in release/
npm run dist:linux    # AppImage + .deb
npm run dist:win      # NSIS .exe installer
npm run dist:mac      # dmg + zip
```

Each command builds the renderer and the backend jar first, then packages them with the Electron shell. The installed app bundles everything except Java: it expects **Java 21+** on the user's PATH (see [Requirements](#requirements)). Build each platform's installer on that platform (or in CI) — a macOS `.dmg` in particular cannot be produced on Linux or Windows.

Full details, including the Widevine VMP signing needed for Spotify playback in a distributed build, are in [PACKAGING.md](PACKAGING.md).

## Tests

```bash
npm run backend:test   # ~240 backend tests, JaCoCo report at backend/target/site/jacoco/
npm test               # 92 core-ui tests
npm run typecheck
node --test packages/desktop/src/*.test.js   # 16 Electron-shell tests
```

Live network probes (NewPipe against real YouTube/SoundCloud) are disabled in the suite and run by hand — see `NewPipeLiveProbeTest` and HANDOFF.md.

## Layout

```
backend/                     Spring Boot: metadata, playlists, tokens, search
  domain/                    Playlist, Track, TrackRef — local-only by construction
  provider/                  MusicProvider SPI: Spotify (OAuth) + YouTube/SoundCloud
                             (NewPipe, anonymous) + Apple Music (stub)
  service/                   Aggregated search, import, playback tickets, connections
packages/core-ui/            React app — wrapped by Electron now, Capacitor later
  api/                       Typed backend client
  player/                    Player facade + SpotifyAdapter / DirectAudioAdapter
packages/desktop/            Electron shell: backend supervisor, OAuth loopback,
                             Java 21 check
packaging/                   electron-builder afterPack hook (Widevine VMP signing)
```

Packaging is configured in the root `package.json` `build` field; see [PACKAGING.md](PACKAGING.md).

## Adding a streaming service

The provider abstraction is the point of the design. To add one:

1. Add a constant to `ProviderId`.
2. Implement `MusicProvider` (4 methods) and annotate it `@Component`. `ProviderRegistry` discovers it; aggregated search, import, and playback pick it up with no changes to their code.
3. Implement `OAuthClient` if the service uses OAuth.
4. Implement `PlayerAdapter` in `core-ui/src/player/` and pass it to the `Player`.

Nothing else changes. `ProviderRegistryTest` demonstrates this with a service invented inside the test.

## Security notes

- OAuth tokens are encrypted at rest with AES-256-GCM. The key lives in the OS keychain via Electron's `safeStorage`, never beside the database.
- The backend binds to loopback **and** requires a per-run shared secret. Loopback alone is not access control: any local process can reach it, and so can any website you visit, since browsers happily send requests to `127.0.0.1`. Cross-origin requests are rejected by exact host match — prefix matching is a bypass, as `http://127.0.0.1.evil.example` demonstrates.
- The refresh token never reaches the renderer. Only short-lived access tokens do, and only because the Spotify SDK requires one in the page.
- Sign-in happens in the real browser, not an embedded webview: the services block embedded sign-in, and an embedded window would put this app in a position to see your password.
- The renderer runs Spotify's and YouTube's SDK code, so it has context isolation, no Node integration, a strict CSP, and a preload exposing exactly four calls.

## How YouTube and SoundCloud work

These are reached through [NewPipeExtractor](https://github.com/TeamNewPipeExtractor) (GPL-3.0), which scrapes the sites rather than using an official API. The consequences, good and bad:

- **No API keys, no OAuth, no quota, no tokens to expire.** They are usable the moment the app starts — search and play with nothing set up.
- **Real audio streams.** The backend resolves a direct stream URL, played by a plain `<audio>` element. No embedded player, no DRM.
- **No account access.** Anonymous scraping cannot read your YouTube or SoundCloud playlists. Import a public or unlisted playlist by pasting its URL instead.
- **It breaks when the sites change — and updates itself.** Scraping is brittle; NewPipe ships fixes constantly. The app checks GitHub for a newer NewPipeExtractor release on startup and daily, downloads it to `<userData>/newpipe/`, and applies it on the next launch: the Electron shell puts the newest jar ahead of the bundled one on the classpath via Spring Boot's `loader.path`, so it shadows the compiled-in version with no rebuild. (A compile-time dependency can't be hot-swapped in a running JVM, hence "apply on next launch".) The Services screen shows the running version and whether an update is waiting; a fresh check is `POST /api/v1/extractor/check`. The bundled fallback is `newpipe.version` in `backend/pom.xml` (mirror it in `unitedplaylists.newpipe.bundled-version`). Disable with `unitedplaylists.newpipe.auto-update: false`.
- **It violates YouTube's and SoundCloud's terms of service**, and rules out distribution through an app store.

## Known limits

- **Spotify requires Premium, for everything.** Since 9 March 2026, apps in Development Mode only work if the account that owns the app has an active Premium subscription — this is not just about playback. On a free account every Web API call returns 403 with no explanation. See the [February 2026 migration guide](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide).
- **Spotify returns at most 10 search results.** That migration cut the search `limit` ceiling from 50 to 10 and the default from 20 to 5. More than 10 now means paginating with `offset`, at one request per 10 results.
- **YouTube, not YouTube Music.** NewPipe reaches YouTube proper, not the YouTube Music premium catalogue, which has no public API.
- **YouTube has no artist field.** The uploading channel is used as the artist, which is right for official artist channels and imprecise for compilation uploads.
- **Scraping is brittle.** YouTube and SoundCloud can break without warning when the sites change; the NewPipe auto-updater is the mitigation (see below), with a manual `newpipe.version` bump as the fallback.
- **Spotify playback needs Premium**, and free accounts get browsing and import only.
- **Re-importing creates a second copy** rather than overwriting, because your copy may hold edits worth more than an upstream rename.

## Roadmap

- **Apple Music.** Needs a paid Apple Developer membership for a MusicKit key, an ES256 developer token, and a Music User Token. The stub documents the shape.
- **Mobile.** `core-ui` is deliberately shell-agnostic so Capacitor can wrap it. The hard part is not the UI: mobile playback needs native Spotify and MusicKit SDK plugins, and a hosted backend becomes a real conversation at that point.
- **Gapless playback across services** — currently a swap between SDKs has an audible gap.
- **Distribution polish.** Installers build today, but for friction-free distribution they still need app icons, OS code-signing/notarization (unsigned builds trip SmartScreen/Gatekeeper), and Castlabs EVS production signing for Spotify playback. Tracked in [PACKAGING.md](PACKAGING.md).

## Licence

GPL-3.0-or-later. See [LICENSE](LICENSE).
