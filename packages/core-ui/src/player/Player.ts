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
  bufferedMs: null,
  volume: 1,
  error: null,
  queue: [],
  queueIndex: -1,
  shuffle: false,
};

/** Fisher-Yates. Returns a fresh array; never mutates the input. */
function shuffled<T>(items: readonly T[]): T[] {
  const copy = [...items];
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [copy[i], copy[j]] = [copy[j] as T, copy[i] as T];
  }
  return copy;
}

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
  #queue: Track[] = [];
  #queueIndex = -1;
  #shuffle = false;

  /**
   * A ticket fetched ahead of time for the track after the current one, so the next
   * `play` skips the network round-trip. Keyed by track so a stale prefetch (queue
   * edited after it was requested) is discarded rather than played.
   */
  #prefetched: { trackKey: string; ticket: PlaybackTicket } | null = null;

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
    this.#invalidatePrefetch();
    this.#emitQueue();
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
   * Sets a queue in a random order and starts it. "Basic RNG is good enough": a
   * single up-front shuffle, not a re-roll on every advance, so the upcoming order
   * is stable and legible in the queue panel.
   */
  async playShuffled(tracks: readonly Track[]): Promise<void> {
    this.#shuffle = true;
    this.setQueue(shuffled(tracks), 0);
    await this.playQueueItem(0);
  }

  /**
   * Turns shuffle on or off. Turning it on reshuffles only the tracks still ahead,
   * leaving the current one playing and history intact — the same thing every music
   * player does, and the least surprising.
   */
  setShuffle(on: boolean): void {
    this.#shuffle = on;
    if (on && this.#queueIndex >= 0 && this.#queueIndex < this.#queue.length - 1) {
      const played = this.#queue.slice(0, this.#queueIndex + 1);
      const upcoming = shuffled(this.#queue.slice(this.#queueIndex + 1));
      this.#queue = [...played, ...upcoming];
      this.#invalidatePrefetch();
      void this.#prefetchNext();
    }
    this.#emitQueue();
  }

  isShuffle(): boolean {
    return this.#shuffle;
  }

  /** Appends a track to the end of the queue. */
  addToQueue(track: Track): void {
    this.#queue = [...this.#queue, track];
    this.#emitQueue();
    void this.#prefetchNext();
  }

  /**
   * Removes the queue item at `index`.
   *
   * Removing the currently playing track does not stop it — it keeps playing until
   * it ends — but the index is walked back one so the following track still plays
   * next rather than being skipped.
   */
  removeFromQueue(index: number): void {
    if (index < 0 || index >= this.#queue.length) {
      return;
    }
    this.#queue = this.#queue.filter((_, i) => i !== index);
    if (index <= this.#queueIndex) {
      this.#queueIndex--;
    }
    this.#invalidatePrefetch();
    this.#emitQueue();
    void this.#prefetchNext();
  }

  /**
   * Moves the queue item at `from` to `to`, keeping the currently playing track
   * pointed at wherever it lands so auto-advance stays correct after a reorder.
   */
  moveInQueue(from: number, to: number): void {
    const size = this.#queue.length;
    if (from < 0 || from >= size || to < 0 || to >= size || from === to) {
      return;
    }
    const current = this.#queue[this.#queueIndex];
    const next = [...this.#queue];
    const [moved] = next.splice(from, 1);
    next.splice(to, 0, moved as Track);
    this.#queue = next;
    // Recover the current index by identity: the reorder shifts positions and the
    // playing track must not be lost track of.
    if (current) {
      const found = next.indexOf(current);
      if (found >= 0) {
        this.#queueIndex = found;
      }
    }
    this.#invalidatePrefetch();
    this.#emitQueue();
    void this.#prefetchNext();
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
      const ticket = await this.#ticketFor(track.key);
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
        bufferedMs: null,
        error: null,
      });

      // Warm up whatever comes next so the end-of-track swap is seamless.
      void this.#prefetchNext();
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
    this.#invalidatePrefetch();
    this.#emit({ ...this.#state, status: "idle", track: null, positionMs: 0, bufferedMs: null });
  }

  /**
   * Polls the live SDK for its position, buffered amount and true duration, for the
   * progress bar.
   */
  async refreshProgress(): Promise<void> {
    if (!this.#active || this.#state.status !== "playing") {
      return;
    }
    const [positionMs, bufferedMs, durationMs] = await Promise.all([
      this.#active.getPositionMs(),
      this.#active.getBufferedMs(),
      this.#active.getDurationMs(),
    ]);
    this.#emit({
      ...this.#state,
      positionMs: positionMs ?? this.#state.positionMs,
      bufferedMs,
      // Keep the metadata estimate until the SDK reports a real, finite duration.
      durationMs: durationMs !== null && durationMs > 0 ? durationMs : this.#state.durationMs,
    });
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

  /**
   * Returns a ticket for a track, using the prefetched one when it matches so a
   * queued advance does not wait on the network.
   */
  async #ticketFor(trackKey: string): Promise<PlaybackTicket> {
    if (this.#prefetched?.trackKey === trackKey) {
      const ticket = this.#prefetched.ticket;
      this.#prefetched = null;
      return ticket;
    }
    return this.#fetchTicket(trackKey);
  }

  /**
   * Fetches the next track's ticket ahead of time and asks its adapter to pre-load
   * it. Best-effort throughout: any failure is swallowed, because a missed prefetch
   * only costs the normal load time when the track actually plays.
   */
  async #prefetchNext(): Promise<void> {
    const next = this.#queue[this.#queueIndex + 1];
    if (!next) {
      this.#prefetched = null;
      return;
    }
    if (this.#prefetched?.trackKey === next.key) {
      return;
    }
    try {
      const ticket = await this.#fetchTicket(next.key);
      // The queue may have changed while we waited; only keep the ticket if it is
      // still the upcoming track.
      if (this.#queue[this.#queueIndex + 1]?.key !== next.key) {
        return;
      }
      this.#prefetched = { trackKey: next.key, ticket };
      await this.#adapters.get(ticket.method)?.prepare?.(ticket);
    } catch {
      // A prefetch failure is invisible by design: the track still loads on play.
      this.#prefetched = null;
    }
  }

  #invalidatePrefetch(): void {
    this.#prefetched = null;
  }

  #emitQueue(): void {
    this.#emit({
      ...this.#state,
      queue: this.#queue,
      queueIndex: this.#queueIndex,
      shuffle: this.#shuffle,
    });
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
