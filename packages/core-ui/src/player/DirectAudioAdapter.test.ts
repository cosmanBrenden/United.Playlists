import { beforeEach, describe, expect, it, vi } from "vitest";
import { DirectAudioAdapter } from "./DirectAudioAdapter";
import type { PlaybackTicket } from "../api/types";

/**
 * A stand-in for HTMLAudioElement. jsdom's Audio does not actually play, so the real
 * one would throw; this records calls instead.
 */
class FakeAudio {
  src = "";
  volume = 1;
  currentTime = 0;
  preload = "";
  playCalls = 0;
  pauseCalls = 0;
  loadCalls = 0;
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
  let fake: FakeAudio;
  let adapter: DirectAudioAdapter;

  beforeEach(async () => {
    fake = new FakeAudio();
    vi.stubGlobal("Audio", vi.fn(() => fake));
    adapter = new DirectAudioAdapter();
    await adapter.init();
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
});
