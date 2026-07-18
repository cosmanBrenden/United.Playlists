import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PlaylistView } from "./PlaylistView";
import type { ApiClient } from "../api/client";
import type { MigrationResult, Playlist, Track } from "../api/types";

function track(provider: Track["provider"], id: string, title: string): Track {
  return {
    key: `${provider}:${id}`,
    provider,
    providerTrackId: id,
    title,
    artists: ["Adele"],
    artistLine: "Adele",
    album: null,
    durationMs: 200000,
    artworkUrl: null,
    playable: true,
  };
}

function playlist(tracks: readonly Track[]): Playlist {
  return {
    id: "p1",
    name: "Mixed",
    description: null,
    origin: null,
    providersUsed: ["SPOTIFY"],
    trackCount: tracks.length,
    entries: tracks.map((t, index) => ({
      id: `e${index}`,
      position: index,
      track: t,
      addedAt: "2026-06-01T12:00:00Z",
    })),
    createdAt: "2026-06-01T12:00:00Z",
    updatedAt: "2026-06-01T12:00:00Z",
  };
}

describe("PlaylistView migration", () => {
  let client: { migratePlaylist: ReturnType<typeof vi.fn>; replaceTrack: ReturnType<typeof vi.fn> };
  let onChanged: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    client = { migratePlaylist: vi.fn(), replaceTrack: vi.fn() };
    onChanged = vi.fn();
  });

  const renderView = (
    tracks: readonly Track[],
    targets: Parameters<typeof PlaylistView>[0]["migrationTargets"] = ["YOUTUBE"],
  ) =>
    render(
      <PlaylistView
        client={client as unknown as ApiClient}
        playlist={playlist(tracks)}
        onChanged={onChanged}
        onDeleted={vi.fn()}
        onPlay={vi.fn()}
        onShufflePlay={vi.fn()}
        migrationTargets={targets}
      />,
    );

  it("hides the migrate toggle entirely when there is nowhere to migrate to", () => {
    renderView([track("SPOTIFY", "sp1", "Hello")], []);

    expect(screen.queryByRole("button", { name: /Migrate/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("keeps the checkboxes and toolbar hidden until migrate mode is turned on", async () => {
    renderView([track("SPOTIFY", "sp1", "Hello")]);

    // Off by default: no checkboxes, no destination bar.
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.queryByText("Migrate whole playlist")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /⇄ Migrate/ }));

    expect(screen.getByRole("button", { name: "Migrate whole playlist" })).toBeInTheDocument();
    expect(screen.getAllByRole("checkbox").length).toBeGreaterThan(0);

    // Turning it back off hides them again.
    await userEvent.click(screen.getByRole("button", { name: /Done migrating/ }));
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
  });

  it("migrates only the selected tracks to the chosen service", async () => {
    const result: MigrationResult = {
      target: "YOUTUBE",
      playlist: playlist([track("YOUTUBE", "yt1", "Hello"), track("SPOTIFY", "sp2", "Skyfall")]),
      replaced: [
        { position: 0, from: track("SPOTIFY", "sp1", "Hello"), to: track("YOUTUBE", "yt1", "Hello") },
      ],
      unresolved: [],
      alreadyOnTarget: 0,
      failures: [],
    };
    client.migratePlaylist.mockResolvedValue(result);

    renderView([track("SPOTIFY", "sp1", "Hello"), track("SPOTIFY", "sp2", "Skyfall")]);

    await userEvent.click(screen.getByRole("button", { name: /⇄ Migrate/ }));
    await userEvent.click(screen.getByLabelText("Select Hello"));
    await userEvent.click(screen.getByRole("button", { name: /Migrate selected \(1\)/ }));

    expect(client.migratePlaylist).toHaveBeenCalledWith("p1", "YOUTUBE", [0]);
    await waitFor(() =>
      expect(screen.getByText(/1 replaced automatically/)).toBeInTheDocument(),
    );
    expect(onChanged).toHaveBeenCalledWith(result.playlist);
  });

  it("migrates the whole playlist and opens the picker for unresolved tracks", async () => {
    const source = track("SPOTIFY", "sp1", "Hello");
    const result: MigrationResult = {
      target: "YOUTUBE",
      playlist: playlist([source]),
      replaced: [],
      unresolved: [
        { position: 0, source, candidates: [track("YOUTUBE", "yt9", "Hello")] },
      ],
      alreadyOnTarget: 0,
      failures: [],
    };
    client.migratePlaylist.mockResolvedValue(result);

    renderView([source]);

    await userEvent.click(screen.getByRole("button", { name: /⇄ Migrate/ }));
    await userEvent.click(screen.getByRole("button", { name: "Migrate whole playlist" }));

    // Whole-playlist migration sends no explicit positions.
    expect(client.migratePlaylist).toHaveBeenCalledWith("p1", "YOUTUBE", undefined);
    await waitFor(() =>
      expect(screen.getByRole("dialog", { name: /Choose matches on YouTube/i })).toBeInTheDocument(),
    );
  });

  it("applies a manual choice through replaceTrack with the source key as the guard", async () => {
    const source = track("SPOTIFY", "sp1", "Hello");
    const chosen = track("YOUTUBE", "yt9", "Hello");
    client.migratePlaylist.mockResolvedValue({
      target: "YOUTUBE",
      playlist: playlist([source]),
      replaced: [],
      unresolved: [{ position: 0, source, candidates: [chosen] }],
      alreadyOnTarget: 0,
      failures: [],
    } satisfies MigrationResult);
    client.replaceTrack.mockResolvedValue(playlist([chosen]));

    renderView([source]);
    await userEvent.click(screen.getByRole("button", { name: /⇄ Migrate/ }));
    await userEvent.click(screen.getByRole("button", { name: "Migrate whole playlist" }));
    await screen.findByRole("dialog");

    await userEvent.click(screen.getByRole("button", { name: "Use this" }));

    expect(client.replaceTrack).toHaveBeenCalledWith("p1", 0, chosen, "SPOTIFY:sp1");
  });
});
