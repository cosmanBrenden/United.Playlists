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
 *   node scripts/setup-evs.mjs                 # asks whether to log in or create an account
 *   node scripts/setup-evs.mjs --login         # log in to an existing account (reauth)
 *   node scripts/setup-evs.mjs --signup        # create a new account (prompts for the
 *                                              #   emailed confirmation code)
 *   node scripts/setup-evs.mjs --signup --confirm 123456   # CI: confirm with the code
 *   node scripts/setup-evs.mjs --skip-install  # don't touch pip, just authenticate
 *   node scripts/setup-evs.mjs --ensure        # best-effort refresh, used by `npm run dist`
 *
 * With no --login/--signup flag and a terminal attached, the script asks which you
 * want, so a fresh machine that has never had an EVS account is handled — you are not
 * assumed to have one. It also offers to create an account if a login attempt fails
 * because none exists. Without a terminal (CI), it defaults to login; pass --signup
 * explicitly to create an account there.
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
import { createInterface } from "node:readline/promises";
import { stdin, stdout } from "node:process";

const args = new Set(process.argv.slice(2));
if (args.has("--help") || args.has("-h")) {
  printUsageAndExit(0);
}

const SIGNUP = args.has("--signup");
const LOGIN = args.has("--login");
const SKIP_INSTALL = args.has("--skip-install");
const ENSURE = args.has("--ensure");
const confirmCode = readFlagValue("--confirm");

/** Whether a real terminal is attached, so we can prompt the user. */
const INTERACTIVE = Boolean(stdin.isTTY && stdout.isTTY);

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
      "  node scripts/setup-evs.mjs               ask whether to log in or create an account",
      "  node scripts/setup-evs.mjs --login       log in to an existing EVS account",
      "  node scripts/setup-evs.mjs --signup      create a new account",
      "  node scripts/setup-evs.mjs --signup --confirm <code>   confirm a new account (CI)",
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

/** Resolves to "signup" or "login". Explicit flag wins; else ask, or default to login. */
async function chooseMode() {
  if (SIGNUP) return "signup";
  if (LOGIN) return "login";
  if (!INTERACTIVE) {
    // No terminal to ask at (CI): default to login. Callers create accounts with the
    // explicit --signup flag there.
    return "login";
  }
  const rl = createInterface({ input: stdin, output: stdout });
  try {
    const answer = await rl.question(
      "\n[evs] Do you already have a Castlabs EVS account?\n" +
        "        [1] Yes — log in on this machine\n" +
        "        [2] No  — create one now\n" +
        "      Choose [1/2]: ",
    );
    return answer.trim().startsWith("2") ? "signup" : "login";
  } finally {
    rl.close();
  }
}

/** Asks a yes/no question at the terminal; defaults to no on empty input. */
async function askYesNo(question) {
  const rl = createInterface({ input: stdin, output: stdout });
  try {
    const answer = await rl.question(`[evs] ${question} [y/N] `);
    return /^y(es)?$/i.test(answer.trim());
  } finally {
    rl.close();
  }
}

/**
 * Creates and confirms an EVS account. Interactively, the tool prompts for any missing
 * details and for the emailed confirmation code, so one command does the whole thing.
 * Non-interactively it needs a `--confirm <code>` to finish; without one it returns
 * false after starting signup, having told the caller how to complete it.
 *
 * @returns true when the account is ready, false when it still needs a confirmation code
 */
function doSignup(python) {
  const account = [python, "-m", "castlabs_evs.account"];
  // Only go non-interactive when we truly cannot prompt for the code — i.e. no terminal
  // and no code yet. A terminal lets the tool ask for the code itself (best UX).
  const canConfirmNow = INTERACTIVE || Boolean(confirmCode);
  const modeFlag = canConfirmNow ? [] : ["-n"];

  console.log("[evs] creating a new EVS account …");
  const signup = run(account[0], [
    ...account.slice(1),
    ...modeFlag,
    ...withCreds("signup", { includeSignupFields: true }),
  ]);
  if (signup.status !== 0) {
    die("Signup failed. Check the output above.");
  }

  // A terminal-driven signup already confirmed inline. Otherwise confirm with the code
  // if we have it, or tell the caller how to finish.
  if (INTERACTIVE && !confirmCode) {
    return true;
  }
  if (confirmCode) {
    const confirm = run(account[0], [
      ...account.slice(1),
      "-n",
      ...withCreds("confirm-signup"),
      "-C",
      confirmCode,
    ]);
    if (confirm.status !== 0) {
      die("Confirmation failed. Re-run with the correct --confirm <code>.");
    }
    return true;
  }
  console.log(
    "\n[evs] Account created. Check your email for the confirmation code, then run:\n" +
      "        npm run setup:evs -- --signup --confirm <code>",
  );
  return false;
}

/**
 * Logs into an existing EVS account (reauth). Non-interactive when account + password
 * are in the environment; otherwise the tool prompts.
 *
 * @returns true on success, false on failure (caller decides what to do next)
 */
function doLogin(python) {
  const account = [python, "-m", "castlabs_evs.account"];
  const nonInteractive = haveNonInteractiveCreds();
  console.log(
    nonInteractive
      ? "[evs] logging in (non-interactive) …"
      : "[evs] logging in (you may be prompted) …",
  );
  const reauth = run(account[0], [
    ...account.slice(1),
    ...(nonInteractive ? ["-n"] : []),
    ...withCreds("reauth"),
  ]);
  return reauth.status === 0;
}

async function main() {
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

  // Decide up front whether we are logging in or creating an account. An explicit flag
  // wins; otherwise ask (terminal) or default to login (CI).
  const mode = await chooseMode();

  if (mode === "signup") {
    if (!doSignup(python)) {
      return; // signup started but needs an emailed code; message already printed
    }
  } else {
    // Login. If it fails on a fresh machine because no account exists, don't dead-end —
    // offer to create one right here, which is the whole point of this change.
    if (!doLogin(python)) {
      const create =
        INTERACTIVE &&
        (await askYesNo("Log in failed. Create a new EVS account now instead?"));
      if (create) {
        if (!doSignup(python)) {
          return;
        }
      } else {
        die(
          "Authentication failed. If you have no EVS account yet, run " +
            "`npm run setup:evs -- --signup` to create one; otherwise check your " +
            "UP_EVS_ACCOUNT / UP_EVS_PASSWORD (or run without them to be prompted).",
        );
      }
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

main().catch((err) => die(String(err?.stack ?? err)));
