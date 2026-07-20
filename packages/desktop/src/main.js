import electron, { app, BrowserWindow, ipcMain, shell, safeStorage } from "electron";

/**
 * Castlabs' Electron for Content Security adds this; stock Electron has no such
 * export.
 *
 * <p>Read off the default export rather than imported by name, because a named
 * import of something stock Electron does not export fails at module load — the app
 * would not start at all, rather than starting without playback.
 */
const components = electron.components;
import { randomBytes } from "node:crypto";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { existsSync, readdirSync } from "node:fs";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { BackendProcess } from "./backend.js";
import { awaitOAuthCallback } from "./oauth-callback.js";
import { parseJavaMajor, javaMissingMessage, javaVersionProblem } from "./java-runtime.js";

const __dirname = dirname(fileURLToPath(import.meta.url));

/**
 * Must match the redirect URI registered in each service's dashboard, which is why
 * this one port is fixed while the backend's is not.
 */
const OAUTH_PORT = 8420;
const OAUTH_REDIRECT = `http://127.0.0.1:${OAUTH_PORT}/callback`;

const isDev = !app.isPackaged;

/** @type {BackendProcess | null} */
let backend = null;
/** @type {BrowserWindow | null} */
let mainWindow = null;
/** @type {BrowserWindow | null} */
let splashWindow = null;

/**
 * Tells Chromium which Linux keychain to use.
 *
 * Chromium picks its password store from `XDG_CURRENT_DESKTOP`, recognising only
 * GNOME and KDE. On anything else — LXQt, XFCE, i3, a bare WM — it silently falls
 * back to `basic_text`, which is not encryption at all: it stores the key in
 * plaintext. `safeStorage.isEncryptionAvailable()` then reports false and the app
 * refuses to start, which is correct but looks like a bug on a machine that has a
 * perfectly good keychain running.
 *
 * `gnome-libsecret` is the right default despite the name: it speaks the
 * freedesktop Secret Service API, which gnome-keyring, ksecretd, KWallet's secrets
 * interface, and KeePassXC all implement. It is the portable choice, not the GNOME
 * one.
 *
 * Override with `UP_PASSWORD_STORE` if a system needs something else.
 */
function selectLinuxPasswordStore() {
  if (process.platform !== "linux") {
    return;
  }
  const store = process.env.UP_PASSWORD_STORE ?? "gnome-libsecret";
  if (store !== "auto") {
    app.commandLine.appendSwitch("password-store", store);
  }
}

// Must run before the app is ready: Chromium reads this switch during startup.
selectLinuxPasswordStore();

/**
 * Names the app, and with it the directory holding the database and key.
 *
 * Without this, Electron derives the path from the package name — which is scoped,
 * so it produces `~/.config/@unitedplaylists/desktop/`: a stray `@` directory, a
 * pointless nesting level, and a location nobody would guess held their library.
 * Set explicitly rather than left to `productName`, so the path is decided here and
 * cannot drift when the package is renamed.
 */
app.setName("United.Playlists");
app.setPath("userData", join(app.getPath("appData"), "UnitedPlaylists"));

/** Explains a missing keychain in terms of what to actually do about it. */
function keychainHelp(problem) {
  const store = process.env.UP_PASSWORD_STORE ?? "gnome-libsecret";
  return process.platform === "linux"
    ? `No OS keychain is reachable, so ${problem}.\n\n` +
        `UnitedPlaylists asked Chromium for the "${store}" password store, which needs a ` +
        "freedesktop Secret Service running — gnome-keyring, ksecretd, KWallet, or KeePassXC " +
        "all provide one.\n\n" +
        "Install or start one, then try again. If your system uses a different store, set " +
        "UP_PASSWORD_STORE (e.g. kwallet6, kwallet5, basic) or UP_PASSWORD_STORE=auto to let " +
        "Chromium choose."
    : `No OS keychain is available, so ${problem}.`;
}

/**
 * Loads the database encryption key, creating one on first run.
 *
 * Encrypted with Electron's safeStorage, which is backed by the OS keychain
 * (Keychain on macOS, DPAPI on Windows, the Secret Service on Linux). Without one
 * the key would land in a plain file next to the database it protects, which is no
 * protection at all — so that case fails loudly instead.
 */
async function loadOrCreateTokenKey() {
  const keyPath = join(app.getPath("userData"), "token.key");

  if (!safeStorage.isEncryptionAvailable()) {
    throw new Error(keychainHelp("your streaming service tokens cannot be protected"));
  }

  if (existsSync(keyPath)) {
    try {
      return safeStorage.decryptString(await readFile(keyPath));
    } catch (cause) {
      // safeStorage keys its encryption on the OS keychain entry, which is named
      // after the app. Renaming the app (app.setName) rotates that entry, so a
      // token.key written under the old name can no longer be decrypted. There is no
      // recovering the old key, so a fresh one is generated below. The service tokens
      // in the database were encrypted with the old key and become unreadable — the
      // backend already handles that by skipping them and prompting a reconnect, so
      // the cost is re-signing into Spotify, not a broken app.
      console.warn(
        `[keychain] Could not decrypt the saved key (${cause.message ?? cause}). ` +
          "This usually means the app name changed. Generating a new key; you will " +
          "need to reconnect Spotify. YouTube and SoundCloud are unaffected.",
      );
    }
  }

  return createAndStoreTokenKey(keyPath);
}

async function createAndStoreTokenKey(keyPath) {
  const key = randomBytes(32).toString("base64");
  await mkdir(dirname(keyPath), { recursive: true });
  await writeFile(keyPath, safeStorage.encryptString(key), { mode: 0o600 });
  return key;
}

const execFileAsync = promisify(execFile);

function resolveJavaPath() {
  // A packaged build may ship a bundled JRE under resources/jre; if present it is
  // used so the machine needs no Java installed. Otherwise the app relies on a
  // system Java on PATH (verified by verifyJava below).
  const bundled = join(process.resourcesPath ?? "", "jre", "bin", process.platform === "win32" ? "java.exe" : "java");
  return !isDev && existsSync(bundled) ? bundled : "java";
}

/**
 * Confirms a usable Java is on PATH before the backend is spawned.
 *
 * When no JRE is bundled the app depends on the user's own Java. A missing or
 * too-old runtime otherwise surfaces as the backend "exiting before it was ready"
 * with an opaque JVM error; this turns it into a plain instruction to install
 * Java 21. The version arithmetic and wording live in java-runtime.js so they can
 * be tested without Electron.
 *
 * @param {string} javaPath
 */
async function verifyJava(javaPath) {
  // A bundled JRE is validated at build time, not here.
  if (javaPath !== "java") {
    return;
  }

  let output;
  try {
    // `java -version` prints to stderr on every JDK; capture both streams.
    const result = await execFileAsync(javaPath, ["-version"]);
    output = `${result.stdout}${result.stderr}`;
  } catch (cause) {
    if (cause?.code === "ENOENT") {
      throw new Error(javaMissingMessage());
    }
    throw new Error(`Could not run Java: ${cause?.message ?? cause}`);
  }

  const major = parseJavaMajor(output);
  const problem = javaVersionProblem(major);
  if (problem) {
    throw new Error(problem);
  }
  if (major === null) {
    // Unrecognised output, but java ran — let the backend try rather than block on
    // a parsing quirk.
    console.warn(`[java] could not parse version from: ${output.trim()}`);
    return;
  }
  console.log(`[java] using system Java ${major} (${javaPath})`);
}

function resolveJarPath() {
  return isDev
    ? join(__dirname, "..", "..", "..", "backend", "target", "backend-0.1.0-SNAPSHOT.jar")
    : join(process.resourcesPath ?? "", "backend.jar");
}

/**
 * The newest NewPipe extractor jar the backend has downloaded, if any.
 *
 * The backend fetches updates into this directory; the launcher applies them by
 * putting the newest one on the classpath ahead of the bundled version. Applying at
 * launch rather than in the running JVM is deliberate: a compile-time dependency
 * cannot be hot-swapped, and an extractor update is fine to pick up next session.
 */
function newestCachedExtractor(dataDir) {
  const dir = join(dataDir, "newpipe");
  if (!existsSync(dir)) {
    return undefined;
  }
  const versionOf = (name) => {
    const m = /(\d+)\.(\d+)\.(\d+)/.exec(name);
    return m ? Number(m[1]) * 1e6 + Number(m[2]) * 1e3 + Number(m[3]) : -1;
  };
  const jars = readdirSync(dir)
    .filter((name) => name.endsWith(".jar"))
    .sort((a, b) => versionOf(b) - versionOf(a));
  return jars.length > 0 ? join(dir, jars[0]) : undefined;
}

async function startBackend() {
  const javaPath = resolveJavaPath();
  await verifyJava(javaPath);

  const tokenKey = await loadOrCreateTokenKey();
  const dataDir = app.getPath("userData");

  const loaderPath = newestCachedExtractor(dataDir);
  if (loaderPath) {
    console.log(`[backend] using downloaded NewPipe extractor: ${loaderPath}`);
  }

  backend = new BackendProcess({
    javaPath,
    jarPath: resolveJarPath(),
    dataDir,
    tokenKey,
    loaderPath,
    extraEnv: {
      UP_REDIRECT_URI: OAUTH_REDIRECT,
      // Supplied by the packager or the developer's environment. Absent, Spotify
      // reports itself unavailable rather than failing at connect time. YouTube and
      // SoundCloud need nothing here — they are scraped anonymously.
      UP_SPOTIFY_CLIENT_ID: process.env.UP_SPOTIFY_CLIENT_ID ?? "",
    },
  });

  return backend.start();
}

/**
 * A frameless, transparent splash shown the moment the app starts, so a user
 * launching the packaged build sees the lockup (with its pulsing aura) instead
 * of a blank screen while the Java backend spawns. Closed once the main window
 * is ready to show. Best-effort: any failure here must never block startup.
 */
function createSplash() {
  try {
    splashWindow = new BrowserWindow({
      width: 920,
      height: 400,
      frame: false,
      transparent: true,
      backgroundColor: "#00000000",
      hasShadow: false,
      resizable: false,
      movable: false,
      minimizable: false,
      maximizable: false,
      skipTaskbar: true,
      focusable: false,
      alwaysOnTop: true,
      center: true,
      show: false,
      webPreferences: { contextIsolation: true, nodeIntegration: false },
    });
    splashWindow.once("ready-to-show", () => splashWindow?.show());
    void splashWindow.loadFile(join(__dirname, "assets", "splash.html"));
  } catch (cause) {
    console.warn("[splash] could not open splash window:", cause);
    splashWindow = null;
  }
}

function closeSplash() {
  splashWindow?.destroy();
  splashWindow = null;
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 760,
    minWidth: 900,
    minHeight: 560,
    backgroundColor: "#0f1115",
    // Window/taskbar icon for the running app. macOS and Windows take their icon
    // from the packaged bundle/exe instead, but this drives Linux and dev runs.
    icon: join(__dirname, "assets", "icon.png"),
    show: false,
    // Hide the menu bar in packaged builds — the app has its own in-window nav, so the
    // default File/Edit/View bar is just chrome. Kept in dev, where its View menu and
    // DevTools shortcuts are useful. (removeMenu below makes this permanent on
    // Windows/Linux; macOS keeps its global menu, so Quit etc. still work.)
    autoHideMenuBar: !isDev,
    webPreferences: {
      preload: join(__dirname, "preload.cjs"),
      // The renderer runs Spotify's and YouTube's SDK code. Context isolation and
      // no node integration mean a compromise there cannot reach the filesystem or
      // spawn processes; it gets only the four calls the preload exposes.
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
      webSecurity: true,
    },
  });

  // Drop the window menu bar entirely in packaged builds (Windows/Linux). Left in dev
  // so the toolbar — and its DevTools entries — stay available while developing.
  if (!isDev) {
    mainWindow.removeMenu();
  }

  mainWindow.once("ready-to-show", () => {
    closeSplash();
    mainWindow?.show();
  });

  // Renderer console goes to the terminal in dev. Without this, a failure in the
  // page is invisible unless DevTools happens to be open — which is how a broken
  // CORS setup once looked like a backend that would not start.
  if (isDev) {
    mainWindow.webContents.on("console-message", (_event, level, message) => {
      const label = ["debug", "info", "warning", "error"][level] ?? "log";
      console.log(`[renderer:${label}] ${message}`);
    });
    mainWindow.webContents.on("did-fail-load", (_event, code, description, url) => {
      console.error(`[renderer] failed to load ${url}: ${description} (${code})`);
    });
  }

  // Anything trying to open a new window goes to the real browser instead. Without
  // this, a link in SDK-rendered content could open a chromeless Electron window
  // that the user cannot tell apart from a real browser.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url);
    return { action: "deny" };
  });

  if (isDev) {
    void mainWindow.loadURL("http://localhost:5173");
  } else {
    void mainWindow.loadFile(join(__dirname, "..", "..", "core-ui", "dist", "index.html"));
  }

  // Backstops so the splash can never outlive startup: a load that finishes
  // without firing ready-to-show, or the window being closed outright.
  mainWindow.webContents.once("did-finish-load", closeSplash);
  mainWindow.on("closed", () => {
    closeSplash();
    mainWindow = null;
  });
}

function registerIpc() {
  ipcMain.handle("backend:info", () => backend?.getInfo() ?? null);

  /**
   * Runs the sign-in flow: open the service's page in the real browser, wait for
   * the loopback callback.
   */
  ipcMain.handle("oauth:authorize", async (_event, _provider, url) => {
    const callback = awaitOAuthCallback({ port: OAUTH_PORT, path: "/callback" });
    await shell.openExternal(url);
    return callback;
  });

  /**
   * Opens a developer-console link in the user's browser.
   *
   * Restricted to https. The renderer runs Spotify's and YouTube's SDK code, so this
   * handler is reachable by third-party JavaScript; handing it an unrestricted
   * openExternal would let that code launch `file://`, or a registered protocol
   * handler, on the user's machine. Only the schemes a documentation link could
   * legitimately need are allowed.
   */
  ipcMain.handle("shell:open-external", async (_event, url) => {
    let parsed;
    try {
      parsed = new URL(String(url));
    } catch {
      throw new Error("Not a valid URL");
    }
    if (parsed.protocol !== "https:") {
      throw new Error(`Refusing to open a ${parsed.protocol} link`);
    }
    await shell.openExternal(parsed.toString());
  });

  /**
   * Hands the Spotify SDK a token.
   *
   * Proxied through the backend rather than kept here, so the renderer never holds
   * a refresh token and the backend stays the only component that decrypts one.
   */
  ipcMain.handle("spotify:token", async () => {
    const info = backend?.getInfo();
    if (!info) {
      throw new Error("Backend is not running");
    }
    const response = await fetch(`${info.baseUrl}/api/v1/connections/SPOTIFY/access-token`, {
      headers: { "X-UnitedPlaylists-Token": info.token },
    });
    if (!response.ok) {
      throw new Error("Could not get a Spotify token. Reconnect Spotify in Services.");
    }
    const body = await response.json();
    return body.accessToken;
  });
}

/**
 * Installs the Widevine CDM and waits for it.
 *
 * Spotify's Web Playback SDK plays DRM-protected audio through EME, so without a
 * Widevine CDM it fails at `initialize` with "Failed to initialize player" and no
 * hint as to why. Stock Electron ships no CDM at all — Google does not license it
 * for redistribution — which is why this app uses Castlabs' build, where the CDM is
 * fetched at runtime by the Component Updater Service.
 *
 * This must finish before any window opens: the SDK checks for a CDM when the page
 * loads, and a CDM that arrives afterwards is too late.
 */
async function initialiseWidevine() {
  if (!components) {
    console.warn(
      "[widevine] This is not the Castlabs Electron build, so there is no Widevine " +
        "CDM. Spotify playback will fail; YouTube will still work.",
    );
    return;
  }
  try {
    await components.whenReady();
    console.log("[widevine] ready:", JSON.stringify(components.status()));
  } catch (cause) {
    // Not fatal. Everything except Spotify playback still works, and a music app
    // that refuses to open is worse than one that cannot play one service.
    console.error("[widevine] failed to initialise; Spotify playback will not work:", cause);
  }
}

app.whenReady().then(async () => {
  // Up front, before the slow work (Widevine + spawning the backend), so there is
  // immediate visual feedback that the app is launching.
  createSplash();

  await initialiseWidevine();

  try {
    await startBackend();
  } catch (cause) {
    closeSplash();
    const { dialog } = await import("electron");
    dialog.showErrorBox("UnitedPlaylists could not start", String(cause.message ?? cause));
    app.quit();
    return;
  }
  registerIpc();
  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});

// The backend is our child process; leaving it running after the app exits would
// strand a JVM holding the database open.
app.on("before-quit", async (event) => {
  if (backend?.isRunning) {
    event.preventDefault();
    await backend.stop();
    backend = null;
    app.quit();
  }
});
