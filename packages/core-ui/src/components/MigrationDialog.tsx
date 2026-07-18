import { useState } from "react";
import type { ProviderId, Track, UnresolvedMatch } from "../api/types";
import { PROVIDER_LABELS } from "../api/types";
import { formatDuration } from "../util/time";
import { ServiceBadge } from "./ServiceBadge";

export interface MigrationDialogProps {
  readonly target: ProviderId;
  readonly unresolved: readonly UnresolvedMatch[];
  /**
   * Applies one manual choice. Resolves on success; rejects (with a message) if the
   * replace fails so the card can show it and stay open.
   */
  readonly onResolve: (match: UnresolvedMatch, candidate: Track) => Promise<void>;
  readonly onClose: () => void;
}

/** Per-track state as the user works through the list. */
type CardState =
  | { readonly kind: "pending" }
  | { readonly kind: "resolving" }
  | { readonly kind: "skipped" }
  | { readonly kind: "resolved"; readonly to: Track }
  | { readonly kind: "error"; readonly message: string };

/**
 * Resolves the tracks a migration could not match on its own.
 *
 * Each track that needs a human decision gets a card showing the original and the
 * candidate matches from *every* service — the target's own results first, then the
 * rest — so the user can pick the right one, keep it where it is (Skip), or settle
 * for a copy on a different service when the target simply doesn't carry the song.
 * A pick replaces the track immediately and collapses the card; the rest stay open.
 */
export function MigrationDialog({
  target,
  unresolved,
  onResolve,
  onClose,
}: MigrationDialogProps): JSX.Element {
  const [states, setStates] = useState<Readonly<Record<number, CardState>>>({});

  const stateFor = (position: number): CardState =>
    states[position] ?? { kind: "pending" };

  const setState = (position: number, state: CardState): void =>
    setStates((prev) => ({ ...prev, [position]: state }));

  const pick = async (match: UnresolvedMatch, candidate: Track): Promise<void> => {
    setState(match.position, { kind: "resolving" });
    try {
      await onResolve(match, candidate);
      setState(match.position, { kind: "resolved", to: candidate });
    } catch (cause) {
      setState(match.position, {
        kind: "error",
        message: cause instanceof Error ? cause.message : "Could not replace the track",
      });
    }
  };

  const outstanding = unresolved.filter(
    (match) => stateFor(match.position).kind === "pending" || stateFor(match.position).kind === "error",
  ).length;

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        className="modal migration-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="migration-title"
        onKeyDown={(event) => {
          if (event.key === "Escape") {
            onClose();
          }
        }}
      >
        <h2 id="migration-title">Choose matches on {PROVIDER_LABELS[target]}</h2>
        <p className="meta">
          {unresolved.length} {unresolved.length === 1 ? "track" : "tracks"} had no exact match.
          Pick the right one, or keep the original.
        </p>

        <div className="migration-list">
          {unresolved.map((match) => {
            const state = stateFor(match.position);
            return (
              <section key={match.position} className="migration-card">
                <div className="migration-source">
                  <div className="track-meta">
                    <span className="track-title">{match.source.title}</span>
                    <span className="track-artist">{match.source.artistLine}</span>
                  </div>
                  <ServiceBadge provider={match.source.provider} />
                  <span className="duration">{formatDuration(match.source.durationMs)}</span>
                </div>

                {state.kind === "resolved" ? (
                  <p className="migration-outcome resolved" role="status">
                    ✓ Replaced with “{state.to.title}” on {PROVIDER_LABELS[state.to.provider]}
                  </p>
                ) : state.kind === "skipped" ? (
                  <p className="migration-outcome">
                    Kept the original on {PROVIDER_LABELS[match.source.provider]}.
                  </p>
                ) : (
                  <>
                    {match.candidates.length === 0 ? (
                      <p className="status">No candidates found on any service.</p>
                    ) : (
                      <ul className="migration-candidates">
                        {match.candidates.map((candidate) => (
                          <li
                            key={candidate.key}
                            className={
                              candidate.provider === target
                                ? "migration-candidate on-target"
                                : "migration-candidate"
                            }
                          >
                            {candidate.artworkUrl ? (
                              <img
                                src={candidate.artworkUrl}
                                alt=""
                                className="artwork"
                                loading="lazy"
                              />
                            ) : (
                              <div className="artwork artwork-placeholder" aria-hidden="true" />
                            )}
                            <div className="track-meta">
                              <span className="track-title">{candidate.title}</span>
                              <span className="track-artist">{candidate.artistLine}</span>
                            </div>
                            <ServiceBadge provider={candidate.provider} />
                            <span className="duration">
                              {formatDuration(candidate.durationMs)}
                            </span>
                            <button
                              type="button"
                              className="primary"
                              disabled={state.kind === "resolving"}
                              onClick={() => void pick(match, candidate)}
                            >
                              {state.kind === "resolving" ? "…" : "Use this"}
                            </button>
                          </li>
                        ))}
                      </ul>
                    )}

                    {state.kind === "error" && (
                      <p className="status error" role="alert">
                        {state.message}
                      </p>
                    )}

                    <div className="migration-card-actions">
                      <button
                        type="button"
                        disabled={state.kind === "resolving"}
                        onClick={() => setState(match.position, { kind: "skipped" })}
                      >
                        Keep original
                      </button>
                    </div>
                  </>
                )}
              </section>
            );
          })}
        </div>

        <div className="modal-actions">
          <button type="button" className="primary" onClick={onClose}>
            {outstanding === 0 ? "Done" : `Done (${outstanding} left)`}
          </button>
        </div>
      </div>
    </div>
  );
}
