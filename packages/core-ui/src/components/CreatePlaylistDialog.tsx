import { useEffect, useRef, useState } from "react";

export interface CreatePlaylistDialogProps {
  readonly onCancel: () => void;
  /** Resolves once the playlist is created; the dialog stays open (and shows the
   *  error) if it rejects. */
  readonly onCreate: (name: string, description: string | null) => Promise<void>;
}

/**
 * A modal for naming a new playlist.
 *
 * Replaces `window.prompt`, which Electron disables by default — it silently returns
 * null, so the old "New playlist" button did nothing and users could only ever
 * import. A real form also lets us take a description and report a create failure
 * inline instead of losing what was typed.
 */
export function CreatePlaylistDialog({
  onCancel,
  onCreate,
}: CreatePlaylistDialogProps): JSX.Element {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const nameRef = useRef<HTMLInputElement>(null);

  // Focus the name field on open so the user can type immediately, and so Escape and
  // Enter behave like a real dialog.
  useEffect(() => {
    nameRef.current?.focus();
  }, []);

  const submit = async (): Promise<void> => {
    const trimmed = name.trim();
    if (!trimmed) {
      setError("Give the playlist a name.");
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await onCreate(trimmed, description.trim() || null);
      // Success closes the dialog from the parent; nothing to do here.
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not create the playlist");
      setBusy(false);
    }
  };

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onClick={(event) => {
        // A click on the backdrop itself (not the dialog) cancels.
        if (event.target === event.currentTarget && !busy) {
          onCancel();
        }
      }}
    >
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="create-playlist-title"
        onKeyDown={(event) => {
          if (event.key === "Escape" && !busy) {
            onCancel();
          }
        }}
      >
        <h2 id="create-playlist-title">New playlist</h2>

        <form
          className="modal-form"
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
        >
          <label className="setup-field">
            <span className="setup-label">Name</span>
            <input
              ref={nameRef}
              type="text"
              value={name}
              maxLength={200}
              placeholder="My playlist"
              onChange={(event) => setName(event.target.value)}
              disabled={busy}
            />
          </label>

          <label className="setup-field">
            <span className="setup-label">Description (optional)</span>
            <input
              type="text"
              value={description}
              maxLength={500}
              placeholder="What's in it?"
              onChange={(event) => setDescription(event.target.value)}
              disabled={busy}
            />
          </label>

          {error && (
            <p className="status error" role="alert">
              {error}
            </p>
          )}

          <div className="modal-actions">
            <button type="button" onClick={onCancel} disabled={busy}>
              Cancel
            </button>
            <button type="submit" className="primary" disabled={busy}>
              {busy ? "Creating…" : "Create"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
