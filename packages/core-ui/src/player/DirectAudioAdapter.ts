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

  async init(): Promise<void> {
    const audio = new Audio();
    audio.preload = "auto";
    audio.addEventListener("ended", () => this.#onEndedCallback?.());
    this.#audio = audio;
  }

  async play(ticket: PlaybackTicket): Promise<void> {
    const streamUrl = ticket.params.streamUrl;
    if (!streamUrl) {
      throw new Error("Playback ticket carried no stream URL");
    }
    const audio = this.#audio;
    if (!audio) {
      throw new Error("Audio element is not ready");
    }
    audio.src = streamUrl;
    audio.load();
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
    if (this.#audio) {
      this.#audio.volume = Math.min(1, Math.max(0, volume));
    }
  }

  async getPositionMs(): Promise<number | null> {
    return this.#audio ? Math.round(this.#audio.currentTime * 1000) : null;
  }

  onEnded(callback: () => void): void {
    this.#onEndedCallback = callback;
  }

  async dispose(): Promise<void> {
    if (this.#audio) {
      this.#audio.pause();
      this.#audio.removeAttribute("src");
      this.#audio = null;
    }
  }
}
