import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import AppointmentBookingForm from "./AppointmentBookingForm.jsx";

const booking = {
  patientName: "Aisha Patel",
  type: "ROUTINE",
  providerId: "PRV-1",
  slotId: "SLT-1",
};

describe("AppointmentBookingForm", () => {
  it("shows eligible providers and slots, then submits the chosen booking", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn((event) => event.preventDefault());
    render(
      <AppointmentBookingForm
        booking={booking}
        setBooking={vi.fn()}
        providers={[{ id: "PRV-1", name: "Dr. Chen", specialty: "GENERAL" }]}
        slots={[
          {
            id: "SLT-1",
            startTime: "2026-10-28T10:05:00.000Z",
            durationMinutes: 30,
          },
        ]}
        busy={false}
        loading={false}
        onSubmit={onSubmit}
      />,
    );

    expect(screen.getByLabelText("Provider")).toHaveValue("PRV-1");
    expect(screen.getByLabelText("Open slot")).toHaveValue("SLT-1");
    const submit = screen.getByRole("button", { name: "Book appointment" });
    expect(submit).toBeEnabled();

    await user.click(submit);
    expect(onSubmit).toHaveBeenCalledOnce();
  });

  it("disables submission while the app is loading", () => {
    render(
      <AppointmentBookingForm
        booking={booking}
        setBooking={vi.fn()}
        providers={[{ id: "PRV-1", name: "Dr. Chen", specialty: "GENERAL" }]}
        slots={[{ id: "SLT-1", startTime: "2026-10-28T10:05:00.000Z" }]}
        busy={false}
        loading
        onSubmit={vi.fn()}
      />,
    );

    expect(screen.getByRole("button", { name: "Book appointment" })).toBeDisabled();
  });
});
