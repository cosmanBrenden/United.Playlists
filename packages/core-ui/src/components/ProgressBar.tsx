import { useState } from "react";
import { formatDuration } from "../util/time";

export interface ProgressBarProps {
  readonly positionMs: number;
  readonly durationMs: number;
  /** How much is loaded ahead, or null when the service cannot report buffering. */
  readonly bufferedMs: number | null;
  /** Whether seeking is possible right now (something is loaded). */
  readonly disabled: boolean;
  readonly onSeek: (positionMs: number) => void;
}

const clampPercent = (value: number): number => Math.min(100, Math.max(0, value));

/**
 * The song progress bar.
 *
 * Three layers stacked in one track: the buffered span behind, the played span in
 * front, and a transparent native range input on top that owns interaction and
 * accessibility (keyboard arrows, screen-reader value). Seeking is committed on
 * release rather than on every drag tick — a Spotify seek is a network call, and
 * firing one per pixel would flood it.
 */
export function ProgressBar({
  positionMs,
  durationMs,
  bufferedMs,
  disabled,
  onSeek,
}: ProgressBarProps): JSX.Element {
  // While the user drags, show where they are dragging to rather than where the
  // track still is; commit the seek only when they let go.
  const [scrubMs, setScrubMs] = useState<number | null>(null);

  const shownMs = scrubMs ?? positionMs;
  const playedPercent = durationMs > 0 ? clampPercent((shownMs / durationMs) * 100) : 0;
  const bufferedPercent =
    bufferedMs !== null && durationMs > 0 ? clampPercent((bufferedMs / durationMs) * 100) : 0;

  const commit = (): void => {
    if (scrubMs !== null) {
      onSeek(scrubMs);
      setScrubMs(null);
    }
  };

  return (
    <div className="progress">
      <span className="progress-time" aria-hidden="true">
        {formatDuration(shownMs)}
      </span>

      <div className="progress-track">
        {bufferedMs !== null && (
          <div className="progress-buffered" style={{ width: `${bufferedPercent}%` }} />
        )}
        <div className="progress-played" style={{ width: `${playedPercent}%` }} />
        <input
          type="range"
          className="progress-range"
          min={0}
          max={Math.max(durationMs, 1)}
          step={1000}
          value={Math.min(shownMs, durationMs || shownMs)}
          disabled={disabled}
          aria-label="Seek"
          aria-valuetext={`${formatDuration(shownMs)} of ${formatDuration(durationMs)}`}
          onChange={(event) => setScrubMs(Number(event.target.value))}
          onPointerUp={commit}
          onKeyUp={commit}
          onBlur={commit}
        />
      </div>

      <span className="progress-time" aria-hidden="true">
        {formatDuration(durationMs)}
      </span>
    </div>
  );
}
