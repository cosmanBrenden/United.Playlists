import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueuePanel } from "./QueuePanel";
import type { Track } from "../api/types";

const track = (id: string, title: string): Track => ({
  key: `YOUTUBE:${id}`,
  provider: "YOUTUBE",
  providerTrackId: id,
  title,
  artists: ["An Artist"],
  artistLine: "An Artist",
  album: null,
  durationMs: 200000,
  artworkUrl: null,
  playable: true,
});

const handlers = () => ({
  onClose: vi.fn(),
  onJump: vi.fn(),
  onRemove: vi.fn(),
  onMove: vi.fn(),
});

describe("QueuePanel", () => {
  const queue = [track("a", "First"), track("b", "Second"), track("c", "Third")];

  it("shows an empty state when there is no queue", () => {
    render(<QueuePanel queue={[]} currentIndex={-1} {...handlers()} />);
    expect(screen.getByText(/queue is empty/i)).toBeInTheDocument();
  });

  it("marks the current track as now playing", () => {
    render(<QueuePanel queue={queue} currentIndex={1} {...handlers()} />);
    expect(screen.getByLabelText("Second (now playing)")).toHaveAttribute("aria-current", "true");
  });

  it("jumps to a clicked track", async () => {
    const h = handlers();
    render(<QueuePanel queue={queue} currentIndex={0} {...h} />);

    await userEvent.click(screen.getByLabelText("Play Third"));
    expect(h.onJump).toHaveBeenCalledWith(2);
  });

  it("removes a track", async () => {
    const h = handlers();
    render(<QueuePanel queue={queue} currentIndex={0} {...h} />);

    await userEvent.click(screen.getByLabelText("Remove Second from the queue"));
    expect(h.onRemove).toHaveBeenCalledWith(1);
  });

  it("reorders a track and disables the edges", async () => {
    const h = handlers();
    render(<QueuePanel queue={queue} currentIndex={0} {...h} />);

    await userEvent.click(screen.getByLabelText("Move Second down"));
    expect(h.onMove).toHaveBeenCalledWith(1, 2);

    expect(screen.getByLabelText("Move First up")).toBeDisabled();
    expect(screen.getByLabelText("Move Third down")).toBeDisabled();
  });
});
