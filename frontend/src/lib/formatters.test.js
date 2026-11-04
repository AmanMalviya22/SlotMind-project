import { describe, expect, it } from "vitest";
import {
  displaySpecialty,
  displayTrigger,
  formatDate,
  formatDateTime,
  getSpecialtyChange,
  localDateKey,
  shiftDay,
  typeClass,
} from "./formatters.js";

describe("date and label formatters", () => {
  it("formats appointment times to the minute", () => {
    const formatted = formatDateTime("2026-10-28T08:35:17.000Z");

    expect(formatted).toContain("2026");
    expect(formatted).not.toMatch(/\b\d{1,2}:\d{2}:\d{2}\b/);
  });

  it("returns a clear fallback for missing or invalid dates", () => {
    expect(formatDateTime(null)).toBe("Time unavailable");
    expect(formatDateTime("not-a-date")).toBe("Time unavailable");
    expect(formatDate("not-a-date")).toBe("Date unavailable");
  });

  it("formats specialties and trigger reasons for people", () => {
    expect(displaySpecialty("PEDIATRIC")).toBe("Pediatric");
    expect(displaySpecialty("FOLLOW_UP")).toBe("Follow Up");
    expect(displayTrigger("INITIAL")).toBe("MANUAL");
    expect(displayTrigger("PROVIDER_OFFLINE")).toBe("PROVIDER OFFLINE");
  });

  it("finds a proposed specialty change from linked appointment data", () => {
    const change = getSpecialtyChange(
      { appointmentId: "APT-1", slotId: "SLT-2" },
      [{ id: "APT-1", providerId: "PRV-1" }],
      [
        { id: "PRV-1", specialty: "PEDIATRIC" },
        { id: "PRV-2", specialty: "SPECIALIST" },
      ],
      [{ id: "SLT-2", providerId: "PRV-2" }],
    );

    expect(change).toEqual({ from: "Pediatric", to: "Specialist" });
    expect(
      getSpecialtyChange(
        { appointmentId: "APT-1", slotId: "SLT-1" },
        [{ id: "APT-1", providerId: "PRV-1" }],
        [{ id: "PRV-1", specialty: "GENERAL" }],
        [{ id: "SLT-1", providerId: "PRV-1" }],
      ),
    ).toBeNull();
  });

  it("keeps schedule dates local and appointment types consistent", () => {
    expect(formatDate(new Date(2026, 9, 28))).toContain("2026");
    expect(localDateKey("2026-10-28")).toBe("2026-10-28");
    expect(shiftDay("2026-10-28", 1)).toBe("2026-10-29");
    expect(typeClass("FOLLOW_UP")).toBe("type-follow-up");
  });
});
