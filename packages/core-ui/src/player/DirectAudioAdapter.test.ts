import { beforeEach, describe, expect, it, vi } from "vitest";
import { DirectAudioAdapter } from "./DirectAudioAdapter";
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
  params: { streamUrl, trackId: "vid1" },
});

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
});
