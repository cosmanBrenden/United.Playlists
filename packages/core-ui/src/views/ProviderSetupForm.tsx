import { useState } from "react";
import type { ApiClient } from "../api/client";
import { ApiError } from "../api/client";
import type { ProviderInfo, ProviderSetup } from "../api/types";

export interface ProviderSetupFormProps {
  readonly client: ApiClient;
  readonly provider: ProviderInfo;
  readonly setup: ProviderSetup;
  readonly onSaved: () => void;
  readonly onCancel: () => void;
  /** Opens a URL in the user's real browser. Supplied by the Electron shell. */
  readonly openExternal?: ((url: string) => void) | undefined;
}

/**
 * Where the user enters a service's API credentials.
 *
 * The instructions and the redirect URI come from the backend rather than being
 * written here: each console is different, and the redirect URI depends on which
 * port the app is actually listening on.
 */
export function ProviderSetupForm({
  client,
  provider,
  setup,
  onSaved,
  onCancel,
  openExternal,
}: ProviderSetupFormProps): JSX.Element {
  const [clientId, setClientId] = useState(setup.clientId ?? "");
  const [clientSecret, setClientSecret] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  // A stored secret is never sent back to us, so an untouched field means "keep
  // what is saved" rather than "clear it".
  const secretAlreadySaved = setup.clientSecretSet;
  const secretMissing =
    setup.requiresClientSecret && !secretAlreadySaved && !clientSecret.trim();
  const canSave = clientId.trim().length > 0 && !secretMissing && !saving;

  const copyRedirectUri = async (): Promise<void> => {
    try {
      await navigator.clipboard.writeText(setup.redirectUri);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can be denied; the URI is on screen to copy by hand.
      setCopied(false);
    }
  };

  const save = async (event: React.FormEvent): Promise<void> => {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      const secret = clientSecret.trim() ? clientSecret.trim() : null;
      await client.saveSetup(provider.id, clientId.trim(), secret);
      onSaved();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Could not save the credentials");
    } finally {
      setSaving(false);
    }
  };

  const forget = async (): Promise<void> => {
    setSaving(true);
    setError(null);
    try {
      await client.clearSetup(provider.id);
      onSaved();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Could not clear the credentials");
    } finally {
      setSaving(false);
    }
  };

  return (
    <form className="setup-form" onSubmit={(event) => void save(event)}>
      <h3>Set up {provider.displayName}</h3>

      <ol className="setup-steps">
        {setup.instructions.map((step) => (
          <li key={step}>{step}</li>
        ))}
      </ol>

      <div className="setup-field">
        <span className="setup-label">Redirect URI to register</span>
        <div className="setup-copy-row">
          <code className="redirect-uri">{setup.redirectUri}</code>
          <button type="button" onClick={() => void copyRedirectUri()}>
            {copied ? "Copied" : "Copy"}
          </button>
        </div>
        {/* Getting this even slightly wrong is the single most common way the
            sign-in fails, and the service's error message never says so. */}
        <span className="setup-hint">Must match exactly, including the port.</span>
      </div>

      {setup.consoleUrl && (
        <button
          type="button"
          className="setup-console-link"
          onClick={() => openExternal?.(setup.consoleUrl as string)}
        >
          Open {provider.displayName} console ↗
        </button>
      )}

      <label className="setup-field">
        <span className="setup-label">Client ID</span>
        <input
          type="text"
          value={clientId}
          onChange={(event) => setClientId(event.target.value)}
          placeholder="Paste the client ID"
          autoComplete="off"
          spellCheck={false}
          required
        />
      </label>

      {setup.requiresClientSecret && (
        <label className="setup-field">
          <span className="setup-label">
            Client secret
            {secretAlreadySaved && <em className="setup-hint"> — saved; leave blank to keep</em>}
          </span>
          <input
            type="password"
            value={clientSecret}
            onChange={(event) => setClientSecret(event.target.value)}
            placeholder={secretAlreadySaved ? "••••••••" : "Paste the client secret"}
            autoComplete="off"
            spellCheck={false}
          />
        </label>
      )}

      {setup.source === "ENVIRONMENT" && (
        <p className="setup-hint">
          Currently using credentials from this build&apos;s configuration. Saving here
          overrides them.
        </p>
      )}

      {error && (
        <p className="status error" role="alert">
          {error}
        </p>
      )}

      <div className="setup-actions">
        <button type="submit" disabled={!canSave}>
          {saving ? "Saving…" : "Save"}
        </button>
        <button type="button" onClick={onCancel} disabled={saving}>
          Cancel
        </button>
        {setup.source === "APP" && (
          <button type="button" className="danger" onClick={() => void forget()} disabled={saving}>
            Forget these
          </button>
        )}
      </div>
    </form>
  );
}
