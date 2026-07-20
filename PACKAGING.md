UNITEDPLAYLISTS — PACKAGING & DISTRIBUTION
==========================================
How to turn the repo into an installer users can double-click. Dev-facing; read
HANDOFF.md first for the overall architecture.


0. WHAT A PACKAGED BUILD CONTAINS
---------------------------------
electron-builder assembles one installer per platform from three inputs:

  - the Electron shell        packages/desktop/src/**      (bundled into app.asar)
  - the renderer bundle       packages/core-ui/dist/**     (bundled into app.asar)
  - the backend jar           backend/target/backend-0.1.0-SNAPSHOT.jar
                              -> shipped as resources/backend.jar (outside the asar)

At runtime the shell resolves these exactly as the packaged layout provides them:
  - the renderer via  loadFile(../../core-ui/dist/index.html)  inside the asar
  - the backend jar via  join(process.resourcesPath, "backend.jar")
  - the data dir via  app.getPath("userData")   (see section 3)

It does NOT bundle a Java runtime. The app expects Java 21+ on the user's PATH and
checks for it at startup (section 2). Bundling a trimmed JRE is still possible later
— main.js already prefers resources/jre/bin/java if present — but is not required.


1. BUILDING INSTALLERS
----------------------
One command builds the renderer, the backend jar, and the installer for the host OS:

  npm run dist            # installer for whatever OS you're on
  npm run dist:linux      # AppImage + deb          -> release/
  npm run dist:win        # NSIS .exe installer      -> release/
  npm run dist:mac        # dmg + zip                -> release/
  npm run dist:dir        # unpacked app only (fast; for smoke-testing the layout)

Output lands in release/ (gitignored). The prep step (dist:prepare) runs
`npm run build --workspace @unitedplaylists/core-ui` and `npm run backend:build`, so
you do not need to build those by hand first.

CROSS-COMPILATION IS LIMITED — build each OS's installer on that OS (or in CI):
  - Linux artifacts: build on Linux.  ✅ verified here (AppImage + deb produced).
  - Windows .exe:    build on Windows. electron-builder CAN build it on Linux via a
                     downloaded Wine, but that path is flaky; prefer a real Windows
                     box or a windows-latest CI runner.
  - macOS dmg:       build on macOS. A dmg CANNOT be produced on Linux/Windows — it
                     needs macOS-only tooling (hdiutil). Use a macos-latest runner.

The Castlabs Electron binary is reused from node_modules/electron/dist (configured as
`electronDist`), so electron-builder does not re-download Electron. If node_modules/
electron/dist is empty after an install, run `npm install-scripts approve electron`
(see HANDOFF hiccup #3).


2. THE JAVA 21 REQUIREMENT
--------------------------
The shell verifies Java before spawning the backend (main.js `verifyJava`, logic in
packages/desktop/src/java-runtime.js):

  - no `java` on PATH   -> a dialog telling the user to install Java 21 (Temurin).
  - java older than 21  -> a dialog naming the detected version.
  - java 21+            -> proceeds.

This turns the otherwise-opaque failure ("backend exited before it was ready") into a
plain instruction. The pure version logic is unit-tested in java-runtime.test.js.

If you later bundle a JRE, drop it at resources/jre (e.g. via electron-builder
`extraResources` running `jlink`/`jpackage --type app-image`); resolveJavaPath picks
it up and verifyJava is skipped for it.


3. WHERE USER DATA LIVES (per platform)
---------------------------------------
The shell sets the backend's data dir to Electron's userData path, which is the
OS-native per-user application-data location:

  Windows   %APPDATA%\UnitedPlaylists              (C:\Users\<you>\AppData\Roaming\…)
  macOS     ~/Library/Application Support/UnitedPlaylists
  Linux     ~/.config/UnitedPlaylists               (or $XDG_CONFIG_HOME)

That directory holds the H2 database (encrypted OAuth tokens), token.key, and the
newpipe/ extractor cache. It is passed to the backend as UP_DATA_DIR; the backend's
application.yml only falls back to ~/.unitedplaylists when run standalone without the
shell. The token-encryption key itself lives in the OS keychain (Keychain / DPAPI /
Secret Service), never on disk in the clear — see HANDOFF section 3 (SECURITY).

Nothing is written next to the installed program, so the app works when installed to a
read-only location and uninstalls cleanly.


4. SPOTIFY PLAYBACK IN A DISTRIBUTED BUILD (Widevine VMP signing)
-----------------------------------------------------------------
Spotify's Web Playback SDK needs a VMP-signed Widevine binary (HANDOFF hiccup #2).
Dev builds are signed with Castlabs' development key automatically; a DISTRIBUTED
build must be signed with a production key via Castlabs' EVS service.

Two pieces run automatically during `npm run dist*`:
  - dist:sign:ensure (scripts/setup-evs.mjs --ensure) runs BEFORE electron-builder and
    refreshes the cached EVS session (or logs in from UP_EVS_* env vars) so a valid
    token exists when signing happens. Best-effort: no session → a warning, build
    continues unsigned.
  - packaging/afterPack.cjs then VMP-signs the packaged app. Also best-effort: if the
    castlabs-evs tool or account is missing it warns and continues. The installer still
    works — YouTube and SoundCloud play; only Spotify playback is disabled in that build.
  - Set UP_VMP_REQUIRED=1 to make a signing failure fail the build instead.

To enable it, set EVS up ONCE on the machine, then just build:
  npm run setup:evs                       # installs castlabs-evs, then ASKS whether to
                                          #   log in or create an account — no existing
                                          #   account is assumed
  npm run dist                            # auto-refreshes the session and signs

  # Skip the prompt with an explicit mode; supply credentials via the environment:
  npm run setup:evs -- --login                        # log into an existing account
  npm run setup:evs -- --signup                       # create one (prompts for the code)
  npm run setup:evs -- --signup --confirm <code>      # CI: finish signup with the code
  UP_EVS_ACCOUNT=… UP_EVS_PASSWORD=… npm run setup:evs -- --login   # non-interactive (CI)

  # Under the hood setup:evs wraps these; you can still run them by hand:
  pip install --upgrade castlabs-evs
  python -m castlabs_evs.account reauth   # log into an existing EVS account
  UP_VMP_REQUIRED=1 npm run dist          # signs release/**/<app>, failing if it can't


5. STILL TO POLISH (not blockers)
---------------------------------
  - App icons: no custom icon yet, so builds use the default Electron logo (harmless
    warning). Drop a 512×512+ packaging/icon.png (electron-builder derives the rest).
  - macOS signing/notarization: unsigned mac builds trip Gatekeeper ("can't be
    opened"). Real mac distribution needs a Developer ID cert + notarization
    (`mac.notarize`), which requires a paid Apple Developer account.
  - Windows signing: unsigned .exe installers show a SmartScreen warning. An
    Authenticode cert removes it.
  - Auto-update: electron-builder writes app-update.yml, but no update feed is
    configured. Wire electron-updater + a release host if you want in-app updates.
