import { describe, it, expect } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { ConfirmProvider, useConfirm } from "./ConfirmDialog";

function Harness({ onResult }: { onResult: (r: unknown) => void }) {
  const confirm = useConfirm();
  return (
    <button
      onClick={async () =>
        onResult(
          await confirm({ title: "Stop tenant?", description: "Blocks everyone.", reason: { label: "Reason", required: true } })
        )
      }
    >
      go
    </button>
  );
}

describe("ConfirmProvider", () => {
  it("resolves null on cancel", async () => {
    let result: unknown = "unset";
    render(
      <ConfirmProvider>
        <Harness onResult={(r) => (result = r)} />
      </ConfirmProvider>
    );
    fireEvent.click(screen.getByText("go"));
    expect(await screen.findByText("Stop tenant?")).toBeTruthy();
    fireEvent.click(screen.getByText("Cancel"));
    await waitFor(() => expect(result).toBeNull());
  });

  it("requires a reason and returns it on confirm", async () => {
    let result: unknown = "unset";
    render(
      <ConfirmProvider>
        <Harness onResult={(r) => (result = r)} />
      </ConfirmProvider>
    );
    fireEvent.click(screen.getByText("go"));
    const confirmBtn = (await screen.findByText("Continue")) as HTMLButtonElement;
    expect(confirmBtn.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Reason/), { target: { value: " abuse " } });
    fireEvent.click(confirmBtn);
    await waitFor(() => expect(result).toEqual({ reason: "abuse" }));
  });
});
