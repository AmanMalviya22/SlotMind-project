import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import SuggestionCard from "./SuggestionCard.jsx";

const suggestion = {
  id: "SUG-1",
  patientName: "Aisha Patel",
  originalProviderName: "Dr. Priya Sharma",
  originalTime: "2026-10-28T08:05:00.000Z",
  providerName: "Dr. James Okonkwo",
  recommendedTime: "2026-10-28T10:05:00.000Z",
  triggerReason: "PROVIDER_OFFLINE",
  confidence: 0.9,
  reasoning: "An eligible opening is available.",
  source: "AI",
};

describe("SuggestionCard", () => {
  it("shows a specialty change and labels model confidence as an estimate", () => {
    render(
      <SuggestionCard
        suggestion={suggestion}
        specialtyChange={{ from: "Pediatric", to: "Specialist" }}
        busy={false}
        patientConfirmed={false}
        onPatientConfirmationChange={vi.fn()}
        onDecide={vi.fn()}
      />,
    );

    expect(screen.getByRole("note")).toHaveTextContent(
      "Specialty change: Pediatric to Specialist",
    );
    expect(screen.getByText(/Model-estimated confidence/).parentElement).toHaveTextContent(
      "90%",
    );
  });

  it("requires patient confirmation before accepting", async () => {
    const user = userEvent.setup();
    const onPatientConfirmationChange = vi.fn();
    const onDecide = vi.fn();
    const props = {
      suggestion,
      specialtyChange: null,
      busy: false,
      patientConfirmed: false,
      onPatientConfirmationChange,
      onDecide,
    };
    const view = render(<SuggestionCard {...props} />);
    const checkbox = screen.getByRole("checkbox", {
      name: "I confirmed the proposed time with the patient.",
    });
    const accept = screen.getByRole("button", {
      name: "Accept recommendation",
    });

    expect(accept).toBeDisabled();
    await user.click(checkbox);
    expect(onPatientConfirmationChange).toHaveBeenCalledWith("SUG-1", true);

    view.rerender(
      <SuggestionCard {...props} patientConfirmed />,
    );
    expect(accept).toBeEnabled();
    await user.click(accept);
    expect(onDecide).toHaveBeenCalledWith("SUG-1", "ACCEPT");
  });

  it("does not show a percentage for rule-based recommendations", () => {
    render(
      <SuggestionCard
        suggestion={{ ...suggestion, source: "RULE_BASED", confidence: 0.92 }}
        specialtyChange={null}
        busy={false}
        patientConfirmed={false}
        onPatientConfirmationChange={vi.fn()}
        onDecide={vi.fn()}
      />,
    );

    expect(screen.getByText("Rule-based recommendation")).toBeInTheDocument();
    expect(screen.queryByText("92%")).not.toBeInTheDocument();
  });
});
