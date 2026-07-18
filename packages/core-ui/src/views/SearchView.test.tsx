import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SearchView } from "./SearchView";
import { ApiError } from "../api/client";
import type { ApiClient } from "../api/client";
import type { SearchResponse, Track } from "../api/types";

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

const emptyResponse = (query: string): SearchResponse => ({
  query,
  results: [],
  byProvider: {},
  failures: [],
  partial: false,
});

describe("SearchView", () => {
  let client: { search: ReturnType<typeof vi.fn>; addTrack: ReturnType<typeof vi.fn> };
  let onPlay: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    client = { search: vi.fn(), addTrack: vi.fn() };
    onPlay = vi.fn();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const renderView = (playlists: Parameters<typeof SearchView>[0]["playlists"] = []) =>
    render(
      <SearchView
        client={client as unknown as ApiClient}
        playlists={playlists}
        onPlay={onPlay}
      />,
    );

  const type = async (text: string): Promise<void> => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.type(screen.getByRole("searchbox"), text);
  };

  /**
   * Advances past the debounce inside act(), because the timer firing causes a
   * React state update. Without act() the update lands outside React's knowledge
   * and every assertion races the render.
   */
  const advancePastDebounce = async (): Promise<void> => {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
  };

  it("labels every result with the service it came from", async () => {
    client.search.mockResolvedValue({
      ...emptyResponse("mixed"),
      results: [track("SPOTIFY", "a", "Spotify Song"), track("YOUTUBE", "b", "YouTube Song")],
    } satisfies SearchResponse);
    renderView();

    await type("mixed");
    await advancePastDebounce();

    await waitFor(() => {
      expect(screen.getByText("Spotify Song")).toBeInTheDocument();
    });
    // The badge is text, not just colour: a coloured dot alone tells a colour-blind
    // user nothing, and this is the point of the feature.
    expect(screen.getByTestId("badge-SPOTIFY")).toHaveTextContent("Spotify");
    expect(screen.getByTestId("badge-YOUTUBE")).toHaveTextContent("YouTube");
  });

  it("debounces typing instead of searching per keystroke", async () => {
    client.search.mockResolvedValue(emptyResponse("rick"));
    renderView();

    await type("rick");
    // Four characters typed; YouTube would charge 400 quota units for four searches.
    expect(client.search).not.toHaveBeenCalled();

    await advancePastDebounce();

    await waitFor(() => {
      expect(client.search).toHaveBeenCalledTimes(1);
    });
    expect(client.search).toHaveBeenCalledWith("rick");
  });

  it("does not search for a blank query", async () => {
    renderView();

    await type("   ");
    await advancePastDebounce();

    expect(client.search).not.toHaveBeenCalled();
  });

  it("warns that results are incomplete when a service fails", async () => {
    client.search.mockResolvedValue({
      ...emptyResponse("q"),
      results: [track("SPOTIFY", "a", "Spotify Song")],
      partial: true,
      failures: [
        {
          provider: "YOUTUBE",
          kind: "RATE_LIMITED",
          message: "YouTube: rate limited",
          requiresReconnect: false,
        },
      ],
    } satisfies SearchResponse);
    renderView();

    await type("q");
    await advancePastDebounce();

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/didn't respond/i);
    });
    // The working service's results still show. Failing whole would be wrong.
    expect(screen.getByText("Spotify Song")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(/YouTube: rate limited/);
  });

  it("tells the user to reconnect when that is the fix", async () => {
    client.search.mockResolvedValue({
      ...emptyResponse("q"),
      partial: true,
      failures: [
        {
          provider: "SPOTIFY",
          kind: "UNAUTHORIZED",
          message: "SPOTIFY: token rejected",
          requiresReconnect: true,
        },
      ],
    } satisfies SearchResponse);
    renderView();

    await type("q");
    await advancePastDebounce();

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/reconnect it in Services/i);
    });
  });

  it("reports a failed search", async () => {
    client.search.mockRejectedValue(new ApiError("Could not reach the backend", { status: 0 }));
    renderView();

    await type("q");
    await advancePastDebounce();

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("Could not reach the backend");
    });
  });

  it("says so when there are no results", async () => {
    client.search.mockResolvedValue(emptyResponse("zzzz"));
    renderView();

    await type("zzzz");
    await advancePastDebounce();

    await waitFor(() => {
      expect(screen.getByText(/No results for/)).toBeInTheDocument();
    });
  });

  it("plays a result", async () => {
    const spotifySong = track("SPOTIFY", "a", "Spotify Song");
    client.search.mockResolvedValue({ ...emptyResponse("q"), results: [spotifySong] });
    renderView();

    await type("q");
    await advancePastDebounce();
    await waitFor(() => expect(screen.getByText("Spotify Song")).toBeInTheDocument());

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.click(screen.getByRole("button", { name: /Play Spotify Song/ }));

    expect(onPlay).toHaveBeenCalledWith(spotifySong, [spotifySong]);
  });

  it("will not play an unplayable track", async () => {
    client.search.mockResolvedValue({
      ...emptyResponse("q"),
      results: [{ ...track("SPOTIFY", "a", "Region Locked"), playable: false }],
    });
    renderView();

    await type("q");
    await advancePastDebounce();

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /Play Region Locked/ })).toBeDisabled();
    });
  });

  it("adds a result to a playlist of the user's choosing", async () => {
    const spotifySong = track("SPOTIFY", "a", "Spotify Song");
    client.search.mockResolvedValue({ ...emptyResponse("q"), results: [spotifySong] });
    client.addTrack.mockResolvedValue({ id: "pl1", name: "Road Trip" });
    renderView([
      {
        id: "pl1",
        name: "Road Trip",
        description: null,
        origin: null,
        providersUsed: [],
        trackCount: 0,
        entries: [],
        createdAt: "2026-06-01T12:00:00Z",
        updatedAt: "2026-06-01T12:00:00Z",
      },
    ]);

    await type("q");
    await advancePastDebounce();
    await waitFor(() => expect(screen.getByText("Spotify Song")).toBeInTheDocument());

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.selectOptions(screen.getByLabelText(/Add Spotify Song to a playlist/), "pl1");

    await waitFor(() => {
      expect(client.addTrack).toHaveBeenCalledWith("pl1", spotifySong);
    });
  });

  it("confirms in the UI when a track is added, then clears the notice", async () => {
    const spotifySong = track("SPOTIFY", "a", "Spotify Song");
    client.search.mockResolvedValue({ ...emptyResponse("q"), results: [spotifySong] });
    client.addTrack.mockResolvedValue({ id: "pl1", name: "Road Trip" });
    const onTrackAdded = vi.fn();
    render(
      <SearchView
        client={client as unknown as ApiClient}
        playlists={[
          {
            id: "pl1",
            name: "Road Trip",
            description: null,
            origin: null,
            providersUsed: [],
            trackCount: 0,
            entries: [],
            createdAt: "2026-06-01T12:00:00Z",
            updatedAt: "2026-06-01T12:00:00Z",
          },
        ]}
        onPlay={onPlay}
        onTrackAdded={onTrackAdded}
      />,
    );

    await type("q");
    await advancePastDebounce();
    await waitFor(() => expect(screen.getByText("Spotify Song")).toBeInTheDocument());

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.selectOptions(screen.getByLabelText(/Add Spotify Song to a playlist/), "pl1");

    await waitFor(() => {
      expect(screen.getByText(/Added .*Spotify Song.* to Road Trip/)).toBeInTheDocument();
    });
    expect(onTrackAdded).toHaveBeenCalledWith({ id: "pl1", name: "Road Trip" });

    // The confirmation is transient — it clears itself after the timeout.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3000);
    });
    expect(screen.queryByText(/Added .*Spotify Song/)).not.toBeInTheDocument();
  });

  it("re-orders results when a different sort is chosen", async () => {
    client.search.mockResolvedValue({
      ...emptyResponse("q"),
      results: [
        track("YOUTUBE", "a", "Zebra"),
        track("SPOTIFY", "b", "Apple"),
        track("YOUTUBE", "c", "Mango"),
      ],
    } satisfies SearchResponse);
    renderView();

    await type("q");
    await advancePastDebounce();
    await waitFor(() => expect(screen.getByText("Apple")).toBeInTheDocument());

    // Backend order is preserved under the default "Relevance".
    const titlesBefore = screen.getAllByText(/Zebra|Apple|Mango/).map((el) => el.textContent);
    expect(titlesBefore).toEqual(["Zebra", "Apple", "Mango"]);

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.selectOptions(screen.getByLabelText("Sort by"), "title");

    await waitFor(() => {
      const titles = screen.getAllByText(/Zebra|Apple|Mango/).map((el) => el.textContent);
      expect(titles).toEqual(["Apple", "Mango", "Zebra"]);
    });
  });

  it("flips between ascending and descending when the direction is toggled", async () => {
    client.search.mockResolvedValue({
      ...emptyResponse("q"),
      results: [
        track("YOUTUBE", "a", "Zebra"),
        track("SPOTIFY", "b", "Apple"),
        track("YOUTUBE", "c", "Mango"),
      ],
    } satisfies SearchResponse);
    renderView();

    await type("q");
    await advancePastDebounce();
    await waitFor(() => expect(screen.getByText("Apple")).toBeInTheDocument());

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.selectOptions(screen.getByLabelText("Sort by"), "title");

    // Ascending by default.
    await waitFor(() => {
      const titles = screen.getAllByText(/Zebra|Apple|Mango/).map((el) => el.textContent);
      expect(titles).toEqual(["Apple", "Mango", "Zebra"]);
    });

    // Toggling the direction reverses the order.
    await user.click(screen.getByRole("button", { name: /Sort direction/ }));
    await waitFor(() => {
      const titles = screen.getAllByText(/Zebra|Apple|Mango/).map((el) => el.textContent);
      expect(titles).toEqual(["Zebra", "Mango", "Apple"]);
    });
  });
});
