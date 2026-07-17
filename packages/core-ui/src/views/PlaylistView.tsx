import { useState } from "react";
import type { ApiClient } from "../api/client";
import { ApiError } from "../api/client";
import type { Playlist, Track } from "../api/types";
import { PROVIDER_LABELS } from "../api/types";
import { ServiceBadge } from "../components/ServiceBadge";
import { formatDuration } from "../util/time";

export interface PlaylistViewProps {
  readonly client: ApiClient;
  readonly playlist: Playlist;
  readonly onChanged: (playlist: Playlist) => void;
  readonly onDeleted: (id: string) => void;
  readonly onPlay: (track: Track, queue: readonly Track[]) => void;
  readonly onShufflePlay: (tracks: readonly Track[]) => void;
}

export function PlaylistView({
  client,
  playlist,
  onChanged,
  onDeleted,
  onPlay,
  onShufflePlay,
}: PlaylistViewProps): JSX.Element {
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const tracks = playlist.entries.map((entry) => entry.track);

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
          <button type="button" className="danger" onClick={() => void deletePlaylist()} disabled={busy}>
            Delete
          </button>
        </div>
      </header>

      {error && <p className="status error" role="alert">{error}</p>}

      {playlist.entries.length === 0 ? (
        <p className="status">
          Nothing here yet. Search for songs and add them from any connected service.
        </p>
      ) : (
        <ol className="track-list">
          {playlist.entries.map((entry, index) => (
            <li key={entry.id} className="track-row">
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
    </section>
  );
}
