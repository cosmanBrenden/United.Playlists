import { createServer } from "node:http";

/**
 * A one-shot loopback listener for the OAuth callback.
 *
 * This is RFC 8252's flow for native apps: the sign-in happens in the user's real
 * browser, and the service redirects back to a loopback URL that this app is
 * briefly listening on. The browser matters — the services block sign-in from
 * embedded webviews, and an embedded window would put this app in a position to
 * observe the user's password.
 *
 * The listener closes as soon as it has a result. Leaving a port open waiting for
 * an authorization code is an invitation to have one injected.
 */

/** Long enough to sign in and clear a 2FA prompt; short enough to not linger. */
const CALLBACK_TIMEOUT_MS = 5 * 60_000;

const SUCCESS_PAGE = `<!doctype html>
<html><head><meta charset="utf-8"><title>Connected</title></head>
<body style="font-family:system-ui;text-align:center;padding-top:4rem">
  <h1>Connected</h1>
  <p>You can close this tab and go back to UnitedPlaylists.</p>
</body></html>`;

const FAILURE_PAGE = `<!doctype html>
<html><head><meta charset="utf-8"><title>Not connected</title></head>
<body style="font-family:system-ui;text-align:center;padding-top:4rem">
  <h1>Something went wrong</h1>
  <p>Go back to UnitedPlaylists and try again.</p>
</body></html>`;

/**
 * Waits for one OAuth callback on a loopback port.
 *
 * @param {object} options
 * @param {number} options.port  must match the redirect URI registered with the service
 * @param {string} options.path
 * @param {AbortSignal} [options.signal]
 * @returns {Promise<{ code: string, state: string }>}
 */
export function awaitOAuthCallback({ port, path = "/callback", signal }) {
  return new Promise((resolve, reject) => {
    const server = createServer((request, response) => {
      const url = new URL(request.url ?? "/", `http://127.0.0.1:${port}`);

      if (url.pathname !== path) {
        response.writeHead(404).end();
        return;
      }

      const error = url.searchParams.get("error");
      const code = url.searchParams.get("code");
      const state = url.searchParams.get("state");

      if (error) {
        respond(response, 400, FAILURE_PAGE);
        // A user clicking "Cancel" arrives here. It is an outcome, not a crash.
        settle(() => reject(new Error(`Authorization was refused: ${error}`)));
        return;
      }

      if (!code || !state) {
        respond(response, 400, FAILURE_PAGE);
        settle(() => reject(new Error("Callback did not include a code and state")));
        return;
      }

      respond(response, 200, SUCCESS_PAGE);
      // The state is not checked here: the backend holds the expected value and
      // does the comparison. Splitting that check across two processes would mean
      // two places to get it wrong.
      settle(() => resolve({ code, state }));
    });

    const timeout = setTimeout(() => {
      settle(() => reject(new Error("Timed out waiting for you to finish signing in")));
    }, CALLBACK_TIMEOUT_MS);

    let settled = false;
    /** Closes the server exactly once, then reports the outcome. */
    const settle = (report) => {
      if (settled) {
        return;
      }
      settled = true;
      clearTimeout(timeout);
      // close() alone only stops new connections; it then waits for the browser's
      // keep-alive connection to time out, which stalls the result by seconds and
      // makes the app look hung right after a successful sign-in.
      server.closeAllConnections();
      server.close(() => report());
    };

    signal?.addEventListener("abort", () => {
      settle(() => reject(new Error("Authorization was cancelled")));
    });

    server.on("error", (cause) => {
      settle(() => reject(new Error(
        cause.code === "EADDRINUSE"
          ? `Port ${port} is already in use, so the sign-in callback cannot be received.`
          : `Callback listener failed: ${cause.message}`,
        { cause },
      )));
    });

    // Loopback only: this must not be reachable from the network.
    server.listen(port, "127.0.0.1");
  });
}

function respond(response, status, body) {
  response.writeHead(status, {
    "Content-Type": "text/html; charset=utf-8",
    // This page carries an authorization code in its URL. It must not be stored.
    "Cache-Control": "no-store",
    // This listener serves exactly one request; there is no reason to keep the
    // socket alive afterwards.
    Connection: "close",
  });
  response.end(body);
}
