import { useEffect, useMemo, useState } from "react";
import type { ApiClient } from "../api/client";
import { ApiError } from "../api/client";
import type { MigrationResult, Playlist, ProviderId, Track, UnresolvedMatch } from "../api/types";
import { PROVIDER_LABELS } from "../api/types";
import { MigrationDialog } from "../components/MigrationDialog";
import { ServiceBadge } from "../components/ServiceBadge";
import { formatDuration } from "../util/time";

export interface PlaylistViewProps {
  readonly client: ApiClient;
  readonly playlist: Playlist;
  readonly onChanged: (playlist: Playlist) => void;
  readonly onDeleted: (id: string) => void;
  readonly onPlay: (track: Track, queue: readonly Track[]) => void;
  readonly onShufflePlay: (tracks: readonly Track[]) => void;
  /**
   * Services a track can be migrated onto: those that are available and searchable
   * right now (a connected authenticated service, or a scraper that needs no
   * sign-in). Empty hides the migration controls entirely.
   */
  readonly migrationTargets: readonly ProviderId[];
}

/** A human summary of what a migration job did, for the confirmation toast. */
function summarize(result: MigrationResult): string {
  const parts: string[] = [];
  if (result.replaced.length > 0) {
    parts.push(`${result.replaced.length} replaced automatically`);
  }
  if (result.alreadyOnTarget > 0) {
    parts.push(`${result.alreadyOnTarget} already on ${PROVIDER_LABELS[result.target]}`);
  }
  if (result.unresolved.length === 0 && result.replaced.length === 0 && result.alreadyOnTarget === 0) {
    parts.push("nothing to migrate");
  }
  return parts.join(", ");
}

export function PlaylistView({
  client,
  playlist,
  onChanged,
  onDeleted,
  onPlay,
  onShufflePlay,
  migrationTargets,
}: PlaylistViewProps): JSX.Element {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [migrateMode, setMigrateMode] = useState(false);
  const [selected, setSelected] = useState<ReadonlySet<number>>(new Set());
  const [target, setTarget] = useState<ProviderId | "">("");
  const [migrating, setMigrating] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [unresolved, setUnresolved] = useState<{
    readonly target: ProviderId;
    readonly matches: readonly UnresolvedMatch[];
  } | null>(null);

  const tracks = playlist.entries.map((entry) => entry.track);

  // Selection is by position, so it makes no sense to carry across a switch to a
  // different playlist — or across edits that renumber the rows. Leaving migrate mode
  // on across a switch is fine, but a fresh playlist starts with nothing selected.
  useEffect(() => {
    setSelected(new Set());
  }, [playlist.id]);

  // Migrate mode is opt-in so the row checkboxes and toolbar stay out of the way
  // during normal listening. Turning it off drops any pending selection.
  const toggleMigrateMode = (): void =>
    setMigrateMode((on) => {
      if (on) {
        setSelected(new Set());
      }
      return !on;
    });

  // Default the target to the first offered service so the control is usable with
  // one click, but never override a choice the user has already made.
  useEffect(() => {
    setTarget((current) =>
      current === "" && migrationTargets.length > 0 ? migrationTargets[0]! : current,
    );
  }, [migrationTargets]);

  const allSelected = tracks.length > 0 && selected.size === tracks.length;

  const toggle = (index: number): void =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });

  const toggleAll = (): void =>
    setSelected(allSelected ? new Set() : new Set(tracks.map((_, index) => index)));

  const run = async (action: () => Promise<Playlist>): Promise<void> => {
    setBusy(true);
    setError(null);
    try {
      onChanged(await action());
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Something went wrong");
    } finally {
      setBusy(false);
    }
  };

  const remove = (position: number) => run(() => client.removeTrack(playlist.id, position));
  const move = (from: number, to: number) => run(() => client.moveTrack(playlist.id, from, to));

  const migrate = async (positions: readonly number[] | undefined): Promise<void> => {
    if (!target) {
      return;
    }
    setMigrating(true);
    setError(null);
    setNotice(null);
    try {
      const result = await client.migratePlaylist(playlist.id, target, positions);
      // The auto-replacements are already applied server-side; adopt the returned
      // playlist so the rows update without a refetch.
      onChanged(result.playlist);
      setSelected(new Set());
      setNotice(summarize(result));
      // Anything the app could not match confidently goes to the picker.
      if (result.unresolved.length > 0) {
        setUnresolved({ target: result.target, matches: result.unresolved });
      }
      if (result.failures.length > 0) {
        setError(
          `Some services didn't respond: ${result.failures
            .map((failure) => PROVIDER_LABELS[failure.provider])
            .join(", ")}. Matches may be incomplete.`,
        );
      }
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Migration failed");
    } finally {
      setMigrating(false);
    }
  };

  // Applying a manual choice from the picker. Errors propagate so the dialog can
  // show them against the card and keep it open.
  const resolveMatch = async (match: UnresolvedMatch, candidate: Track): Promise<void> => {
    const updated = await client.replaceTrack(
      playlist.id,
      match.position,
      candidate,
      match.source.key,
    );
    onChanged(updated);
  };

  const selectedPositions = useMemo(
    () => [...selected].sort((a, b) => a - b),
    [selected],
  );

  const deletePlaylist = async (): Promise<void> => {
    setBusy(true);
    try {
      await client.deletePlaylist(playlist.id);
      onDeleted(playlist.id);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Could not delete the playlist");
      setBusy(false);
    }
  };

  return (
    <section className="view playlist-view">
      <header className="view-header">
        <div>
          <h2>{playlist.name}</h2>
          {playlist.description && <p className="subtitle">{playlist.description}</p>}
          <p className="meta">
            {playlist.trackCount} {playlist.trackCount === 1 ? "track" : "tracks"}
            {playlist.providersUsed.length > 0 && (
              <>
                {" from "}
                {playlist.providersUsed.map((p) => PROVIDER_LABELS[p]).join(", ")}
              </>
            )}
          </p>
          {/* Imported playlists are local copies. Say so, so the user is not
              surprised that their edits do not appear on the origin service. */}
          {playlist.origin && (
            <p className="meta origin-note">
              Imported from {PROVIDER_LABELS[playlist.origin.provider]}. Your changes here stay
              in UnitedPlaylists and will not alter the original.
            </p>
          )}
        </div>

        <div className="header-actions">
          <button
            type="button"
            onClick={() => tracks[0] && onPlay(tracks[0], tracks)}
            disabled={tracks.length === 0}
          >
            Play all
          </button>
          <button
            type="button"
            onClick={() => onShufflePlay(tracks)}
            disabled={tracks.length === 0}
            title="Play in a random order"
          >
            🔀 Shuffle
          </button>
          {/* Migrate mode is hidden behind this toggle: the row checkboxes and the
              destination bar only appear once the user asks for them. */}
          {migrationTargets.length > 0 && tracks.length > 0 && (
            <button
              type="button"
              onClick={toggleMigrateMode}
              aria-pressed={migrateMode}
              title="Move tracks to another service"
            >
              {migrateMode ? "Done migrating" : "⇄ Migrate"}
            </button>
          )}
          <button type="button" className="danger" onClick={() => void deletePlaylist()} disabled={busy}>
            Delete
          </button>
        </div>
      </header>

      {error && <p className="status error" role="alert">{error}</p>}
      {notice && (
        <p className="status" role="status" aria-live="polite">
          {notice}
        </p>
      )}

      {/* Migration controls: pick a destination service, then move the selected
          tracks (or the whole playlist) onto it. Shown only in migrate mode. */}
      {migrateMode && migrationTargets.length > 0 && playlist.entries.length > 0 && (
        <div className="migration-bar">
          <label className="migration-target">
            <span>Migrate to</span>
            <select
              value={target}
              onChange={(event) => setTarget(event.target.value as ProviderId)}
              disabled={migrating}
            >
              {migrationTargets.map((provider) => (
                <option key={provider} value={provider}>
                  {PROVIDER_LABELS[provider]}
                </option>
              ))}
            </select>
          </label>

          <button
            type="button"
            onClick={() => void migrate(selectedPositions)}
            disabled={migrating || selected.size === 0 || !target}
            title="Move the selected tracks to the chosen service"
          >
            Migrate selected ({selected.size})
          </button>
          <button
            type="button"
            onClick={() => void migrate(undefined)}
            disabled={migrating || !target}
            title="Move every track to the chosen service"
          >
            Migrate whole playlist
          </button>
          {migrating && <span className="status" role="status">Searching services…</span>}
        </div>
      )}

      {playlist.entries.length === 0 ? (
        <p className="status">
          Nothing here yet. Search for songs and add them from any connected service.
        </p>
      ) : (
        <ol className="track-list">
          {migrateMode && (
            <li className="track-row select-all-row">
              <label className="select-track">
                <input
                  type="checkbox"
                  checked={allSelected}
                  onChange={toggleAll}
                  aria-label={allSelected ? "Clear selection" : "Select all tracks"}
                />
                <span className="meta">
                  {selected.size > 0 ? `${selected.size} selected` : "Select all"}
                </span>
              </label>
            </li>
          )}
          {playlist.entries.map((entry, index) => (
            <li
              key={entry.id}
              className={
                migrateMode && selected.has(index) ? "track-row selected" : "track-row"
              }
            >
              {migrateMode && (
                <input
                  type="checkbox"
                  className="select-track"
                  checked={selected.has(index)}
                  onChange={() => toggle(index)}
                  aria-label={`Select ${entry.track.title}`}
                />
              )}
              <span className="position">{index + 1}</span>

              {entry.track.artworkUrl ? (
                <img src={entry.track.artworkUrl} alt="" className="artwork" loading="lazy" />
              ) : (
                <div className="artwork artwork-placeholder" aria-hidden="true" />
              )}

              <div className="track-meta">
                <span className="track-title">{entry.track.title}</span>
                <span className="track-artist">{entry.track.artistLine}</span>
              </div>

              <ServiceBadge provider={entry.track.provider} />
              <span className="duration">{formatDuration(entry.track.durationMs)}</span>

              <button
                type="button"
                className="play-button"
                onClick={() => onPlay(entry.track, tracks)}
                aria-label={`Play ${entry.track.title}`}
              >
                ▶
              </button>
              <button
                type="button"
                onClick={() => void move(index, index - 1)}
                disabled={busy || index === 0}
                aria-label={`Move ${entry.track.title} up`}
              >
                ↑
              </button>
              <button
                type="button"
                onClick={() => void move(index, index + 1)}
                disabled={busy || index === playlist.entries.length - 1}
                aria-label={`Move ${entry.track.title} down`}
              >
                ↓
              </button>
              <button
                type="button"
                className="danger"
                onClick={() => void remove(index)}
                disabled={busy}
                aria-label={`Remove ${entry.track.title}`}
              >
                ✕
              </button>
            </li>
          ))}
        </ol>
      )}

      {unresolved && (
        <MigrationDialog
          target={unresolved.target}
          unresolved={unresolved.matches}
          onResolve={resolveMatch}
          onClose={() => setUnresolved(null)}
        />
      )}
    </section>
  );
}
