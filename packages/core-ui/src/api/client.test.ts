import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClient, ApiError } from "./client";
import type { Playlist, SearchResponse } from "./types";

describe("ApiClient", () => {
  const baseUrl = "http://127.0.0.1:8421";
  const token = "test-token";
  let fetchMock: ReturnType<typeof vi.fn>;
  let client: ApiClient;

  const jsonResponse = (body: unknown, status = 200): Response =>
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });

  beforeEach(() => {
    fetchMock = vi.fn();
    client = new ApiClient({ baseUrl, token, fetchFn: fetchMock as unknown as typeof fetch });
  });

  const lastRequest = (): Request => fetchMock.mock.calls[0]?.[0] as Request;

  describe("authentication", () => {
    it("sends the shared secret on every request", async () => {
      fetchMock.mockResolvedValue(jsonResponse([]));

      await client.listPlaylists();

      expect(lastRequest().headers.get("X-UnitedPlaylists-Token")).toBe(token);
    });

    it("targets the configured backend port", async () => {
      fetchMock.mockResolvedValue(jsonResponse([]));

      await client.listPlaylists();

      expect(lastRequest().url).toBe(`${baseUrl}/api/v1/playlists`);
    });
  });

  describe("playlists", () => {
    const playlist: Playlist = {
      id: "11111111-1111-1111-1111-111111111111",
      name: "Road Trip",
      description: null,
      origin: null,
      providersUsed: ["SPOTIFY"],
      trackCount: 1,
      entries: [],
      createdAt: "2026-06-01T12:00:00Z",
      updatedAt: "2026-06-01T12:00:00Z",
    };

    it("lists playlists", async () => {
      fetchMock.mockResolvedValue(jsonResponse([playlist]));

      const result = await client.listPlaylists();

      expect(result).toHaveLength(1);
      expect(result[0]?.name).toBe("Road Trip");
    });

    it("creates a playlist", async () => {
      fetchMock.mockResolvedValue(jsonResponse(playlist, 201));

      const created = await client.createPlaylist("Road Trip", "for the car");

      expect(created.name).toBe("Road Trip");
      const request = lastRequest();
      expect(request.method).toBe("POST");
      await expect(request.json()).resolves.toEqual({
        name: "Road Trip",
        description: "for the car",
      });
    });

    it("adds a track from a search result", async () => {
      fetchMock.mockResolvedValue(jsonResponse(playlist, 201));

      await client.addTrack(playlist.id, {
        key: "YOUTUBE:dQw4w9WgXcQ",
        provider: "YOUTUBE",
        providerTrackId: "dQw4w9WgXcQ",
        title: "A Song",
        artists: ["A Channel"],
        artistLine: "A Channel",
        album: null,
        durationMs: 213000,
        artworkUrl: null,
        playable: true,
      });

      const body = await lastRequest().json();
      expect(body).toMatchObject({
        trackKey: "YOUTUBE:dQw4w9WgXcQ",
        title: "A Song",
        artists: ["A Channel"],
        durationMs: 213000,
      });
    });

    it("moves a track", async () => {
      fetchMock.mockResolvedValue(jsonResponse(playlist));

      await client.moveTrack(playlist.id, 0, 2);

      await expect(lastRequest().json()).resolves.toEqual({ from: 0, to: 2 });
    });

    it("deletes a playlist and tolerates the empty 204 body", async () => {
      fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

      await expect(client.deletePlaylist(playlist.id)).resolves.toBeUndefined();
      expect(lastRequest().method).toBe("DELETE");
    });
  });

  describe("migration", () => {
    const track = {
      key: "YOUTUBE:yt1",
      provider: "YOUTUBE" as const,
      providerTrackId: "yt1",
      title: "Hello",
      artists: ["Adele"],
      artistLine: "Adele",
      album: null,
      durationMs: 295000,
      artworkUrl: null,
      playable: true,
    };

    it("migrates the whole playlist when no positions are given", async () => {
      fetchMock.mockResolvedValue(
        jsonResponse({
          target: "YOUTUBE",
          playlist: { id: "p1", entries: [] },
          replaced: [],
          unresolved: [],
          alreadyOnTarget: 0,
          failures: [],
        }),
      );

      await client.migratePlaylist("p1", "YOUTUBE");

      const request = lastRequest();
      expect(request.method).toBe("POST");
      expect(request.url).toBe(`${baseUrl}/api/v1/playlists/p1/migrate`);
      await expect(request.json()).resolves.toEqual({
        targetProvider: "YOUTUBE",
        positions: [],
      });
    });

    it("sends the selected positions when given", async () => {
      fetchMock.mockResolvedValue(
        jsonResponse({
          target: "SPOTIFY",
          playlist: { id: "p1", entries: [] },
          replaced: [],
          unresolved: [],
          alreadyOnTarget: 0,
          failures: [],
        }),
      );

      await client.migratePlaylist("p1", "SPOTIFY", [0, 2]);

      await expect(lastRequest().json()).resolves.toEqual({
        targetProvider: "SPOTIFY",
        positions: [0, 2],
      });
    });

    it("replaces a track in place, carrying the expected key", async () => {
      fetchMock.mockResolvedValue(jsonResponse({ id: "p1", entries: [] }));

      await client.replaceTrack("p1", 3, track, "SPOTIFY:sp1");

      const request = lastRequest();
      expect(request.method).toBe("POST");
      expect(request.url).toBe(`${baseUrl}/api/v1/playlists/p1/tracks/3/replace`);
      await expect(request.json()).resolves.toMatchObject({
        trackKey: "YOUTUBE:yt1",
        title: "Hello",
        artists: ["Adele"],
        durationMs: 295000,
        expectedKey: "SPOTIFY:sp1",
      });
    });

    it("surfaces a 409 stale-replacement conflict as an ApiError", async () => {
      fetchMock.mockResolvedValue(
        jsonResponse({ error: "stale_replacement", message: "changed under you" }, 409),
      );

      await expect(client.replaceTrack("p1", 0, track, "SPOTIFY:old")).rejects.toMatchObject({
        status: 409,
        code: "stale_replacement",
      });
    });
  });

  describe("search", () => {
    const response: SearchResponse = {
      query: "rick astley",
      results: [
        {
          key: "SPOTIFY:abc",
          provider: "SPOTIFY",
          providerTrackId: "abc",
          title: "Never Gonna Give You Up",
          artists: ["Rick Astley"],
          artistLine: "Rick Astley",
          album: "Whenever You Need Somebody",
          durationMs: 213573,
          artworkUrl: null,
          playable: true,
        },
      ],
      byProvider: {},
      failures: [],
      partial: false,
    };

    it("passes the query and limit", async () => {
      fetchMock.mockResolvedValue(jsonResponse(response));

      await client.search("rick astley", 30);

      const url = new URL(lastRequest().url);
      expect(url.pathname).toBe("/api/v1/search");
      expect(url.searchParams.get("q")).toBe("rick astley");
      expect(url.searchParams.get("limit")).toBe("30");
    });

    it("encodes queries with special characters", async () => {
      fetchMock.mockResolvedValue(jsonResponse(response));

      await client.search("AC/DC & Guns N' Roses", 20);

      const url = new URL(lastRequest().url);
      expect(url.searchParams.get("q")).toBe("AC/DC & Guns N' Roses");
    });

    it("returns results tagged with their service", async () => {
      fetchMock.mockResolvedValue(jsonResponse(response));

      const result = await client.search("rick astley", 20);

      expect(result.results[0]?.provider).toBe("SPOTIFY");
    });

    it("surfaces a partial result rather than treating it as failure", async () => {
      fetchMock.mockResolvedValue(
        jsonResponse({
          ...response,
          partial: true,
          failures: [
            {
              provider: "YOUTUBE",
              kind: "RATE_LIMITED",
              message: "YouTube: rate limited",
              requiresReconnect: false,
            },
          ],
        } satisfies SearchResponse),
      );

      const result = await client.search("rick astley", 20);

      // The Spotify results still arrive; the UI shows a warning alongside them.
      expect(result.results).toHaveLength(1);
      expect(result.partial).toBe(true);
      expect(result.failures[0]?.provider).toBe("YOUTUBE");
    });
  });

  describe("error handling", () => {
    it("throws ApiError carrying the backend's reason", async () => {
      fetchMock.mockResolvedValue(
        jsonResponse(
          {
            error: "unauthorized",
            message: "SPOTIFY: token rejected",
            provider: "SPOTIFY",
            requiresReconnect: true,
            details: [],
            timestamp: "2026-06-01T12:00:00Z",
          },
          401,
        ),
      );

      await expect(client.search("x", 20)).rejects.toSatisfy((error: unknown) => {
        expect(error).toBeInstanceOf(ApiError);
        const apiError = error as ApiError;
        expect(apiError.status).toBe(401);
        expect(apiError.requiresReconnect).toBe(true);
        expect(apiError.provider).toBe("SPOTIFY");
        return true;
      });
    });

    it("still throws a usable error when the body is not JSON", async () => {
      fetchMock.mockResolvedValue(
        new Response("<html>Gateway Timeout</html>", {
          status: 504,
          headers: { "Content-Type": "text/html" },
        }),
      );

      await expect(client.listPlaylists()).rejects.toBeInstanceOf(ApiError);
      await expect(client.listPlaylists()).rejects.toMatchObject({ status: 504 });
    });

    it("wraps a network failure rather than leaking a raw TypeError", async () => {
      fetchMock.mockRejectedValue(new TypeError("Failed to fetch"));

      await expect(client.listPlaylists()).rejects.toSatisfy((error: unknown) => {
        expect(error).toBeInstanceOf(ApiError);
        expect((error as ApiError).status).toBe(0);
        expect((error as ApiError).message).toMatch(/backend/i);
        return true;
      });
    });

    it("reports validation details from a 400", async () => {
      fetchMock.mockResolvedValue(
        jsonResponse(
          {
            error: "validation_failed",
            message: "Request is not valid",
            provider: null,
            requiresReconnect: false,
            details: ["name must not be blank"],
            timestamp: "2026-06-01T12:00:00Z",
          },
          400,
        ),
      );

      await expect(client.createPlaylist("", null)).rejects.toMatchObject({
        status: 400,
        details: ["name must not be blank"],
      });
    });
  });

  describe("connections", () => {
    it("lists providers including unavailable ones", async () => {
      fetchMock.mockResolvedValue(
        jsonResponse([
          {
            id: "APPLE_MUSIC",
            displayName: "Apple Music",
            available: false,
            connected: false,
            accountLabel: null,
          },
        ]),
      );

      const providers = await client.listProviders();

      expect(providers[0]?.available).toBe(false);
    });

    it("fetches a playback ticket for a track", async () => {
      fetchMock.mockResolvedValue(
        jsonResponse({
          trackKey: "SPOTIFY:abc",
          provider: "SPOTIFY",
          method: "SPOTIFY_WEB_SDK",
          params: { uri: "spotify:track:abc" },
        }),
      );

      const ticket = await client.playbackTicket("SPOTIFY:abc");

      expect(ticket.method).toBe("SPOTIFY_WEB_SDK");
      expect(new URL(lastRequest().url).searchParams.get("trackKey")).toBe("SPOTIFY:abc");
    });
  });
});
