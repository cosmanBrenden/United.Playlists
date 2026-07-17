"use strict";

/**
 * electron-builder afterPack hook: VMP-sign the packaged app for Widevine.
 *
 * Spotify's Web Playback SDK plays DRM audio through a Widevine CDM. Castlabs'
 * "Electron for Content Security" build carries the CDM, but Google's Verified
 * Media Path requires the shipping binary to be VMP-signed. A dev build is signed
 * with castLabs' development key automatically; a DISTRIBUTED build must be signed
 * with a production key via castLabs' EVS (Electron Version Signing) service. An
 * unsigned distribution still runs — YouTube and SoundCloud play normally — but
 * Spotify playback fails with "Failed to initialize player".
 *
 * Signing needs the `castlabs-evs` Python package and an EVS account (create one
 * with `python -m castlabs_evs.account`). Because that account is not available in
 * every environment, this hook is best-effort by default: it signs when it can and
 * warns when it cannot, so a build without EVS credentials still produces a working
 * installer. Set UP_VMP_REQUIRED=1 to make a signing failure fail the build.
 *
 * @param {import("electron-builder").AfterPackContext} context
 */
exports.default = async function afterPack(context) {
  const { spawnSync } = require("node:child_process");

  const required = process.env.UP_VMP_REQUIRED === "1";
  const python = process.env.PYTHON ?? "python3";
  const appDir = context.appOutDir;

  const fail = (message) => {
    if (required) {
      throw new Error(`[vmp] ${message}`);
    }
    console.warn(
      `[vmp] ${message}\n` +
        "[vmp] Continuing without VMP signing. YouTube and SoundCloud will work; " +
        "Spotify playback will not in this build. Set UP_VMP_REQUIRED=1 to make this fatal.",
    );
  };

  // Is the castlabs EVS tool present at all?
  const probe = spawnSync(python, ["-m", "castlabs_evs.vmp", "--help"], {
    stdio: "ignore",
  });
  if (probe.status !== 0) {
    fail(
      "castlabs-evs is not installed, so the app was not VMP-signed. " +
        "Install it with `pip install --upgrade castlabs-evs` and create an account " +
        "with `python -m castlabs_evs.account` to enable Spotify playback in packaged builds.",
    );
    return;
  }

  console.log(`[vmp] signing ${appDir} for Widevine (VMP)…`);
  const sign = spawnSync(python, ["-m", "castlabs_evs.vmp", "sign-pkg", appDir], {
    stdio: "inherit",
  });
  if (sign.status !== 0) {
    fail(`VMP signing exited with code ${sign.status}.`);
    return;
  }
  console.log("[vmp] VMP signing complete.");
};
