import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MigrationDialog } from "./MigrationDialog";
import type { Track, UnresolvedMatch } from "../api/types";

function track(
  provider: Track["provider"],
  id: string,
  title: string,
  artist: string,
): Track {
  return {
    key: `${provider}:${id}`,
    provider,
    providerTrackId: id,
    title,
    artists: [artist],
    artistLine: artist,
    album: null,
    durationMs: 200000,
    artworkUrl: null,
    playable: true,
  };
}

const source = track("SPOTIFY", "sp1", "Hello", "Adele");

const match: UnresolvedMatch = {
  position: 2,
  source,
  candidates: [
    track("YOUTUBE", "yt1", "Hello", "Adele"),
    track("SOUNDCLOUD", "sc1", "Hello (Cover)", "Someone"),
  ],
};

describe("MigrationDialog", () => {
  it("lists candidates from every service for a track", () => {
    render(
      <MigrationDialog
        target="YOUTUBE"
        unresolved={[match]}
        onResolve={vi.fn()}
        onClose={vi.fn()}
      />,
    );

    expect(screen.getByTestId("badge-YOUTUBE")).toBeInTheDocument();
    expect(screen.getByTestId("badge-SOUNDCLOUD")).toBeInTheDocument();
  });

  it("resolves a track with the chosen candidate and collapses the card", async () => {
    const onResolve = vi.fn().mockResolvedValue(undefined);
    render(
      <MigrationDialog
        target="YOUTUBE"
        unresolved={[match]}
        onResolve={onResolve}
        onClose={vi.fn()}
      />,
    );

    const buttons = screen.getAllByRole("button", { name: "Use this" });
    await userEvent.click(buttons[0]!);

    expect(onResolve).toHaveBeenCalledWith(match, match.candidates[0]);
    await waitFor(() =>
      expect(screen.getByRole("status")).toHaveTextContent(/Replaced with .*YouTube/i),
    );
  });

  it("keeps the original when the user skips, without resolving", async () => {
    const onResolve = vi.fn();
    render(
      <MigrationDialog
        target="YOUTUBE"
        unresolved={[match]}
        onResolve={onResolve}
        onClose={vi.fn()}
      />,
    );

    await userEvent.click(screen.getByRole("button", { name: "Keep original" }));

    expect(onResolve).not.toHaveBeenCalled();
    expect(screen.getByText(/Kept the original on Spotify/i)).toBeInTheDocument();
  });

  it("shows an error against the card and stays open when a replace fails", async () => {
    const onResolve = vi.fn().mockRejectedValue(new Error("Backend unreachable"));
    render(
      <MigrationDialog
        target="YOUTUBE"
        unresolved={[match]}
        onResolve={onResolve}
        onClose={vi.fn()}
      />,
    );

    await userEvent.click(screen.getAllByRole("button", { name: "Use this" })[0]!);

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(/Backend unreachable/),
    );
    // The candidates are still there to try again.
    expect(screen.getAllByRole("button", { name: "Use this" }).length).toBeGreaterThan(0);
  });

  it("counts how many tracks are still outstanding on the Done button", async () => {
    const twoMatches: UnresolvedMatch[] = [
      match,
      { ...match, position: 5, source: track("SPOTIFY", "sp2", "Skyfall", "Adele") },
    ];
    render(
      <MigrationDialog
        target="YOUTUBE"
        unresolved={twoMatches}
        onResolve={vi.fn().mockResolvedValue(undefined)}
        onClose={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: /Done \(2 left\)/ })).toBeInTheDocument();

    // Resolving one (the first card's first candidate) drops the count.
    await userEvent.click(screen.getAllByRole("button", { name: "Use this" })[0]!);

    await waitFor(() =>
      expect(screen.getByRole("button", { name: /Done \(1 left\)/ })).toBeInTheDocument(),
    );
  });
});
