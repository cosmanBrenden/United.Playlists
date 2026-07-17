import type { PlaybackTicket, Track } from "../api/types";
import { PROVIDER_LABELS } from "../api/types";
import type { PlayerAdapter, PlayerListener, PlayerState } from "./types";

export interface PlayerOptions {
  readonly adapters: readonly PlayerAdapter[];
  /** Fetches a playback ticket from the backend. Injectable for tests. */
  readonly fetchTicket: (trackKey: string) => Promise<PlaybackTicket>;
}

const INITIAL_STATE: PlayerState = {
  status: "idle",
  track: null,
  positionMs: 0,
  durationMs: 0,
  volume: 1,
  error: null,
};

/**
 * One player over several streaming SDKs.
 *
 * This is what makes a mixed playlist feel like a playlist rather than two apps
 * bolted together. The UI calls `play(track)` and never learns which service is
 * involved; the facade fetches a ticket, picks the adapter matching the ticket's
 * method, and swaps SDKs underneath.
 *
 * Adding a service means writing one {@link PlayerAdapter} and passing it in.
 */
export class Player {
  readonly #adapters: Map<string, PlayerAdapter>;
  readonly #initialised = new Set<string>();
  readonly #fetchTicket: (trackKey: string) => Promise<PlaybackTicket>;
  readonly #listeners = new Set<PlayerListener>();

  #state: PlayerState = INITIAL_STATE;
  #active: PlayerAdapter | null = null;
  #queue: readonly Track[] = [];
  #queueIndex = -1;

  constructor(options: PlayerOptions) {
    this.#adapters = new Map(options.adapters.map((adapter) => [adapter.method, adapter]));
    this.#fetchTicket = options.fetchTicket;
  }

  getState(): PlayerState {
    return this.#state;
  }

  /** @returns an unsubscribe function */
  subscribe(listener: PlayerListener): () => void {
    this.#listeners.add(listener);
    return () => {
      this.#listeners.delete(listener);
    };
  }

  setQueue(tracks: readonly Track[], startIndex = 0): void {
    this.#queue = [...tracks];
    this.#queueIndex = startIndex;
  }

  getQueue(): readonly Track[] {
    return this.#queue;
  }

  getQueueIndex(): number {
    return this.#queueIndex;
  }

  async playQueueItem(index: number): Promise<void> {
    const track = this.#queue[index];
    if (!track) {
      return;
    }
    this.#queueIndex = index;
    await this.play(track);
  }

  /**
   * Plays a track, switching SDKs if it belongs to a different service.
   *
   * Never throws: a failure lands in `state.error` instead, because a playback
   * failure is an everyday event here (expired token, no Premium, region lock) and
   * the UI needs to show it, not catch it.
   */
  async play(track: Track): Promise<void> {
    this.#emit({ ...this.#state, status: "loading", track, error: null });

    try {
      const ticket = await this.#fetchTicket(track.key);
      const adapter = this.#adapters.get(ticket.method);
      if (!adapter) {
        throw new Error(
          `${PROVIDER_LABELS[track.provider]} playback is not supported in this build`,
        );
      }

      // Silence the outgoing service first. Two SDKs left running play over each
      // other, and the user hears both.
      if (this.#active && this.#active !== adapter) {
        await this.#active.stop();
      }

      if (!this.#initialised.has(adapter.method)) {
        await adapter.init();
        this.#initialised.add(adapter.method);
        adapter.onEnded(() => {
          void this.next();
        });
      }

      // Re-apply volume: it belongs to the app, and a freshly swapped-in SDK knows
      // nothing about what the user set earlier.
      await adapter.setVolume(this.#state.volume);
      await adapter.play(ticket);
      this.#active = adapter;

      this.#emit({
        ...this.#state,
        status: "playing",
        track,
        positionMs: 0,
        durationMs: track.durationMs ?? 0,
        error: null,
      });
    } catch (cause) {
      this.#emit({
        ...this.#state,
        status: "error",
        track,
        error: cause instanceof Error ? cause.message : String(cause),
      });
    }
  }

  async pause(): Promise<void> {
    if (!this.#active || this.#state.status !== "playing") {
      return;
    }
    await this.#active.pause();
    this.#emit({ ...this.#state, status: "paused" });
  }

  async resume(): Promise<void> {
    if (!this.#active || this.#state.status !== "paused") {
      return;
    }
    await this.#active.resume();
    this.#emit({ ...this.#state, status: "playing" });
  }

  async toggle(): Promise<void> {
    if (this.#state.status === "playing") {
      await this.pause();
    } else if (this.#state.status === "paused") {
      await this.resume();
    }
  }

  async seek(positionMs: number): Promise<void> {
    if (!this.#active) {
      return;
    }
    await this.#active.seek(positionMs);
    this.#emit({ ...this.#state, positionMs });
  }

  async setVolume(volume: number): Promise<void> {
    const clamped = Math.min(1, Math.max(0, volume));
    this.#emit({ ...this.#state, volume: clamped });
    await this.#active?.setVolume(clamped);
  }

  /** Advances to the next queued track, or stops at the end. */
  async next(): Promise<void> {
    const nextIndex = this.#queueIndex + 1;
    if (nextIndex >= this.#queue.length) {
      // Stop rather than wrap: a playlist that silently loops forever is a
      // surprise, and repeat is an explicit feature, not a default.
      await this.stop();
      return;
    }
    await this.playQueueItem(nextIndex);
  }

  /**
   * Goes back a track, or restarts the current one if already at the start.
   *
   * Matches what every music player does, and avoids an index underflow.
   */
  async previous(): Promise<void> {
    if (this.#queueIndex <= 0) {
      await this.seek(0);
      return;
    }
    await this.playQueueItem(this.#queueIndex - 1);
  }

  async stop(): Promise<void> {
    await this.#active?.stop();
    this.#active = null;
    this.#emit({ ...this.#state, status: "idle", track: null, positionMs: 0 });
  }

  /** Polls the live SDK for its position, for the progress bar. */
  async refreshPosition(): Promise<void> {
    if (!this.#active || this.#state.status !== "playing") {
      return;
    }
    const positionMs = await this.#active.getPositionMs();
    if (positionMs !== null) {
      this.#emit({ ...this.#state, positionMs });
    }
  }

  /** Releases every SDK that was loaded. */
  async dispose(): Promise<void> {
    await this.#active?.stop();
    this.#active = null;
    for (const method of this.#initialised) {
      await this.#adapters.get(method)?.dispose();
    }
    this.#initialised.clear();
    this.#listeners.clear();
  }

  #emit(state: PlayerState): void {
    this.#state = state;
    for (const listener of this.#listeners) {
      try {
        listener(state);
      } catch {
        // A broken listener is a bug in a component, not a reason to stop the
        // music or to deny every other listener the update.
      }
    }
  }
}
