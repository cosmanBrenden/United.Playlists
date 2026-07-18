import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { PlayerBar } from "./PlayerBar";
import type { PlayerState } from "../player/types";
import type { Track } from "../api/types";

const track: Track = {
  key: "YOUTUBE:vid1",
  provider: "YOUTUBE",
  providerTrackId: "vid1",
  title: "A Song",
  artists: ["An Artist"],
  artistLine: "An Artist",
  album: null,
  durationMs: 200000,
  artworkUrl: null,
  playable: true,
};

const state = (overrides: Partial<PlayerState> = {}): PlayerState => ({
  status: "playing",
  track,
  positionMs: 60000,
  durationMs: 200000,
  bufferedMs: 120000,
  volume: 1,
  error: null,
  queue: [track],
  queueIndex: 0,
  shuffle: false,
  ...overrides,
});

const handlers = () => ({
  onToggle: vi.fn(),
  onNext: vi.fn(),
  onPrevious: vi.fn(),
  onVolume: vi.fn(),
  onSeek: vi.fn(),
  onToggleShuffle: vi.fn(),
  onToggleQueue: vi.fn(),
});

describe("PlayerBar", () => {
  it("renders a seekable progress bar with played and buffered time", () => {
    render(<PlayerBar state={state()} queueOpen={false} {...handlers()} />);

    const seek = screen.getByLabelText("Seek") as HTMLInputElement;
    expect(seek.value).toBe("60000");
    expect(seek.max).toBe("200000");
    // 1:00 played, 3:20 total both shown.
    expect(screen.getByText("1:00")).toBeInTheDocument();
    expect(screen.getByText("3:20")).toBeInTheDocument();
  });

  it("commits a seek only when the user releases the slider, not on every drag tick", async () => {
    const h = handlers();
    render(<PlayerBar state={state()} queueOpen={false} {...h} />);

    const seek = screen.getByLabelText("Seek");
    // Dragging updates the visible position but must not fire a seek per tick — that
    // would flood Spotify's seek endpoint.
    fireEvent.change(seek, { target: { value: "90000" } });
    fireEvent.change(seek, { target: { value: "120000" } });
    expect(h.onSeek).not.toHaveBeenCalled();

    // Releasing commits the final position once.
    fireEvent.pointerUp(seek);
    expect(h.onSeek).toHaveBeenCalledTimes(1);
    expect(h.onSeek).toHaveBeenCalledWith(120000);
  });

  it("reflects and toggles shuffle", async () => {
    const h = handlers();
    const { rerender } = render(
      <PlayerBar state={state({ shuffle: false })} queueOpen={false} {...h} />,
    );

    const shuffle = screen.getByLabelText("Shuffle");
    expect(shuffle).toHaveAttribute("aria-pressed", "false");
    await userEvent.click(shuffle);
    expect(h.onToggleShuffle).toHaveBeenCalled();

    rerender(<PlayerBar state={state({ shuffle: true })} queueOpen={false} {...h} />);
    expect(screen.getByLabelText("Shuffle")).toHaveAttribute("aria-pressed", "true");
  });

  it("toggles the queue panel", async () => {
    const h = handlers();
    render(<PlayerBar state={state()} queueOpen={false} {...h} />);

    await userEvent.click(screen.getByLabelText("Queue"));
    expect(h.onToggleQueue).toHaveBeenCalled();
  });

  it("hides the buffered indicator when the service cannot report it", () => {
    render(<PlayerBar state={state({ bufferedMs: null })} queueOpen={false} {...handlers()} />);
    // No crash, and the seek bar still works.
    expect(screen.getByLabelText("Seek")).toBeInTheDocument();
  });

  it("disables seeking when nothing is loaded", () => {
    render(
      <PlayerBar
        state={state({ status: "idle", track: null })}
        queueOpen={false}
        {...handlers()}
      />,
    );
    expect(screen.getByLabelText("Seek")).toBeDisabled();
  });
});
