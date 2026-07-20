import { beforeEach, describe, expect, it, vi } from "vitest";
import { DirectAudioAdapter, type HlsEngine, type HlsSupport } from "./DirectAudioAdapter";
import type { PlaybackTicket } from "../api/types";

/** A single buffered span, shaped like the DOM TimeRanges the adapter reads. */
class FakeTimeRanges {
  constructor(private readonly ranges: ReadonlyArray<[number, number]>) {}
  get length(): number {
    return this.ranges.length;
  }
  start(i: number): number {
    return this.ranges[i]?.[0] ?? 0;
  }
  end(i: number): number {
    return this.ranges[i]?.[1] ?? 0;
  }
}

/**
 * A stand-in for HTMLAudioElement. jsdom's Audio does not actually play, so the real
 * one would throw; this records calls instead.
 */
class FakeAudio {
  src = "";
  volume = 1;
  currentTime = 0;
  duration = Number.NaN;
  preload = "";
  playCalls = 0;
  pauseCalls = 0;
  loadCalls = 0;
  buffered: FakeTimeRanges = new FakeTimeRanges([]);
  #listeners = new Map<string, () => void>();
  failPlay?: DOMException;

  addEventListener(event: string, cb: () => void): void {
    this.#listeners.set(event, cb);
  }

  removeAttribute(name: string): void {
    if (name === "src") this.src = "";
  }

  /** Present only when a test opts into native HLS; absent means "Chromium-like". */
  canPlayType?: (type: string) => string;

  load(): void {
    this.loadCalls++;
  }

  async play(): Promise<void> {
    this.playCalls++;
    if (this.failPlay) throw this.failPlay;
  }

  pause(): void {
    this.pauseCalls++;
  }

  fireEnded(): void {
    this.#listeners.get("ended")?.();
  }
}

const ticket = (streamUrl: string): PlaybackTicket => ({
  trackKey: "YOUTUBE:vid1",
  provider: "YOUTUBE",
  method: "DIRECT_AUDIO",
  params: { streamUrl, protocol: "progressive", trackId: "vid1" },
});

/** An HLS ticket, as the backend flags SoundCloud tracks. */
const hlsTicket = (streamUrl: string): PlaybackTicket => ({
  trackKey: "SOUNDCLOUD:t1",
  provider: "SOUNDCLOUD",
  method: "DIRECT_AUDIO",
  params: { streamUrl, protocol: "hls", trackId: "t1" },
});

/** Records how the adapter drives hls.js, standing in for the real engine. */
class FakeHls implements HlsEngine {
  loadedSource?: string;
  attachedTo?: HTMLMediaElement;
  destroyed = false;
  loadSource(url: string): void {
    this.loadedSource = url;
  }
  attachMedia(media: HTMLMediaElement): void {
    this.attachedTo = media;
  }
  destroy(): void {
    this.destroyed = true;
  }
}

/** An HlsSupport whose engines the test can inspect; `supported` toggles capability. */
function fakeHlsSupport(supported = true): HlsSupport & { engines: FakeHls[] } {
  const engines: FakeHls[] = [];
  return {
    engines,
    isSupported: () => supported,
    create: () => {
      const engine = new FakeHls();
      engines.push(engine);
      return engine;
    },
  };
}

describe("DirectAudioAdapter", () => {
  let audios: FakeAudio[];
  let fake: FakeAudio;
  let adapter: DirectAudioAdapter;

  beforeEach(async () => {
    audios = [];
    // Each `new Audio()` yields a fresh element, so the prepared element is distinct
    // from the playing one — the whole point of pre-buffering.
    vi.stubGlobal(
      "Audio",
      vi.fn(() => {
        const a = new FakeAudio();
        audios.push(a);
        return a;
      }),
    );
    adapter = new DirectAudioAdapter();
    await adapter.init();
    fake = audios[0]!;
  });

  it("plays the stream URL from the ticket", async () => {
    await adapter.play(ticket("https://stream.example/audio.webm"));

    expect(fake.src).toBe("https://stream.example/audio.webm");
    expect(fake.playCalls).toBe(1);
  });

  it("rejects a ticket with no stream URL", async () => {
    await expect(
      adapter.play({ ...ticket(""), params: { trackId: "vid1" } }),
    ).rejects.toThrow(/no stream URL/i);
  });

  it("explains an expired stream link rather than leaking a raw DOM error", async () => {
    fake.failPlay = new DOMException("boom", "NotAllowedError");

    await expect(adapter.play(ticket("https://stream.example/a.webm"))).rejects.toThrow(
      /may have expired/i,
    );
  });

  it("explains an unplayable codec", async () => {
    fake.failPlay = new DOMException("boom", "NotSupportedError");

    await expect(adapter.play(ticket("https://stream.example/a.webm"))).rejects.toThrow(
      /format could not be played/i,
    );
  });

  it("clamps volume to 0..1", async () => {
    await adapter.setVolume(5);
    expect(fake.volume).toBe(1);
    await adapter.setVolume(-1);
    expect(fake.volume).toBe(0);
  });

  it("seeks in seconds", async () => {
    await adapter.seek(45000);
    expect(fake.currentTime).toBe(45);
  });

  it("reports position in milliseconds", async () => {
    fake.currentTime = 12.5;
    expect(await adapter.getPositionMs()).toBe(12500);
  });

  it("reports the true duration once known, else null", async () => {
    expect(await adapter.getDurationMs()).toBeNull();
    fake.duration = 183.4;
    expect(await adapter.getDurationMs()).toBe(183400);
  });

  it("reports the buffered end covering the play head", async () => {
    fake.currentTime = 10;
    fake.buffered = new FakeTimeRanges([[0, 42]]);
    expect(await adapter.getBufferedMs()).toBe(42000);
  });

  it("reports zero buffered before anything loads", async () => {
    expect(await adapter.getBufferedMs()).toBe(0);
  });

  it("fires the ended callback so the queue can advance", async () => {
    const onEnded = vi.fn();
    adapter.onEnded(onEnded);

    fake.fireEnded();

    expect(onEnded).toHaveBeenCalledOnce();
  });

  it("releases the source on stop, so a swapped-out service stops downloading", async () => {
    await adapter.play(ticket("https://stream.example/a.webm"));
    await adapter.stop();

    expect(fake.pauseCalls).toBeGreaterThan(0);
    expect(fake.src).toBe("");
  });

  describe("pre-buffering", () => {
    it("pre-loads the next track into a separate element", async () => {
      await adapter.prepare(ticket("https://stream.example/next.webm"));

      const prepared = audios[1]!;
      expect(prepared).not.toBe(fake);
      expect(prepared.src).toBe("https://stream.example/next.webm");
      expect(prepared.loadCalls).toBeGreaterThan(0);
    });

    it("promotes the pre-loaded element on play instead of reloading", async () => {
      const url = "https://stream.example/next.webm";
      await adapter.prepare(ticket(url));
      const prepared = audios[1]!;
      const loadsAfterPrepare = prepared.loadCalls;

      await adapter.play(ticket(url));

      // The warmed-up element plays without a fresh load(); its buffer is reused.
      expect(prepared.playCalls).toBe(1);
      expect(prepared.loadCalls).toBe(loadsAfterPrepare);
      // Position/volume now read from the promoted element.
      prepared.currentTime = 3;
      expect(await adapter.getPositionMs()).toBe(3000);
    });

    it("carries volume onto a promoted element", async () => {
      await adapter.setVolume(0.4);
      const url = "https://stream.example/next.webm";
      await adapter.prepare(ticket(url));

      await adapter.play(ticket(url));

      expect(audios[1]!.volume).toBe(0.4);
    });

    it("falls back to a normal load when play does not match the pre-load", async () => {
      await adapter.prepare(ticket("https://stream.example/next.webm"));

      await adapter.play(ticket("https://stream.example/other.webm"));

      // The unrelated track loads on the main element as usual.
      expect(fake.src).toBe("https://stream.example/other.webm");
      expect(fake.playCalls).toBe(1);
    });
  });

  describe("HLS playback (SoundCloud)", () => {
    let support: ReturnType<typeof fakeHlsSupport>;
    let hlsAdapter: DirectAudioAdapter;
    let hlsFake: FakeAudio;

    beforeEach(async () => {
      support = fakeHlsSupport(true);
      hlsAdapter = new DirectAudioAdapter(support);
      await hlsAdapter.init();
      // The outer beforeEach already created audios[0] for its own adapter; this
      // adapter's element is whichever init() just appended.
      hlsFake = audios[audios.length - 1]!;
    });

    it("drives an HLS ticket through hls.js instead of the src attribute", async () => {
      const url = "https://playback.soundcloud.cloud/x/playlist.m3u8";
      await hlsAdapter.play(hlsTicket(url));

      const engine = support.engines[0]!;
      expect(engine.loadedSource).toBe(url);
      expect(engine.attachedTo).toBe(hlsFake);
      // The element is fed by MSE, so nothing is set on src directly.
      expect(hlsFake.src).toBe("");
      expect(hlsFake.playCalls).toBe(1);
    });

    it("plays HLS natively when the element supports it, skipping hls.js", async () => {
      const url = "https://playback.soundcloud.cloud/x/playlist.m3u8";
      // Simulate Safari/iOS: the element reports it can play HLS itself.
      hlsFake.canPlayType = (t: string) =>
        t === "application/vnd.apple.mpegurl" ? "maybe" : "";

      await hlsAdapter.play(hlsTicket(url));

      expect(support.engines).toHaveLength(0);
      expect(hlsFake.src).toBe(url);
    });

    it("keeps progressive tickets off hls.js", async () => {
      await hlsAdapter.play(ticket("https://stream.example/audio.webm"));

      expect(support.engines).toHaveLength(0);
      expect(hlsFake.src).toBe("https://stream.example/audio.webm");
    });

    it("destroys the engine on stop so the segment fetch loop ends", async () => {
      await hlsAdapter.play(hlsTicket("https://x/playlist.m3u8"));
      const engine = support.engines[0]!;

      await hlsAdapter.stop();

      expect(engine.destroyed).toBe(true);
    });

    it("tears down the previous engine when the next track loads on the same element", async () => {
      await hlsAdapter.play(hlsTicket("https://x/one.m3u8"));
      const first = support.engines[0]!;

      await hlsAdapter.play(hlsTicket("https://x/two.m3u8"));

      expect(first.destroyed).toBe(true);
      expect(support.engines[1]!.loadedSource).toBe("https://x/two.m3u8");
    });

    it("promotes a pre-loaded HLS engine on play rather than recreating it", async () => {
      const url = "https://x/next.m3u8";
      await hlsAdapter.prepare(hlsTicket(url));
      expect(support.engines).toHaveLength(1);
      const prepared = support.engines[0]!;

      await hlsAdapter.play(hlsTicket(url));

      // No second engine created: the warmed-up one is reused.
      expect(support.engines).toHaveLength(1);
      expect(prepared.destroyed).toBe(false);
    });

    it("falls back to src when MSE is unsupported and there is no native HLS", async () => {
      const noSupport = fakeHlsSupport(false);
      const adapter2 = new DirectAudioAdapter(noSupport);
      await adapter2.init();
      const url = "https://x/playlist.m3u8";

      await adapter2.play(hlsTicket(url));

      // Nothing can play it, but we still try src rather than throwing — the play()
      // error path then reports the codec failure to the user.
      expect(noSupport.engines).toHaveLength(0);
      const lastAudio = audios[audios.length - 1]!;
      expect(lastAudio.src).toBe(url);
    });
  });
});
