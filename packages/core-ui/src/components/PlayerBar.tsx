import type { PlayerState } from "../player/types";
import { ServiceBadge } from "./ServiceBadge";

export interface PlayerBarProps {
  readonly state: PlayerState;
  readonly onToggle: () => void;
  readonly onNext: () => void;
  readonly onPrevious: () => void;
  readonly onVolume: (volume: number) => void;
}

/**
 * The transport bar.
 *
 * Shows which service the current track is playing from, because the user needs to
 * understand why a track suddenly cannot play — "Spotify wants Premium" is only
 * legible if they can see the track is a Spotify one.
 */
export function PlayerBar({
  state,
  onToggle,
  onNext,
  onPrevious,
  onVolume,
}: PlayerBarProps): JSX.Element {
  const { track, status } = state;

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

      <div className="player-controls">
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

      <div className="player-extra">
        {status === "loading" && <span className="status">Loading…</span>}
        {status === "error" && (
          <span className="status error" role="alert">
            {state.error}
          </span>
        )}
        <input
          type="range"
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
