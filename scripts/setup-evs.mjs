#!/usr/bin/env node
// @ts-check
"use strict";

/**
 * One-command setup for Castlabs EVS (Electron VMP signing), the thing a packaged
 * build needs so Spotify playback works — see PACKAGING.md §4 and README.
 *
 * Spotify's Web Playback SDK plays DRM audio through Widevine, and Google's Verified
 * Media Path requires the shipping binary to be VMP-signed with a *production* key.
 * That signing (packaging/afterPack.cjs, run during `npm run dist`) needs the
 * `castlabs-evs` Python tool AND a logged-in EVS account. On a fresh machine that was
 * a pile of manual steps; this script does them:
 *
 *   1. finds a usable Python,
 *   2. installs / upgrades the `castlabs-evs` pip package,
 *   3. authenticates the EVS account so afterPack can sign, and
 *   4. verifies the signer is ready.
 *
 * USAGE
 *   node scripts/setup-evs.mjs                 # log in an existing account (reauth)
 *   node scripts/setup-evs.mjs --signup        # create a new account, then confirm
 *   node scripts/setup-evs.mjs --signup --confirm 123456   # confirm with an emailed code
 *   node scripts/setup-evs.mjs --skip-install  # don't touch pip, just authenticate
 *   node scripts/setup-evs.mjs --ensure        # best-effort refresh, used by `npm run dist`
 *
 * The `--ensure` mode is what the dist scripts call before packaging: it refreshes the
 * cached EVS session (or logs in non-interactively if UP_EVS_ACCOUNT/PASSWORD are set)
 * so that packaging/afterPack.cjs finds a valid session and signs. It NEVER fails the
 * build — if EVS is not installed or not logged in, it prints a hint and exits 0, and
 * the build proceeds unsigned (YouTube/SoundCloud still play; Spotify playback does
 * not). Run `npm run setup:evs` once, interactively, to establish the session it
 * refreshes.
 *
 * Credentials come from the environment (recommended) or, if absent, the tool prompts:
 *   UP_EVS_ACCOUNT     EVS account name
 *   UP_EVS_PASSWORD    EVS password   (see the note below about process listings)
 *   UP_EVS_EMAIL       email          (signup only)
 *   UP_EVS_FIRST_NAME  first name     (signup only)
 *   UP_EVS_LAST_NAME   last name      (signup only)
 *   PYTHON             python executable to use (default: python3, then python)
 *
 * SECURITY NOTE: passing UP_EVS_PASSWORD makes the login non-interactive, but the
 * password is handed to the tool as a `-P` argument, which is briefly visible in the
 * process list. On a shared machine, leave UP_EVS_PASSWORD unset and let the tool
 * prompt for it (it reads the password without echoing).
 *
 * This is best-effort by design, exactly like afterPack: a build with no EVS account
 * still produces a working installer — YouTube and SoundCloud play, only Spotify
 * playback is disabled in that build.
 */

import { spawnSync } from "node:child_process";

const args = new Set(process.argv.slice(2));
if (args.has("--help") || args.has("-h")) {
  printUsageAndExit(0);
}

const SIGNUP = args.has("--signup");
const SKIP_INSTALL = args.has("--skip-install");
const ENSURE = args.has("--ensure");
const confirmCode = readFlagValue("--confirm");

/** Prints a message and exits non-zero. */
function die(message) {
  console.error(`\n[evs] ${message}`);
  process.exit(1);
}

/** Reads `--flag value` from argv, or null if the flag is absent. */
function readFlagValue(flag) {
  const argv = process.argv.slice(2);
  const i = argv.indexOf(flag);
  return i !== -1 && i + 1 < argv.length ? argv[i + 1] : null;
}

function printUsageAndExit(code) {
  console.log(
    [
      "Set up Castlabs EVS so packaged builds can be VMP-signed for Spotify playback.",
      "",
      "  node scripts/setup-evs.mjs               log in an existing EVS account",
      "  node scripts/setup-evs.mjs --signup      create a new account",
      "  node scripts/setup-evs.mjs --signup --confirm <code>   confirm a new account",
      "  node scripts/setup-evs.mjs --skip-install               skip the pip step",
      "",
      "Credentials via env: UP_EVS_ACCOUNT, UP_EVS_PASSWORD (+ UP_EVS_EMAIL,",
      "UP_EVS_FIRST_NAME, UP_EVS_LAST_NAME for --signup). PYTHON overrides the",
      "interpreter. See the header of this file for details.",
    ].join("\n"),
  );
  process.exit(code);
}

/** Runs a command, inheriting stdio so prompts and output reach the user. */
function run(cmd, cmdArgs, { quiet = false } = {}) {
  return spawnSync(cmd, cmdArgs, { stdio: quiet ? "ignore" : "inherit" });
}

/**
 * Finds a Python that runs. Tries $PYTHON, then python3, then python — the last for
 * Windows, where python3 is often absent.
 */
function resolvePython() {
  const candidates = [process.env.PYTHON, "python3", "python"].filter(Boolean);
  for (const candidate of candidates) {
    const probe = spawnSync(candidate, ["--version"], { stdio: "ignore" });
    if (probe.status === 0) {
      return candidate;
    }
  }
  die(
    "No working Python found. Install Python 3 (python.org or your package manager) " +
      "and re-run, or set PYTHON to its path.",
  );
  return ""; // unreachable; keeps the type checker happy
}

/** True if `castlabs_evs` is already importable by this Python. */
function evsInstalled(python) {
  return run(python, ["-c", "import castlabs_evs"], { quiet: true }).status === 0;
}

/** pip-installs castlabs-evs, tolerating PEP 668 "externally managed" environments. */
function installEvs(python) {
  console.log("[evs] installing/upgrading castlabs-evs …");
  let res = run(python, ["-m", "pip", "install", "--upgrade", "castlabs-evs"]);
  if (res.status !== 0) {
    // A system Python may refuse a global install (PEP 668). Retry into the user site.
    console.log("[evs] retrying with --user …");
    res = run(python, ["-m", "pip", "install", "--user", "--upgrade", "castlabs-evs"]);
  }
  if (res.status !== 0) {
    die(
      "Could not install castlabs-evs with pip. Install it by hand " +
        "(`pip install --upgrade castlabs-evs`, or via pipx/venv) and re-run with " +
        "--skip-install.",
    );
  }
}

/** Builds the account subcommand args, appending credential flags that are present. */
function withCreds(subcommand, { includeSignupFields = false } = {}) {
  const out = [subcommand];
  const account = process.env.UP_EVS_ACCOUNT;
  const password = process.env.UP_EVS_PASSWORD;
  const email = process.env.UP_EVS_EMAIL;
  const first = process.env.UP_EVS_FIRST_NAME;
  const last = process.env.UP_EVS_LAST_NAME;
  if (account) out.push("-A", account);
  if (password) out.push("-P", password);
  if (includeSignupFields) {
    if (email) out.push("-E", email);
    if (first) out.push("-F", first);
    if (last) out.push("-L", last);
  }
  return out;
}

/** Whether both account + password are in the environment (enables non-interactive). */
function haveNonInteractiveCreds() {
  return Boolean(process.env.UP_EVS_ACCOUNT && process.env.UP_EVS_PASSWORD);
}

/**
 * Best-effort session refresh for the dist pipeline. Never exits non-zero: a machine
 * without EVS just builds unsigned. Returns nothing; its only job is a side effect
 * (a fresh token) so afterPack can sign.
 */
function ensureForDist() {
  const python = firstWorkingPython();
  if (!python) {
    warnUnsigned("no Python found to run the EVS signer");
    return;
  }
  if (!evsInstalled(python)) {
    warnUnsigned("castlabs-evs is not installed — run `npm run setup:evs` once to enable signing");
    return;
  }
  // If creds are in the env, log in fresh; otherwise refresh whatever session is cached.
  const nonInteractive = haveNonInteractiveCreds();
  const argv = nonInteractive
    ? ["-m", "castlabs_evs.account", "-n", ...withCreds("reauth")]
    : ["-m", "castlabs_evs.account", "refresh"];
  const res = run(python, argv, { quiet: true });
  if (res.status === 0) {
    console.log("[evs] session refreshed — this build will be VMP-signed for Spotify playback.");
  } else {
    warnUnsigned(
      "could not refresh the EVS session (not logged in?) — run `npm run setup:evs` to log in",
    );
  }
}

/** Prints the standard "building without signing" warning; used only in --ensure mode. */
function warnUnsigned(reason) {
  console.warn(
    `[evs] ${reason}.\n` +
      "[evs] Building WITHOUT VMP signing: YouTube and SoundCloud will play, Spotify " +
      "playback will not in this build.",
  );
}

/** Like resolvePython but returns null instead of exiting — for best-effort --ensure. */
function firstWorkingPython() {
  const candidates = [process.env.PYTHON, "python3", "python"].filter(Boolean);
  for (const candidate of candidates) {
    if (spawnSync(candidate, ["--version"], { stdio: "ignore" }).status === 0) {
      return candidate;
    }
  }
  return null;
}

function main() {
  if (ENSURE) {
    ensureForDist();
    return;
  }

  const python = resolvePython();
  console.log(`[evs] using Python: ${python}`);

  if (SKIP_INSTALL) {
    if (!evsInstalled(python)) {
      die("--skip-install was given but castlabs-evs is not importable by " + python);
    }
  } else if (!evsInstalled(python) || args.has("--force-install")) {
    installEvs(python);
  } else {
    console.log("[evs] castlabs-evs already installed; upgrading anyway …");
    installEvs(python);
  }

  // `-n` puts the account tool in non-interactive mode; only safe when we can supply
  // every answer it would otherwise prompt for.
  const nonInteractive = haveNonInteractiveCreds();
  const base = [python, "-m", "castlabs_evs.account"];
  const modeFlag = nonInteractive ? ["-n"] : [];

  if (SIGNUP) {
    console.log("[evs] creating a new EVS account …");
    const signup = run(base[0], [
      ...base.slice(1),
      ...modeFlag,
      ...withCreds("signup", { includeSignupFields: true }),
    ]);
    if (signup.status !== 0) {
      die("Signup failed. Check the output above.");
    }
    // EVS emails a confirmation code. If the caller already has it, confirm now;
    // otherwise tell them how to finish.
    if (confirmCode) {
      const confirm = run(base[0], [
        ...base.slice(1),
        ...modeFlag,
        ...withCreds("confirm-signup"),
        "-C",
        confirmCode,
      ]);
      if (confirm.status !== 0) {
        die("Confirmation failed. Re-run with the correct --confirm <code>.");
      }
    } else {
      console.log(
        "\n[evs] Account created. Check your email for the confirmation code, then run:\n" +
          "        node scripts/setup-evs.mjs --signup --confirm <code>\n" +
          "      (or just `npm run setup:evs` once confirmed, to verify).",
      );
      return;
    }
  } else {
    console.log(
      nonInteractive
        ? "[evs] authenticating EVS account (non-interactive) …"
        : "[evs] authenticating EVS account (you may be prompted) …",
    );
    const reauth = run(base[0], [...base.slice(1), ...modeFlag, ...withCreds("reauth")]);
    if (reauth.status !== 0) {
      die(
        "Authentication failed. Set UP_EVS_ACCOUNT and UP_EVS_PASSWORD (or run " +
          "without them to be prompted), or use --signup to create an account first.",
      );
    }
  }

  // Confirm the signer itself is now usable — this is what afterPack.cjs invokes.
  const vmpOk = run(python, ["-m", "castlabs_evs.vmp", "--help"], { quiet: true });
  if (vmpOk.status !== 0) {
    die("castlabs-evs installed but the VMP signer did not run. See the output above.");
  }

  console.log(
    "\n[evs] ✔ EVS is set up. Packaged builds will now be VMP-signed for Spotify playback.\n" +
      "      Build one with:  UP_VMP_REQUIRED=1 npm run dist\n" +
      "      (UP_VMP_REQUIRED=1 makes a signing failure fail the build, so you find out now.)",
  );
}

main();
