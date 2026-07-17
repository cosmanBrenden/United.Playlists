import { useState } from "react";
import type { ApiClient } from "../api/client";
import { ApiError } from "../api/client";
import type { ImportSummary, ProviderId, ProviderInfo } from "../api/types";
import { PROVIDER_LABELS } from "../api/types";
import { ProviderSetupForm } from "./ProviderSetupForm";

export interface ConnectionsViewProps {
  readonly client: ApiClient;
  readonly providers: readonly ProviderInfo[];
  readonly onChanged: () => void;
  /**
   * Opens the service's sign-in page in the user's real browser and resolves with
   * the callback code. Supplied by the Electron shell, which owns the loopback
   * listener.
   */
  readonly authorize: (provider: ProviderId, url: string) => Promise<{ code: string; state: string }>;
  /** Opens a URL in the user's real browser, for the "open console" links. */
  readonly openExternal?: ((url: string) => void) | undefined;
}

export function ConnectionsView({
  client,
  providers,
  onChanged,
  authorize,
  openExternal,
}: ConnectionsViewProps): JSX.Element {
  const [busy, setBusy] = useState<ProviderId | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [summary, setSummary] = useState<ImportSummary | null>(null);
  /** Which service's setup form is open, if any. */
  const [editing, setEditing] = useState<ProviderId | null>(null);

  const connect = async (provider: ProviderId): Promise<void> => {
    setBusy(provider);
    setError(null);
    try {
      const url = await client.beginAuthorization(provider);
      // Sign-in happens in the real browser, never an embedded webview: the
      // services block embedded sign-in, and an embedded window would put this app
      // in a position to see the user's password.
      const { code, state } = await authorize(provider, url);
      await client.completeAuthorization(provider, code, state);
      onChanged();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Could not connect the service");
    } finally {
      setBusy(null);
    }
  };

  const disconnect = async (provider: ProviderId): Promise<void> => {
    setBusy(provider);
    try {
      await client.disconnect(provider);
      onChanged();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Could not disconnect");
    } finally {
      setBusy(null);
    }
  };

  const importFrom = async (provider: ProviderId): Promise<void> => {
    setBusy(provider);
    setError(null);
    try {
      setSummary(await client.importPlaylists(provider));
      onChanged();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Import failed");
    } finally {
      setBusy(null);
    }
  };

  const importUrl = async (provider: ProviderId, url: string): Promise<void> => {
    if (!url.trim()) {
      return;
    }
    setBusy(provider);
    setError(null);
    try {
      setSummary(await client.importPlaylistByUrl(provider, url.trim()));
      onChanged();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Import failed");
    } finally {
      setBusy(null);
    }
  };

  return (
    <section className="view connections-view">
      <header className="view-header">
        <h2>Services</h2>
      </header>

      {error && <p className="status error" role="alert">{error}</p>}

      {summary && (
        <p className="status" role="status">
          Imported {summary.importedCount}{" "}
          {summary.importedCount === 1 ? "playlist" : "playlists"} ({summary.trackCount} tracks).
          {summary.alreadyPresent > 0 && (
            <>
              {" "}
              {summary.alreadyPresent} already imported and left untouched, in case you had
              edited your copy.
            </>
          )}
          {/* Without this, importing 3 of someone's 40 playlists reads as a bug. */}
          {summary.unreadable > 0 && (
            <>
              {" "}
              {summary.unreadable} could not be read:{" "}
              {PROVIDER_LABELS[summary.provider]} only allows reading playlists you own or
              collaborate on, so ones you merely follow — Discover Weekly and the like —
              cannot be copied.
            </>
          )}
        </p>
      )}

      <ul className="provider-list">
        {providers.map((provider) => (
          <li key={provider.id} className="provider-item">
            <div className="provider-row">
              <div className="provider-meta">
                <span className="provider-name">{provider.displayName}</span>
                {provider.connected && provider.accountLabel && (
                  <span className="provider-account">{provider.accountLabel}</span>
                )}
                {/* A missing permission only shows up as a 403 when the user tries to
                    import, with nothing to say reconnecting is the fix. Say it here. */}
                {provider.connected && provider.missingScopes.length > 0 && (
                  <span className="provider-warning" role="status">
                    {provider.displayName} did not grant{" "}
                    <code>{provider.missingScopes.join(", ")}</code>, so importing will fail.
                    Disconnect and connect again, and approve every permission it asks for.
                  </span>
                )}
                {/* The backend says why. Hard-coding a reason here meant every
                    unavailable service claimed it needed an Apple membership. */}
                {!provider.available && provider.unavailableReason && (
                  <span className="provider-note">{provider.unavailableReason}</span>
                )}
                {/* Scraper services need no sign-in and are always usable. */}
                {!provider.requiresAuthentication && provider.available && (
                  <span className="provider-account">Ready — no sign-in needed</span>
                )}
              </div>

              {/* Scraper-backed services: no connect, no setup. Usable immediately,
                  imported by pasting a playlist URL. */}
              {!provider.requiresAuthentication ? (
                <button
                  type="button"
                  onClick={() =>
                    setEditing(editing === provider.id ? null : provider.id)
                  }
                  disabled={busy !== null}
                  aria-expanded={editing === provider.id}
                >
                  Import by URL
                </button>
              ) : /* Nothing the user enters would make an unimplemented service work,
                    so it gets no setup button. */
              !provider.setupSupported ? (
                <button type="button" disabled title={provider.unavailableReason ?? "Unavailable"}>
                  Unavailable
                </button>
              ) : (
                <>
                  {provider.setup && (
                    <button
                      type="button"
                      onClick={() =>
                        setEditing(editing === provider.id ? null : provider.id)
                      }
                      disabled={busy !== null}
                      aria-expanded={editing === provider.id}
                    >
                      {provider.available ? "Edit keys" : "Add keys"}
                    </button>
                  )}
                  {provider.available &&
                    (provider.connected ? (
                      <>
                        <button
                          type="button"
                          onClick={() => void importFrom(provider.id)}
                          disabled={busy !== null}
                        >
                          Import playlists
                        </button>
                        <button
                          type="button"
                          className="danger"
                          onClick={() => void disconnect(provider.id)}
                          disabled={busy !== null}
                        >
                          Disconnect
                        </button>
                      </>
                    ) : (
                      <button
                        type="button"
                        onClick={() => void connect(provider.id)}
                        disabled={busy !== null}
                      >
                        {busy === provider.id ? "Connecting…" : "Connect"}
                      </button>
                    ))}
                </>
              )}
            </div>

            {editing === provider.id && provider.setup && (
              <ProviderSetupForm
                client={client}
                provider={provider}
                setup={provider.setup}
                openExternal={openExternal}
                onSaved={() => {
                  setEditing(null);
                  onChanged();
                }}
                onCancel={() => setEditing(null)}
              />
            )}

            {editing === provider.id && !provider.requiresAuthentication && (
              <UrlImportForm
                provider={provider}
                busy={busy !== null}
                onImport={(url) => importUrl(provider.id, url)}
                onCancel={() => setEditing(null)}
              />
            )}
          </li>
        ))}
      </ul>

      <p className="footnote">
        Disconnecting a service keeps any playlists you imported from it — they are your copies.
        Their tracks simply stop playing until you reconnect. YouTube and SoundCloud need no
        sign-in; search and play them straight away, and import a playlist by pasting its link.
      </p>
    </section>
  );
}

interface UrlImportFormProps {
  readonly provider: ProviderInfo;
  readonly busy: boolean;
  readonly onImport: (url: string) => void;
  readonly onCancel: () => void;
}

/**
 * Paste-a-URL import for the scraper-backed services.
 *
 * These have no account to enumerate, so a link is the substitute for "import my
 * playlists". Public and unlisted playlists work; a private one the scraper cannot see
 * comes back as unreadable.
 */
function UrlImportForm({ provider, busy, onImport, onCancel }: UrlImportFormProps): JSX.Element {
  const [url, setUrl] = useState("");

  return (
    <form
      className="setup-form"
      onSubmit={(event) => {
        event.preventDefault();
        onImport(url);
      }}
    >
      <h3>Import a {provider.displayName} playlist</h3>
      <p className="setup-hint">
        Paste a public or unlisted playlist link. There is no sign-in — {provider.displayName}
        {" "}search and playback already work without one.
      </p>
      <label className="setup-field">
        <span className="setup-label">Playlist URL</span>
        <input
          type="url"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          placeholder={
            provider.id === "SOUNDCLOUD"
              ? "https://soundcloud.com/user/sets/…"
              : "https://www.youtube.com/playlist?list=…"
          }
          autoComplete="off"
          required
        />
      </label>
      <div className="setup-actions">
        <button type="submit" disabled={busy || !url.trim()}>
          {busy ? "Importing…" : "Import"}
        </button>
        <button type="button" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
      </div>
    </form>
  );
}
