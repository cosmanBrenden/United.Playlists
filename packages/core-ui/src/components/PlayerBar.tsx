import type { PlayerState } from "../player/types";
import { ProgressBar } from "./ProgressBar";
import { ServiceBadge } from "./ServiceBadge";

export interface PlayerBarProps {
  readonly state: PlayerState;
  readonly onToggle: () => void;
  readonly onNext: () => void;
  readonly onPrevious: () => void;
  readonly onVolume: (volume: number) => void;
  readonly onSeek: (positionMs: number) => void;
  readonly onToggleShuffle: () => void;
  readonly onToggleQueue: () => void;
  readonly queueOpen: boolean;
}

/**
 * The transport bar.
 *
 * Shows which service the current track is playing from, because the user needs to
 * understand why a track suddenly cannot play — "Spotify wants Premium" is only
 * legible if they can see the track is a Spotify one. Below the controls sits the
 * seekable progress bar with its buffered indicator.
 */
export function PlayerBar({
  state,
  onToggle,
  onNext,
  onPrevious,
  onVolume,
  onSeek,
  onToggleShuffle,
  onToggleQueue,
  queueOpen,
}: PlayerBarProps): JSX.Element {
  const { track, status } = state;
  const canSeek = Boolean(track) && (status === "playing" || status === "paused");

  return (
    <footer className="player-bar">
      <div className="player-track">
        {track ? (
          <>
            {track.artworkUrl ? (
              <img src={track.artworkUrl} alt="" className="artwork" />
            ) : (
              <div className="artwork artwork-placeholder" aria-hidden="true" />
            )}
            <div className="track-meta">
              <span className="track-title">{track.title}</span>
              <span className="track-artist">{track.artistLine}</span>
            </div>
            <ServiceBadge provider={track.provider} />
          </>
        ) : (
          <span className="track-artist">Nothing playing</span>
        )}
      </div>

      <div className="player-center">
        <div className="player-controls">
          <button
            type="button"
            className="toggle-shuffle"
            onClick={onToggleShuffle}
            aria-pressed={state.shuffle}
            aria-label="Shuffle"
            title={state.shuffle ? "Shuffle on" : "Shuffle off"}
          >
            🔀
          </button>
          <button type="button" onClick={onPrevious} disabled={!track} aria-label="Previous track">
            ⏮
          </button>
          <button
            type="button"
            onClick={onToggle}
            disabled={!track || status === "loading"}
            aria-label={status === "playing" ? "Pause" : "Play"}
          >
            {status === "playing" ? "⏸" : "▶"}
          </button>
          <button type="button" onClick={onNext} disabled={!track} aria-label="Next track">
            ⏭
          </button>
        </div>

        <ProgressBar
          positionMs={state.positionMs}
          durationMs={state.durationMs}
          bufferedMs={state.bufferedMs}
          disabled={!canSeek}
          onSeek={onSeek}
        />
      </div>

      <div className="player-extra">
        {status === "loading" && <span className="status">Loading…</span>}
        {status === "error" && (
          <span className="status error" role="alert">
            {state.error}
          </span>
        )}
        <button
          type="button"
          className="toggle-queue"
          onClick={onToggleQueue}
          aria-pressed={queueOpen}
          aria-label="Queue"
          title="Show queue"
        >
          ☰
        </button>
        <input
          type="range"
          className="volume-range"
          min="0"
          max="1"
          step="0.01"
          value={state.volume}
          onChange={(event) => onVolume(Number(event.target.value))}
          aria-label="Volume"
        />
      </div>
    </footer>
  );
}
