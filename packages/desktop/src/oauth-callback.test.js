import { strict as assert } from "node:assert";
import { after, describe, it } from "node:test";
import { createServer } from "node:http";
import { awaitOAuthCallback } from "./oauth-callback.js";

/** Finds a free port, so parallel runs cannot collide on a hard-coded one. */
async function freePort() {
  const probe = createServer();
  await new Promise((resolve) => probe.listen(0, "127.0.0.1", resolve));
  const { port } = probe.address();
  await new Promise((resolve) => probe.close(resolve));
  return port;
}

const hit = (port, query) =>
  fetch(`http://127.0.0.1:${port}/callback${query}`).catch(() => null);

describe("awaitOAuthCallback", () => {
  it("resolves with the code and state from the callback", async () => {
    const port = await freePort();
    const pending = awaitOAuthCallback({ port });

    await hit(port, "?code=auth-code-123&state=the-state");

    assert.deepEqual(await pending, { code: "auth-code-123", state: "the-state" });
  });

  // The assertion is attached before the request is fired. The listener now settles
  // in single-digit milliseconds, so awaiting the request first would leave the
  // rejection momentarily unhandled and trip the test runner.
  it("rejects when the user refuses consent", async () => {
    const port = await freePort();
    const rejection = assert.rejects(
      awaitOAuthCallback({ port }),
      /refused: access_denied/,
    );

    await hit(port, "?error=access_denied");

    await rejection;
  });

  it("rejects a callback missing its code", async () => {
    const port = await freePort();
    const rejection = assert.rejects(
      awaitOAuthCallback({ port }),
      /did not include a code and state/,
    );

    await hit(port, "?state=only-state");

    await rejection;
  });

  it("releases the port once it has a result", async () => {
    const port = await freePort();
    const pending = awaitOAuthCallback({ port });
    await hit(port, "?code=c&state=s");
    await pending;

    // The port must be reusable: a listener left open waiting for an authorization
    // code is an injection point, and it would block the next sign-in with EADDRINUSE.
    const second = awaitOAuthCallback({ port });
    await hit(port, "?code=c2&state=s2");
    assert.deepEqual(await second, { code: "c2", state: "s2" });
  });

  it("ignores requests to any other path", async () => {
    const port = await freePort();
    const pending = awaitOAuthCallback({ port });

    const probe = await fetch(`http://127.0.0.1:${port}/not-the-callback`).catch(() => null);
    assert.equal(probe?.status, 404);

    // Still waiting for the real thing.
    await hit(port, "?code=real&state=s");
    assert.equal((await pending).code, "real");
  });

  it("can be cancelled", async () => {
    const port = await freePort();
    const controller = new AbortController();
    const pending = awaitOAuthCallback({ port, signal: controller.signal });

    controller.abort();

    await assert.rejects(pending, /cancelled/);
  });

  it("reports a port clash rather than hanging", async () => {
    const port = await freePort();
    const squatter = createServer();
    await new Promise((resolve) => squatter.listen(port, "127.0.0.1", resolve));

    try {
      await assert.rejects(awaitOAuthCallback({ port }), /already in use/);
    } finally {
      await new Promise((resolve) => squatter.close(resolve));
    }
  });

  it("tells the browser not to cache the page holding the code", async () => {
    const port = await freePort();
    const pending = awaitOAuthCallback({ port });

    const response = await fetch(`http://127.0.0.1:${port}/callback?code=c&state=s`);

    assert.equal(response.headers.get("cache-control"), "no-store");
    await pending;
  });
});

describe("BackendProcess.generateApiToken", () => {
  it("produces a fresh, URL-safe, high-entropy token each time", async () => {
    const { BackendProcess } = await import("./backend.js");

    const tokens = new Set(
      Array.from({ length: 50 }, () => BackendProcess.generateApiToken()),
    );

    assert.equal(tokens.size, 50);
    for (const token of tokens) {
      assert.match(token, /^[A-Za-z0-9_-]+$/);
      // 32 bytes as base64url.
      assert.ok(token.length >= 42, `token too short: ${token.length}`);
    }
  });
});
