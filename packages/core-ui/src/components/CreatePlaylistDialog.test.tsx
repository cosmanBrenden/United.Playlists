import { describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { CreatePlaylistDialog } from "./CreatePlaylistDialog";

describe("CreatePlaylistDialog", () => {
  it("creates a playlist with the entered name and description", async () => {
    const onCreate = vi.fn().mockResolvedValue(undefined);
    render(<CreatePlaylistDialog onCancel={vi.fn()} onCreate={onCreate} />);

    await userEvent.type(screen.getByLabelText("Name"), "Road Trip");
    await userEvent.type(screen.getByLabelText("Description (optional)"), "Long drives");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(onCreate).toHaveBeenCalledWith("Road Trip", "Long drives");
  });

  it("passes a null description when left blank", async () => {
    const onCreate = vi.fn().mockResolvedValue(undefined);
    render(<CreatePlaylistDialog onCancel={vi.fn()} onCreate={onCreate} />);

    await userEvent.type(screen.getByLabelText("Name"), "Focus");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(onCreate).toHaveBeenCalledWith("Focus", null);
  });

  it("refuses an empty name without calling onCreate", async () => {
    const onCreate = vi.fn().mockResolvedValue(undefined);
    render(<CreatePlaylistDialog onCancel={vi.fn()} onCreate={onCreate} />);

    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    expect(onCreate).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(/give the playlist a name/i);
  });

  it("keeps the dialog open and shows the error when creation fails", async () => {
    const onCreate = vi.fn().mockRejectedValue(new Error("Backend unreachable"));
    render(<CreatePlaylistDialog onCancel={vi.fn()} onCreate={onCreate} />);

    await userEvent.type(screen.getByLabelText("Name"), "Mix");
    await userEvent.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("Backend unreachable");
    });
    // Still open (name field present) so the typed name is not lost.
    expect(screen.getByLabelText("Name")).toHaveValue("Mix");
  });

  it("cancels on the Cancel button", async () => {
    const onCancel = vi.fn();
    render(<CreatePlaylistDialog onCancel={onCancel} onCreate={vi.fn()} />);

    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onCancel).toHaveBeenCalled();
  });
});
