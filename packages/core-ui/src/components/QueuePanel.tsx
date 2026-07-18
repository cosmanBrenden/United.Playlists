import type { Track } from "../api/types";
import { ServiceBadge } from "./ServiceBadge";

export interface QueuePanelProps {
  readonly queue: readonly Track[];
  readonly currentIndex: number;
  readonly onClose: () => void;
  readonly onJump: (index: number) => void;
  readonly onRemove: (index: number) => void;
  readonly onMove: (from: number, to: number) => void;
}

/**
 * The play queue, as a panel the user can open and rearrange.
 *
 * Shows the whole queue with the current track marked and the rest labelled
 * "Next up", and lets the user jump to, reorder, or drop any entry. Editing goes
 * straight to the {@link Player}, which keeps its own index pointed at the playing
 * track through every change, so the panel only has to describe the intent.
 */
export function QueuePanel({
  queue,
  currentIndex,
  onClose,
  onJump,
  onRemove,
  onMove,
}: QueuePanelProps): JSX.Element {
  return (
    <aside className="queue-panel" aria-label="Play queue">
      <header className="queue-header">
        <h2>Queue</h2>
        <button type="button" onClick={onClose} aria-label="Close queue">
          ✕
        </button>
      </header>

      {queue.length === 0 ? (
        <p className="status">The queue is empty. Play a playlist or a search result.</p>
      ) : (
        <ol className="queue-list">
          {queue.map((track, index) => {
            const isCurrent = index === currentIndex;
            const isPast = index < currentIndex;
            return (
              <li
                key={`${track.key}-${index}`}
                className={`queue-row${isCurrent ? " current" : ""}${isPast ? " past" : ""}`}
              >
                <button
                  type="button"
                  className="queue-jump"
                  onClick={() => onJump(index)}
                  aria-label={isCurrent ? `${track.title} (now playing)` : `Play ${track.title}`}
                  aria-current={isCurrent}
                >
                  <span className="queue-index" aria-hidden="true">
                    {isCurrent ? "▶" : index + 1}
                  </span>
                  <div className="track-meta">
                    <span className="track-title">{track.title}</span>
                    <span className="track-artist">{track.artistLine}</span>
                  </div>
                  <ServiceBadge provider={track.provider} />
                </button>

                <div className="queue-actions">
                  <button
                    type="button"
                    onClick={() => onMove(index, index - 1)}
                    disabled={index === 0}
                    aria-label={`Move ${track.title} up`}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    onClick={() => onMove(index, index + 1)}
                    disabled={index === queue.length - 1}
                    aria-label={`Move ${track.title} down`}
                  >
                    ↓
                  </button>
                  <button
                    type="button"
                    className="danger"
                    onClick={() => onRemove(index)}
                    aria-label={`Remove ${track.title} from the queue`}
                  >
                    ✕
                  </button>
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </aside>
  );
}
