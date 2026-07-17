import type { PlaybackMethod, PlaybackTicket } from "../api/types";
import type { PlayerAdapter } from "./types";

/**
 * Plays a direct audio stream URL through a plain HTML5 {@code <audio>} element.
 *
 * <p>Used by the scraper-backed services (YouTube and SoundCloud via NewPipe), where
 * the backend resolves a real stream URL rather than delegating to a vendor SDK. That
 * makes this the simplest adapter by far — no SDK to load, no DRM, no hidden iframe —
 * which is one of the quieter wins of the scraper approach.
 *
 * <p>The stream URL is time-limited and tied to the requesting IP, so the backend
 * resolves it fresh for every {@code play} rather than caching a ticket.
 */
export class DirectAudioAdapter implements PlayerAdapter {
  readonly method: PlaybackMethod = "DIRECT_AUDIO";

  #audio: HTMLAudioElement | null = null;
  #onEndedCallback?: () => void;
  #volume = 1;

  /**
   * A second element holding the next track, pre-loaded while the current one plays.
   * When that track's `play` arrives we promote this element instead of starting a
   * fresh download, so the gap between tracks is as small as the browser allows.
   */
  #prepared: { url: string; audio: HTMLAudioElement } | null = null;

  async init(): Promise<void> {
    this.#audio = this.#makeAudio();
  }

  async prepare(ticket: PlaybackTicket): Promise<void> {
    const streamUrl = ticket.params.streamUrl;
    if (!streamUrl || this.#prepared?.url === streamUrl) {
      return;
    }
    // Drop any earlier pre-load: only the immediate next track is worth holding a
    // connection open for.
    this.#releasePrepared();
    const audio = this.#makeAudio();
    audio.src = streamUrl;
    audio.load();
    this.#prepared = { url: streamUrl, audio };
  }

  async play(ticket: PlaybackTicket): Promise<void> {
    const streamUrl = ticket.params.streamUrl;
    if (!streamUrl) {
      throw new Error("Playback ticket carried no stream URL");
    }

    // If this is the track we pre-loaded, swap the warmed-up element in rather than
    // reloading the stream from scratch.
    if (this.#prepared?.url === streamUrl) {
      const promoted = this.#prepared.audio;
      this.#prepared = null;
      this.#teardown(this.#audio);
      this.#audio = promoted;
    } else {
      if (!this.#audio) {
        throw new Error("Audio element is not ready");
      }
      this.#audio.src = streamUrl;
      this.#audio.load();
    }

    const audio = this.#audio;
    if (!audio) {
      throw new Error("Audio element is not ready");
    }
    audio.volume = this.#volume;
    try {
      await audio.play();
    } catch (cause) {
      // A stream URL that has expired, or a codec the browser will not decode, lands
      // here. Surface it as a normal playback failure rather than an unhandled
      // rejection, so the player bar can show it. The name is read defensively:
      // DOMException is not always instanceof Error (jsdom, some runtimes), so relying
      // on the prototype chain would misclassify a codec failure as an expiry.
      const name =
        typeof cause === "object" && cause !== null && "name" in cause
          ? String((cause as { name: unknown }).name)
          : "";
      throw new Error(
        name === "NotSupportedError"
          ? "This track's audio format could not be played."
          : "Could not start playback. The stream link may have expired — try again.",
      );
    }
  }

  async resume(): Promise<void> {
    await this.#audio?.play();
  }

  async pause(): Promise<void> {
    this.#audio?.pause();
  }

  async stop(): Promise<void> {
    if (this.#audio) {
      this.#audio.pause();
      // Releasing the source stops the download and frees the connection, which
      // matters when swapping between services mid-playlist.
      this.#audio.removeAttribute("src");
      this.#audio.load();
    }
  }

  async seek(positionMs: number): Promise<void> {
    if (this.#audio) {
      this.#audio.currentTime = positionMs / 1000;
    }
  }

  async setVolume(volume: number): Promise<void> {
    this.#volume = Math.min(1, Math.max(0, volume));
    if (this.#audio) {
      this.#audio.volume = this.#volume;
    }
  }

  async getPositionMs(): Promise<number | null> {
    return this.#audio ? Math.round(this.#audio.currentTime * 1000) : null;
  }

  async getBufferedMs(): Promise<number | null> {
    const audio = this.#audio;
    if (!audio) {
      return null;
    }
    const ranges = audio.buffered;
    if (ranges.length === 0) {
      return 0;
    }
    // Report how far the contiguous buffer covering the play head reaches. That is
    // the span that would keep playing if the network dropped, which is what the
    // "loaded" portion of a progress bar means to a user.
    const now = audio.currentTime;
    for (let i = 0; i < ranges.length; i++) {
      if (now >= ranges.start(i) && now <= ranges.end(i)) {
        return Math.round(ranges.end(i) * 1000);
      }
    }
    // Play head is not inside any range yet (just seeked): fall back to the furthest
    // buffered point so the bar still shows progress.
    return Math.round(ranges.end(ranges.length - 1) * 1000);
  }

  async getDurationMs(): Promise<number | null> {
    const duration = this.#audio?.duration;
    return duration !== undefined && Number.isFinite(duration) && duration > 0
      ? Math.round(duration * 1000)
      : null;
  }

  onEnded(callback: () => void): void {
    this.#onEndedCallback = callback;
  }

  async dispose(): Promise<void> {
    this.#releasePrepared();
    this.#teardown(this.#audio);
    this.#audio = null;
  }

  /** Creates an element wired to fire the shared end-of-track callback. */
  #makeAudio(): HTMLAudioElement {
    const audio = new Audio();
    audio.preload = "auto";
    audio.volume = this.#volume;
    // Read the callback live rather than closing over it: the same element outlives
    // any single onEnded registration.
    audio.addEventListener("ended", () => this.#onEndedCallback?.());
    return audio;
  }

  #releasePrepared(): void {
    if (this.#prepared) {
      this.#teardown(this.#prepared.audio);
      this.#prepared = null;
    }
  }

  #teardown(audio: HTMLAudioElement | null): void {
    if (audio) {
      audio.pause();
      audio.removeAttribute("src");
      audio.load();
    }
  }
}
