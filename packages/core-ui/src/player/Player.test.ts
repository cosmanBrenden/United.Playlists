import { beforeEach, describe, expect, it, vi } from "vitest";
import { Player } from "./Player";
import type { PlayerAdapter, PlayerState } from "./types";
import type { PlaybackMethod, PlaybackTicket, Track } from "../api/types";

/** A recording stand-in for a real SDK. */
class FakeAdapter implements PlayerAdapter {
  initCalls = 0;
  playCalls: PlaybackTicket[] = [];
  stopCalls = 0;
  disposeCalls = 0;
  pauseCalls = 0;
  resumeCalls = 0;
  seekCalls: number[] = [];
  volumeCalls: number[] = [];
  positionMs: number | null = 0;
  failInit?: Error;
  failPlay?: Error;
  #onEnded?: () => void;

  constructor(readonly method: PlaybackMethod) {}

  async init(): Promise<void> {
    this.initCalls++;
    if (this.failInit) throw this.failInit;
  }

  async play(ticket: PlaybackTicket): Promise<void> {
    this.playCalls.push(ticket);
    if (this.failPlay) throw this.failPlay;
  }

  async resume(): Promise<void> {
    this.resumeCalls++;
  }

  async pause(): Promise<void> {
    this.pauseCalls++;
  }

  async stop(): Promise<void> {
    this.stopCalls++;
  }

  async seek(positionMs: number): Promise<void> {
    this.seekCalls.push(positionMs);
  }

  async setVolume(volume: number): Promise<void> {
    this.volumeCalls.push(volume);
  }

  async getPositionMs(): Promise<number | null> {
    return this.positionMs;
  }

  onEnded(callback: () => void): void {
    this.#onEnded = callback;
  }

  async dispose(): Promise<void> {
    this.disposeCalls++;
  }

  /** Test hook: pretend the track finished. */
  fireEnded(): void {
    this.#onEnded?.();
  }
}

const track = (provider: "SPOTIFY" | "YOUTUBE", id: string, title: string): Track => ({
  key: `${provider}:${id}`,
  provider,
  providerTrackId: id,
  title,
  artists: ["An Artist"],
  artistLine: "An Artist",
  album: null,
  durationMs: 200000,
  artworkUrl: null,
  playable: true,
});

const ticketFor = (t: Track): PlaybackTicket => ({
  trackKey: t.key,
  provider: t.provider,
  method: t.provider === "SPOTIFY" ? "SPOTIFY_WEB_SDK" : "DIRECT_AUDIO",
  params:
    t.provider === "SPOTIFY"
      ? { uri: `spotify:track:${t.providerTrackId}` }
      : { videoId: t.providerTrackId },
});

describe("Player", () => {
  let spotify: FakeAdapter;
  let youtube: FakeAdapter;
  let fetchTicket: ReturnType<typeof vi.fn>;
  let player: Player;

  const spotifyTrack = track("SPOTIFY", "abc", "A Spotify Song");
  const youtubeTrack = track("YOUTUBE", "dQw4w9WgXcQ", "A YouTube Song");

  beforeEach(() => {
    spotify = new FakeAdapter("SPOTIFY_WEB_SDK");
    youtube = new FakeAdapter("DIRECT_AUDIO");
    fetchTicket = vi.fn(async (key: string) =>
      ticketFor(key.startsWith("SPOTIFY") ? spotifyTrack : youtubeTrack),
    );
    player = new Player({
      adapters: [spotify, youtube],
      fetchTicket: fetchTicket as unknown as (key: string) => Promise<PlaybackTicket>,
    });
  });

  describe("routing to the right SDK", () => {
    it("plays a Spotify track through the Spotify adapter", async () => {
      await player.play(spotifyTrack);

      expect(spotify.playCalls).toHaveLength(1);
      expect(spotify.playCalls[0]?.params.uri).toBe("spotify:track:abc");
      expect(youtube.playCalls).toHaveLength(0);
    });

    it("plays a YouTube track through the YouTube adapter", async () => {
      await player.play(youtubeTrack);

      expect(youtube.playCalls).toHaveLength(1);
      expect(youtube.playCalls[0]?.params.videoId).toBe("dQw4w9WgXcQ");
      expect(spotify.playCalls).toHaveLength(0);
    });

    it("initialises an SDK lazily, and only once", async () => {
      expect(spotify.initCalls).toBe(0);

      await player.play(spotifyTrack);
      await player.play(track("SPOTIFY", "def", "Another Spotify Song"));

      // Loading every SDK up front would cost startup time for services the user
      // may never play, and Spotify's SDK demands a token the moment it loads.
      expect(spotify.initCalls).toBe(1);
      expect(youtube.initCalls).toBe(0);
    });

    it("errors clearly when no adapter handles the service", async () => {
      const appleTrack = { ...spotifyTrack, key: "APPLE_MUSIC:x", provider: "APPLE_MUSIC" as const };
      fetchTicket.mockResolvedValue({
        trackKey: "APPLE_MUSIC:x",
        provider: "APPLE_MUSIC",
        method: "APPLE_MUSICKIT_JS",
        params: {},
      });

      await player.play(appleTrack);

      expect(player.getState().status).toBe("error");
      expect(player.getState().error).toMatch(/Apple Music/i);
    });
  });

  describe("crossing services mid-playlist", () => {
    it("stops the previous service before starting the next", async () => {
      await player.play(spotifyTrack);
      await player.play(youtubeTrack);

      // Without this the Spotify SDK keeps playing underneath the YouTube one and
      // the user hears both at once.
      expect(spotify.stopCalls).toBe(1);
      expect(youtube.playCalls).toHaveLength(1);
      expect(player.getState().track?.provider).toBe("YOUTUBE");
    });

    it("does not stop the adapter when staying on the same service", async () => {
      await player.play(spotifyTrack);
      await player.play(track("SPOTIFY", "def", "Another"));

      expect(spotify.stopCalls).toBe(0);
      expect(spotify.playCalls).toHaveLength(2);
    });

    it("carries volume across an adapter swap", async () => {
      await player.setVolume(0.3);
      await player.play(spotifyTrack);
      await player.play(youtubeTrack);

      // Volume is a property of the app, not of whichever SDK happens to be live.
      expect(youtube.volumeCalls).toContain(0.3);
      expect(player.getState().volume).toBe(0.3);
    });

    it("plays a queue that alternates services", async () => {
      player.setQueue([spotifyTrack, youtubeTrack], 0);
      await player.playQueueItem(0);
      expect(player.getState().track?.provider).toBe("SPOTIFY");

      await player.next();

      expect(player.getState().track?.provider).toBe("YOUTUBE");
      expect(spotify.stopCalls).toBe(1);
    });
  });

  describe("queue", () => {
    beforeEach(() => {
      player.setQueue([spotifyTrack, youtubeTrack], 0);
    });

    it("advances automatically when a track ends", async () => {
      await player.playQueueItem(0);

      spotify.fireEnded();
      await vi.waitFor(() => {
        expect(player.getState().track?.key).toBe(youtubeTrack.key);
      });
    });

    it("stops at the end of the queue rather than wrapping", async () => {
      await player.playQueueItem(1);

      await player.next();

      expect(player.getState().status).toBe("idle");
      expect(player.getState().track).toBeNull();
    });

    it("goes back to the previous track", async () => {
      await player.playQueueItem(1);

      await player.previous();

      expect(player.getState().track?.key).toBe(spotifyTrack.key);
    });

    it("previous at the start restarts the track instead of underflowing", async () => {
      await player.playQueueItem(0);

      await player.previous();

      expect(spotify.seekCalls).toContain(0);
      expect(player.getState().track?.key).toBe(spotifyTrack.key);
    });

    it("ignores an out-of-range index", async () => {
      await player.playQueueItem(99);

      expect(player.getState().status).toBe("idle");
      expect(spotify.playCalls).toHaveLength(0);
    });
  });

  describe("transport", () => {
    it("pauses and resumes the live adapter", async () => {
      await player.play(spotifyTrack);

      await player.pause();
      expect(player.getState().status).toBe("paused");
      expect(spotify.pauseCalls).toBe(1);

      await player.resume();
      expect(player.getState().status).toBe("playing");
      expect(spotify.resumeCalls).toBe(1);
    });

    it("does nothing when pausing with nothing playing", async () => {
      await player.pause();

      expect(spotify.pauseCalls).toBe(0);
      expect(player.getState().status).toBe("idle");
    });

    it("seeks the live adapter", async () => {
      await player.play(spotifyTrack);

      await player.seek(45000);

      expect(spotify.seekCalls).toContain(45000);
    });

    it("clamps volume to 0..1", async () => {
      await player.setVolume(5);
      expect(player.getState().volume).toBe(1);

      await player.setVolume(-2);
      expect(player.getState().volume).toBe(0);
    });
  });

  describe("state changes", () => {
    it("notifies subscribers", async () => {
      const listener = vi.fn<(state: PlayerState) => void>();
      player.subscribe(listener);

      await player.play(spotifyTrack);

      expect(listener).toHaveBeenCalled();
      const latest = listener.mock.calls.at(-1)?.[0];
      expect(latest?.status).toBe("playing");
      expect(latest?.track?.key).toBe(spotifyTrack.key);
    });

    it("stops notifying after unsubscribe", async () => {
      const listener = vi.fn();
      const unsubscribe = player.subscribe(listener);
      unsubscribe();

      await player.play(spotifyTrack);

      expect(listener).not.toHaveBeenCalled();
    });

    it("one throwing listener does not break the others", async () => {
      const healthy = vi.fn();
      player.subscribe(() => {
        throw new Error("listener blew up");
      });
      player.subscribe(healthy);

      await player.play(spotifyTrack);

      expect(healthy).toHaveBeenCalled();
      expect(player.getState().status).toBe("playing");
    });
  });

  describe("failures", () => {
    it("surfaces an SDK init failure as an error state, not a crash", async () => {
      spotify.failInit = new Error("Widevine unavailable");

      await player.play(spotifyTrack);

      expect(player.getState().status).toBe("error");
      expect(player.getState().error).toMatch(/Widevine unavailable/);
    });

    it("surfaces a play failure", async () => {
      spotify.failPlay = new Error("Premium required");

      await player.play(spotifyTrack);

      expect(player.getState().status).toBe("error");
      expect(player.getState().error).toMatch(/Premium required/);
    });

    it("surfaces a ticket fetch failure", async () => {
      fetchTicket.mockRejectedValue(new Error("token rejected"));

      await player.play(spotifyTrack);

      expect(player.getState().status).toBe("error");
      expect(player.getState().error).toMatch(/token rejected/);
    });

    it("recovers on the next successful play", async () => {
      spotify.failPlay = new Error("transient");
      await player.play(spotifyTrack);
      expect(player.getState().status).toBe("error");

      spotify.failPlay = undefined as unknown as Error;
      await player.play(spotifyTrack);

      expect(player.getState().status).toBe("playing");
      expect(player.getState().error).toBeNull();
    });
  });

  describe("teardown", () => {
    it("disposes every initialised adapter", async () => {
      await player.play(spotifyTrack);
      await player.play(youtubeTrack);

      await player.dispose();

      expect(spotify.disposeCalls).toBe(1);
      expect(youtube.disposeCalls).toBe(1);
    });

    it("does not dispose an adapter that was never used", async () => {
      await player.play(spotifyTrack);

      await player.dispose();

      expect(spotify.disposeCalls).toBe(1);
      expect(youtube.disposeCalls).toBe(0);
    });
  });
});
