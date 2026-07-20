import Hls from "hls.js";
import type { PlaybackMethod, PlaybackTicket } from "../api/types";
import type { PlayerAdapter } from "./types";

/**
 * The slice of hls.js this adapter uses, isolated behind an interface so the adapter
 * can be unit-tested without a real MediaSource (which jsdom lacks) and so the browser
 * dependency has exactly one seam.
 */
export interface HlsEngine {
  loadSource(url: string): void;
  attachMedia(media: HTMLMediaElement): void;
  destroy(): void;
}

/** Capability + factory for HLS playback, injectable for tests. */
export interface HlsSupport {
  /** Whether Media Source Extensions can drive hls.js in this runtime. */
  isSupported(): boolean;
  create(): HlsEngine;
}

/** The real hls.js, used in the browser/Electron. */
const defaultHlsSupport: HlsSupport = {
  isSupported: () => Hls.isSupported(),
  create: () => new Hls({ enableWorker: true }),
};

/** One audio element and the HLS engine (if any) feeding it. */
interface Source {
  url: string;
  audio: HTMLAudioElement;
  hls: HlsEngine | null;
}

/**
 * Plays a direct audio stream URL through a plain HTML5 {@code <audio>} element.
 *
 * <p>Used by the scraper-backed services (YouTube and SoundCloud via NewPipe), where
 * the backend resolves a real stream URL rather than delegating to a vendor SDK. That
 * makes this the simplest adapter by far — no SDK to load, no DRM, no hidden iframe.
 *
 * <p>Two delivery protocols arrive here, distinguished by the ticket's {@code protocol}
 * param. A <em>progressive</em> URL (YouTube, most audio) plays straight through the
 * element. An <em>HLS</em> playlist (SoundCloud serves only these for most tracks) does
 * not: Chromium — and therefore Electron — ships no native HLS, so a plain element
 * silently fails with "audio format could not be played". For HLS this adapter drives
 * the element through {@link https://github.com/video-dev/hls.js hls.js} via Media
 * Source Extensions, and falls back to the element's own native HLS where it exists
 * (Safari/iOS), which is what makes SoundCloud play at all.
 *
 * <p>The stream URL is time-limited and tied to the requesting IP, so the backend
 * resolves it fresh for every {@code play} rather than caching a ticket.
 */
export class DirectAudioAdapter implements PlayerAdapter {
  readonly method: PlaybackMethod = "DIRECT_AUDIO";

  #source: Source | null = null;
  #onEndedCallback?: () => void;
  #volume = 1;
  readonly #hlsSupport: HlsSupport;

  /**
   * The next track, pre-loaded while the current one plays. Holding a second element
   * (and, for HLS, a second engine) warmed up means the gap between tracks is as small
   * as the browser allows.
   */
  #prepared: Source | null = null;

  constructor(hlsSupport: HlsSupport = defaultHlsSupport) {
    this.#hlsSupport = hlsSupport;
  }

  async init(): Promise<void> {
    this.#source = { url: "", audio: this.#makeAudio(), hls: null };
  }

  async prepare(ticket: PlaybackTicket): Promise<void> {
    const streamUrl = ticket.params.streamUrl;
    if (!streamUrl || this.#prepared?.url === streamUrl) {
      return;
    }
    // Drop any earlier pre-load: only the immediate next track is worth holding a
    // connection open for.
    this.#releasePrepared();
    this.#prepared = this.#load(this.#makeAudio(), streamUrl, ticket);
  }

  async play(ticket: PlaybackTicket): Promise<void> {
    const streamUrl = ticket.params.streamUrl;
    if (!streamUrl) {
      throw new Error("Playback ticket carried no stream URL");
    }

    // If this is the track we pre-loaded, swap the warmed-up source in rather than
    // reloading the stream from scratch.
    if (this.#prepared?.url === streamUrl) {
      const promoted = this.#prepared;
      this.#prepared = null;
      this.#teardown(this.#source);
      this.#source = promoted;
    } else {
      if (!this.#source) {
        throw new Error("Audio element is not ready");
      }
      // Reuse the live element, but discard any HLS engine bound to the previous track
      // before attaching the new source to it.
      const audio = this.#source.audio;
      this.#detachEngine(this.#source);
      this.#source = this.#load(audio, streamUrl, ticket);
    }

    const audio = this.#source.audio;
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
    await this.#source?.audio.play();
  }

  async pause(): Promise<void> {
    this.#source?.audio.pause();
  }

  async stop(): Promise<void> {
    if (this.#source) {
      const audio = this.#source.audio;
      audio.pause();
      // Releasing the source stops the download and frees the connection, which
      // matters when swapping between services mid-playlist. For HLS that means tearing
      // down the engine, which owns the fetch loop the <audio> element does not.
      this.#detachEngine(this.#source);
      audio.removeAttribute("src");
      audio.load();
      this.#source.url = "";
    }
  }

  async seek(positionMs: number): Promise<void> {
    if (this.#source) {
      this.#source.audio.currentTime = positionMs / 1000;
    }
  }

  async setVolume(volume: number): Promise<void> {
    this.#volume = Math.min(1, Math.max(0, volume));
    if (this.#source) {
      this.#source.audio.volume = this.#volume;
    }
  }

  async getPositionMs(): Promise<number | null> {
    return this.#source ? Math.round(this.#source.audio.currentTime * 1000) : null;
  }

  async getBufferedMs(): Promise<number | null> {
    const audio = this.#source?.audio;
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
    const duration = this.#source?.audio.duration;
    return duration !== undefined && Number.isFinite(duration) && duration > 0
      ? Math.round(duration * 1000)
      : null;
  }

  onEnded(callback: () => void): void {
    this.#onEndedCallback = callback;
  }

  async dispose(): Promise<void> {
    this.#releasePrepared();
    this.#teardown(this.#source);
    this.#source = null;
  }

  /**
   * Points an element at a stream. Progressive URLs (and HLS where the element plays it
   * natively, i.e. Safari/iOS) go straight on {@code src}; otherwise an hls.js engine is
   * attached to drive the element via MSE.
   */
  #load(audio: HTMLAudioElement, url: string, ticket: PlaybackTicket): Source {
    if (this.#needsHlsEngine(audio, url, ticket)) {
      const hls = this.#hlsSupport.create();
      hls.loadSource(url);
      hls.attachMedia(audio);
      return { url, audio, hls };
    }
    audio.src = url;
    audio.load();
    return { url, audio, hls: null };
  }

  /**
   * True when the stream is HLS and the element cannot play it itself, so hls.js has to.
   * The protocol is authoritative (the backend flags SoundCloud as "hls"); the {@code
   * .m3u8} check is a defensive fallback for any ticket that predates the flag.
   */
  #needsHlsEngine(audio: HTMLAudioElement, url: string, ticket: PlaybackTicket): boolean {
    const isHls = ticket.params.protocol === "hls" || /\.m3u8(\?|$)/i.test(url);
    if (!isHls) {
      return false;
    }
    // Safari/iOS play HLS natively; there, hls.js is neither needed nor wanted.
    const nativeHls =
      typeof audio.canPlayType === "function" &&
      audio.canPlayType("application/vnd.apple.mpegurl") !== "";
    return !nativeHls && this.#hlsSupport.isSupported();
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
      this.#teardown(this.#prepared);
      this.#prepared = null;
    }
  }

  /** Destroys the HLS engine on a source, if any, leaving the element reusable. */
  #detachEngine(source: Source): void {
    if (source.hls) {
      source.hls.destroy();
      source.hls = null;
    }
  }

  #teardown(source: Source | null): void {
    if (source) {
      this.#detachEngine(source);
      source.audio.pause();
      source.audio.removeAttribute("src");
      source.audio.load();
    }
  }
}
