import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App.jsx";

const { requestMock } = vi.hoisted(() => ({
  requestMock: vi.fn(),
}));

vi.mock("./lib/api.js", () => ({
  API: "http://localhost:8080/api",
  request: requestMock,
}));

const providers = [
  {
    id: "P-1",
    name: "Dr. Rao",
    specialty: "GENERAL",
    status: "AVAILABLE",
  },
  {
    id: "P-2",
    name: "Dr. Iyer",
    specialty: "GENERAL",
    status: "AVAILABLE",
  },
];

const slots = [
  {
    id: "SL-1",
    providerId: "P-1",
    providerName: "Dr. Rao",
    startTime: "2099-11-04T09:00:00Z",
    durationMinutes: 30,
    status: "OPEN",
  },
  {
    id: "SL-2",
    providerId: "P-2",
    providerName: "Dr. Iyer",
    startTime: "2099-11-04T10:00:00Z",
    durationMinutes: 30,
    status: "OPEN",
  },
];

const appointments = [];
const suggestions = [];

function configureApi(fixtures = {}) {
  const data = {
    providers: fixtures.providers ?? providers,
    appointments: fixtures.appointments ?? appointments,
    slots: fixtures.slots ?? slots,
    suggestions: fixtures.suggestions ?? suggestions,
  };

  requestMock.mockImplementation(async (path, options = {}) => {
    if (options.method === "POST" && path === "/appointments") {
      return { id: "APT-CREATED" };
    }
    if (options.method === "POST" && path === "/slots") {
      const payload = JSON.parse(options.body);
      const provider = data.providers.find((item) => item.id === payload.providerId);
      const created = {
        id: "SL-NEW",
        providerId: payload.providerId,
        providerName: provider.name,
        startTime: payload.startTime,
        durationMinutes: payload.durationMinutes,
        status: "OPEN",
      };
      data.slots.push(created);
      return created;
    }
    if (options.method === "PATCH" && path.startsWith("/providers/")) {
      return data.providers.find((provider) =>
        path.includes(encodeURIComponent(provider.id)),
      );
    }
    if (options.method === "PATCH" && path.startsWith("/suggestions/")) {
      return data.suggestions.find((suggestion) =>
        path.includes(encodeURIComponent(suggestion.id)),
      );
    }
    if (path === "/providers") return data.providers;
    if (path === "/appointments") return data.appointments;
    if (path === "/slots") return data.slots;
    if (path === "/suggestions") return data.suggestions;
    throw new Error(`Unexpected API request: ${options.method || "GET"} ${path}`);
  });
  return data;
}

describe("SlotMind dashboard", () => {
  beforeEach(() => {
    requestMock.mockReset();
    configureApi();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("loads the scheduling dashboard from all backend resources", async () => {
    render(<App />);

    expect(
      await screen.findByRole("heading", { name: /SlotMind/ }),
    ).toBeInTheDocument();
    expect(await screen.findByRole("combobox", { name: "Status for Dr. Rao" })).toBeInTheDocument();
    expect(screen.getByText("No pending recommendations. Set a provider to OFFLINE or request a streamed manual suggestion.")).toBeInTheDocument();

    expect(requestMock).toHaveBeenCalledWith("/providers");
    expect(requestMock).toHaveBeenCalledWith("/appointments");
    expect(requestMock).toHaveBeenCalledWith("/slots");
    expect(requestMock).toHaveBeenCalledWith("/suggestions");
  });

  it("books an appointment using the selected eligible provider and slot", async () => {
    const user = userEvent.setup();
    render(<App />);

    await screen.findByRole("combobox", { name: "Status for Dr. Rao" });
    await user.type(screen.getByPlaceholderText("Enter patient name"), "Aarav Sharma");
    await user.click(screen.getByRole("button", { name: "Book appointment" }));

    await waitFor(() => {
      expect(requestMock).toHaveBeenCalledWith("/appointments", {
        method: "POST",
        body: JSON.stringify({
          patientName: "Aarav Sharma",
          type: "ROUTINE",
          providerId: "P-1",
          slotId: "SL-1",
        }),
      });
    });
  });

  it("sends provider status changes to the backend", async () => {
    const user = userEvent.setup();
    render(<App />);

    const statusSelect = await screen.findByRole("combobox", {
      name: "Status for Dr. Rao",
    });
    await user.selectOptions(statusSelect, "OFFLINE");

    await waitFor(() => {
      expect(requestMock).toHaveBeenCalledWith("/providers/P-1/status", {
        method: "PATCH",
        body: JSON.stringify({ status: "OFFLINE" }),
      });
    });
  });

  it("creates a replacement slot and refreshes the queue from the streamed suggestion", async () => {
    const user = userEvent.setup();
    const appointment = {
      id: "APT-RECOVERY",
      patientName: "Aarav",
      type: "ROUTINE",
      providerId: "P-1",
      providerName: "Dr. Rao",
      slotId: "SL-OLD",
      startTime: "2099-11-04T09:00:00Z",
      status: "RESCHEDULE_PENDING",
    };
    const data = configureApi({
      appointments: [appointment],
      providers,
      slots: [],
      suggestions: [],
    });
    const suggestion = {
      id: "SUG-RECOVERY",
      appointmentId: appointment.id,
      patientName: appointment.patientName,
      originalProviderName: "Dr. Rao",
      originalTime: appointment.startTime,
      slotId: "SL-NEW",
      providerName: "Dr. Iyer",
      recommendedTime: "2099-11-04T11:00:00Z",
      confidence: 0.85,
      reasoning: "A replacement opening matches the appointment duration.",
      status: "PENDING",
      triggerReason: "INITIAL",
      source: "AI",
    };
    const encoder = new TextEncoder();
    const frames = [
      'event:progress\ndata: {"message":"Checking replacement slots"}\n\n',
      'event:suggestion\ndata: {"patientName":"Aarav"}\n\n',
      'event:done\ndata: {"message":"Recommendation ready"}\n\n',
    ].map((frame) => encoder.encode(frame));

    vi.stubGlobal("fetch", vi.fn(async () => {
      data.suggestions.push(suggestion);
      let index = 0;
      return {
        ok: true,
        body: {
          getReader: () => ({
            read: async () => index < frames.length
              ? { done: false, value: frames[index++] }
              : { done: true, value: undefined },
            releaseLock: vi.fn(),
          }),
        },
      };
    }));

    render(<App />);
    await screen.findByText("NO REPLACEMENT YET");
    await user.selectOptions(
      screen.getByRole("combobox", { name: "Provider for replacement slot" }),
      "P-2",
    );
    fireEvent.change(screen.getByLabelText("Start time for replacement slot"), {
      target: { value: "2099-11-04T11:00" },
    });
    await user.clear(screen.getByLabelText("Duration in minutes"));
    await user.type(screen.getByLabelText("Duration in minutes"), "45");
    await user.click(screen.getByRole("button", { name: "Create slot and retry" }));

    const startTime = new Date("2099-11-04T11:00").toISOString();
    await waitFor(() => {
      expect(requestMock).toHaveBeenCalledWith("/slots", {
        method: "POST",
        body: JSON.stringify({
          providerId: "P-2",
          startTime,
          durationMinutes: 45,
        }),
      });
    });
    expect(await screen.findByText(/A replacement opening matches the appointment duration/)).toBeInTheDocument();
  });

  it("requires patient confirmation before sending an accept decision", async () => {
    const user = userEvent.setup();
    const existingAppointment = {
      id: "APT-1",
      patientName: "Aarav",
      type: "ROUTINE",
      providerId: "P-1",
      providerName: "Dr. Rao",
      slotId: "SL-1",
      startTime: "2099-11-04T09:00:00Z",
      status: "RESCHEDULE_PENDING",
    };
    const pendingSuggestion = {
      id: "SUG-1",
      appointmentId: "APT-1",
      patientName: "Aarav",
      originalProviderName: "Dr. Rao",
      originalTime: "2099-11-04T09:00:00Z",
      slotId: "SL-2",
      providerName: "Dr. Iyer",
      recommendedTime: "2099-11-04T10:00:00Z",
      confidence: 0.9,
      reasoning: "A matching provider is available.",
      status: "PENDING",
      triggerReason: "PROVIDER_OFFLINE",
      source: "AI",
    };
    configureApi({
      appointments: [existingAppointment],
      suggestions: [pendingSuggestion],
      slots: [
        { ...slots[0], status: "CANCELLED" },
        slots[1],
      ],
    });
    render(<App />);

    const confirmation = await screen.findByRole("checkbox", {
      name: "I confirmed the proposed time with the patient.",
    });
    const acceptButton = screen.getByRole("button", {
      name: "Accept recommendation",
    });

    expect(acceptButton).toBeDisabled();
    await user.click(confirmation);
    await user.click(acceptButton);

    await waitFor(() => {
      expect(requestMock).toHaveBeenCalledWith("/suggestions/SUG-1", {
        method: "PATCH",
        body: JSON.stringify({
          action: "ACCEPT",
          patientConfirmed: true,
        }),
      });
    });
  });
});
