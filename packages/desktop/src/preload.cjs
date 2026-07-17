const { contextBridge, ipcRenderer } = require("electron");

/**
 * The renderer's entire view of the outside world.
 *
 * Four calls, no general capability. This matters more than usual here: the page
 * loads and runs Spotify's and YouTube's SDKs, so whatever is exposed on this
 * bridge is exposed to third-party code. Passing `ipcRenderer` through, or a
 * generic "invoke anything" function, would hand that code the run of the main
 * process.
 *
 * CommonJS because Electron preload scripts do not support ESM.
 */
contextBridge.exposeInMainWorld("unitedPlaylists", {
  /** The local backend's origin and shared secret, chosen at runtime. */
  getBackendInfo: () => ipcRenderer.invoke("backend:info"),

  /** A current Spotify access token, for the Web Playback SDK. */
  getSpotifyAccessToken: () => ipcRenderer.invoke("spotify:token"),

  /** Opens the service's sign-in page in the user's browser; resolves on callback. */
  authorize: (provider, url) => ipcRenderer.invoke("oauth:authorize", provider, url),

  /** Opens a link in the user's browser, for the developer-console links. */
  openExternal: (url) => ipcRenderer.invoke("shell:open-external", url),

  platform: process.platform,
});
