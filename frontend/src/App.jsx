import React, { useCallback, useEffect, useRef, useState } from "react";
import { API, request } from "./lib/api.js";
import AppointmentBookingForm from "./components/AppointmentBookingForm.jsx";
import EmptyState from "./components/EmptyState.jsx";
import ProviderStatusPanel from "./components/ProviderStatusPanel.jsx";
import RecoverySlotForm from "./components/RecoverySlotForm.jsx";
import SuggestionCard from "./components/SuggestionCard.jsx";
import {
  displayTrigger,
  formatDate,
  formatDateTime,
  getSpecialtyChange,
  localDateKey,
  shiftDay,
  typeClass,
} from "./lib/formatters.js";
import "./styles.css";

const EMPTY_DATA = {
  providers: [],
  appointments: [],
  slots: [],
  suggestions: [],
};

export default function App() {
  const [data, setData] = useState(EMPTY_DATA);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [lastUpdated, setLastUpdated] = useState(null);
  const [streamProgress, setStreamProgress] = useState("");
  const [patientConfirmations, setPatientConfirmations] = useState({});
  const [selectedDay, setSelectedDay] = useState(() =>
    localDateKey(new Date()),
  );
  const [waitlist, setWaitlist] = useState(() => {
    try {
      const saved = window.localStorage.getItem("slotmind-waitlist");
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  });
  const [waitlistDraft, setWaitlistDraft] = useState({
    patientName: "",
    type: "ROUTINE",
  });
  const [booking, setBooking] = useState({
    patientName: "",
    type: "ROUTINE",
    providerId: "",
    slotId: "",
  });
  const sequence = useRef(0);
  const initialLoad = useRef(true);

  const load = useCallback(async () => {
    const current = ++sequence.current;

    try {
      const [providers, appointments, slots, suggestions] = await Promise.all([
        request("/providers"),
        request("/appointments"),
        request("/slots"),
        request("/suggestions"),
      ]);

      if (current === sequence.current) {
        setData({ providers, appointments, slots, suggestions });
        setError("");
        setLastUpdated(new Date());
      }
    } catch (requestError) {
      if (current === sequence.current) {
        setError(
          requestError.message ||
            "Backend unavailable. Start Spring Boot on port 8080.",
        );
      }
    } finally {
      if (current === sequence.current && initialLoad.current) {
        initialLoad.current = false;
        setLoading(false);
      }
    }
  }, []);

  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), 5000);
    return () => window.clearInterval(timer);
  }, [load]);

  useEffect(() => {
    try {
      window.localStorage.setItem(
        "slotmind-waitlist",
        JSON.stringify(waitlist),
      );
    } catch {
      // The front desk can still use the queue for this session if storage is unavailable.
    }
  }, [waitlist]);

  useEffect(() => {
    const isBookableProvider = (provider) =>
      provider.status !== "OFFLINE" &&
      data.slots.some(
        (slot) =>
          slot.status === "OPEN" &&
          new Date(slot.startTime).getTime() > Date.now() &&
          slot.providerId === provider.id,
      );
    const provider =
      data.providers.find(
        (item) => item.id === booking.providerId && isBookableProvider(item),
      ) || data.providers.find(isBookableProvider);
    const providerId = provider?.id || "";
    const slotsForProvider = data.slots.filter(
      (slot) =>
        slot.status === "OPEN" &&
        new Date(slot.startTime).getTime() > Date.now() &&
        slot.providerId === providerId,
    );
    const slotId = slotsForProvider.some((slot) => slot.id === booking.slotId)
      ? booking.slotId
      : slotsForProvider[0]?.id || "";

    if (providerId !== booking.providerId || slotId !== booking.slotId) {
      setBooking((current) => ({ ...current, providerId, slotId }));
    }
  }, [data.providers, data.slots, booking.providerId, booking.slotId]);

  const mutate = async (operation) => {
    setBusy(true);
    setError("");
    try {
      await operation();
      await load();
    } catch (operationError) {
      setError(
        operationError.message || "The operation could not be completed.",
      );
    } finally {
      setBusy(false);
    }
  };

  const updateProviderStatus = (providerId, status) =>
    mutate(() =>
      request(`/providers/${encodeURIComponent(providerId)}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status }),
      }),
    );

  const decideSuggestion = (suggestionId, action) =>
    mutate(() =>
      request(`/suggestions/${encodeURIComponent(suggestionId)}`, {
        method: "PATCH",
        body: JSON.stringify({
          action,
          patientConfirmed:
            action === "ACCEPT" && patientConfirmations[suggestionId] === true,
        }),
      }),
    );

  const streamSuggestion = async (appointmentId) => {
    setStreamProgress("Connecting to live recommendation stream…");
    const response = await fetch(
      `${API}/appointments/${encodeURIComponent(appointmentId)}/suggest/stream`,
      { method: "POST", headers: { Accept: "text/event-stream" } },
    );
    if (!response.ok) {
      let message = `Request failed (${response.status})`;
      try {
        const body = await response.json();
        message = body.error || message;
      } catch {
        // Keep the HTTP status message if the response is not JSON.
      }
      throw new Error(message);
    }
    if (!response.body)
      throw new Error("The browser does not support streamed responses.");

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let receivedSuggestion = false;

    const handleEvent = (block) => {
      if (!block.trim()) return;
      const lines = block.split(/\r?\n/);
      const eventName = lines
        .find((line) => line.startsWith("event:"))
        ?.slice(6)
        .trim();
      const payloadText = lines
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).trim())
        .join("\n");
      if (!payloadText) return;

      let payload;
      try {
        payload = JSON.parse(payloadText);
      } catch {
        payload = { message: payloadText };
      }

      if (eventName === "progress") {
        setStreamProgress(payload.message || "Working on a recommendation…");
      } else if (eventName === "reasoning") {
        setStreamProgress(
          payload.reasoning
            ? `Explanation: ${payload.reasoning}`
            : "Recommendation explanation received.",
        );
      } else if (eventName === "suggestion") {
        receivedSuggestion = true;
        setStreamProgress(
          `Recommendation ready for ${payload.patientName || "front-desk review"}.`,
        );
      } else if (eventName === "workflow-error") {
        throw new Error(
          payload.message || "No suitable replacement is available.",
        );
      } else if (eventName === "done") {
        setStreamProgress(payload.message || "Recommendation complete.");
      }
    };

    try {
      while (true) {
        const { value, done } = await reader.read();
        buffer += decoder.decode(value || new Uint8Array(), {
          stream: !done,
        });
        const blocks = buffer.split(/\r?\n\r?\n/);
        buffer = blocks.pop() || "";
        blocks.forEach(handleEvent);
        if (done) break;
      }
      if (buffer.trim()) handleEvent(buffer);
      if (!receivedSuggestion)
        throw new Error("The stream ended without a recommendation.");
    } finally {
      reader.releaseLock();
    }
  };

  const requestManualSuggestion = (appointmentId) =>
    mutate(() => streamSuggestion(appointmentId));

  const createRecoverySlotAndRetry = (appointmentId, slotDetails) =>
    mutate(async () => {
      await request("/slots", {
        method: "POST",
        body: JSON.stringify(slotDetails),
      });
      // Refresh even if the subsequent SSE attempt fails, so the new slot remains visible.
      await load();
      await streamSuggestion(appointmentId);
    });

  const addWaitlistEntry = (event) => {
    event.preventDefault();
    const patientName = waitlistDraft.patientName.trim();
    if (!patientName) return;
    setWaitlist((current) => [
      ...current,
      {
        id: `WL-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`,
        patientName,
        type: waitlistDraft.type,
        createdAt: new Date().toISOString(),
      },
    ]);
    setWaitlistDraft({ patientName: "", type: "ROUTINE" });
  };

  const serveWaitlistEntry = (entry) =>
    mutate(async () => {
      const nextSlot = data.slots
        .filter(
          (slot) =>
            slot.status === "OPEN" &&
            new Date(slot.startTime).getTime() > Date.now() &&
            data.providers.some(
              (provider) =>
                provider.id === slot.providerId &&
                provider.status !== "OFFLINE",
            ),
        )
        .sort(
          (left, right) => new Date(left.startTime) - new Date(right.startTime),
        )[0];
      if (!nextSlot)
        throw new Error(
          "No eligible future slot is open. The patient remains on the waitlist.",
        );
      await request("/appointments", {
        method: "POST",
        body: JSON.stringify({
          patientName: entry.patientName,
          type: entry.type,
          providerId: nextSlot.providerId,
          slotId: nextSlot.id,
        }),
      });
      setWaitlist((current) => current.filter((item) => item.id !== entry.id));
    });

  const removeWaitlistEntry = (entryId) => {
    setWaitlist((current) => current.filter((entry) => entry.id !== entryId));
  };

  const bookAppointment = async (event) => {
    event.preventDefault();
    await mutate(async () => {
      await request("/appointments", {
        method: "POST",
        body: JSON.stringify({
          ...booking,
          patientName: booking.patientName.trim(),
        }),
      });
      setBooking((current) => ({ ...current, patientName: "" }));
    });
  };

  const bookableProviders = data.providers.filter(
    (provider) =>
      provider.status !== "OFFLINE" &&
      data.slots.some(
        (slot) =>
          slot.status === "OPEN" &&
          new Date(slot.startTime).getTime() > Date.now() &&
          slot.providerId === provider.id,
      ),
  );
  const bookableSlots = data.slots.filter(
    (slot) =>
      slot.status === "OPEN" &&
      new Date(slot.startTime).getTime() > Date.now() &&
      slot.providerId === booking.providerId,
  );

  const pendingSuggestions = data.suggestions.filter(
    (suggestion) => suggestion.status === "PENDING",
  );
  const unresolvedAppointments = data.appointments.filter(
    (appointment) =>
      appointment.status === "RESCHEDULE_PENDING" &&
      !pendingSuggestions.some(
        (suggestion) => suggestion.appointmentId === appointment.id,
      ),
  );
  const daySlots = data.slots
    .filter((slot) => localDateKey(slot.startTime) === selectedDay)
    .slice()
    .sort(
      (left, right) => new Date(left.startTime) - new Date(right.startTime),
    );
  const dayAppointments = data.appointments.filter(
    (appointment) => localDateKey(appointment.startTime) === selectedDay,
  );
  const appointmentBySlot = new Map(
    data.appointments.map((appointment) => [appointment.slotId, appointment]),
  );

  return (
    <main>
      <header className="topbar">
        <div className="brand-lockup">
          <span className="brand-mark" aria-hidden="true">
            S
          </span>
          <div className="brand-copy">
            <span className="eyebrow">CareBridge / Operations</span>
            <h1>
              Slot<span>Mind</span>
            </h1>
            <p>Appointment rescheduling</p>
          </div>
        </div>
        <div className="header-actions">
          <div className="sync-status" aria-live="polite">
            <span className={`sync-dot ${error ? "offline" : ""}`} />
            <span>
              {loading
                ? "Loading data"
                : error
                  ? "Connection issue"
                  : "Live updates"}
            </span>
            {lastUpdated && (
              <small>
                Updated at {lastUpdated.toLocaleTimeString([], {
                  hour: "numeric",
                  minute: "2-digit",
                })}
              </small>
            )}
          </div>
          <button
            type="button"
            className="refresh-button"
            onClick={() => void load()}
            disabled={busy || loading}
          >
            Refresh data
          </button>
        </div>
      </header>

      {error && (
        <div className="error" role="alert">
          <strong>Action needs attention</strong>
          <span>{error}</span>
        </div>
      )}

      {loading && (
        <p className="loading" role="status">
          Loading appointment data…
        </p>
      )}

      <section className="stats" aria-label="Scheduling overview">
        <Stat
          value={
            data.appointments.filter((item) => item.status === "CONFIRMED")
              .length
          }
          label="Confirmed appointments"
        />
        <Stat
          value={
            data.appointments.filter(
              (item) => item.status === "RESCHEDULE_PENDING",
            ).length
          }
          label="Appointments needing review"
        />
        <Stat value={pendingSuggestions.length} label="Pending suggestions" />
        <Stat
          value={data.slots.filter((slot) => slot.status === "OPEN").length}
          label="Open slots"
        />
      </section>

      <AppointmentBookingForm
        booking={booking}
        setBooking={setBooking}
        providers={bookableProviders}
        slots={bookableSlots}
        busy={busy}
        loading={loading}
        onSubmit={bookAppointment}
      />

      <div className="grid">
        <ProviderStatusPanel
          providers={data.providers}
          loading={loading}
          busy={busy}
          onStatusChange={updateProviderStatus}
        />

        <section className="card wide" aria-labelledby="queue-heading">
          <h2 id="queue-heading">
            Rescheduling queue{" "}
            <small>Updates automatically every 5 seconds</small>
          </h2>
          {streamProgress && (
            <p className="loading stream-progress" role="status">
              {streamProgress}
            </p>
          )}
          {pendingSuggestions.length === 0 &&
          unresolvedAppointments.length === 0 ? (
            <EmptyState message="No pending recommendations. Set a provider to OFFLINE or request a streamed manual suggestion." />
          ) : (
            pendingSuggestions.map((suggestion) => {
              const specialtyChange = getSpecialtyChange(
                suggestion,
                data.appointments,
                data.providers,
                data.slots,
              );

              return (
                <SuggestionCard
                  key={suggestion.id}
                  suggestion={suggestion}
                  specialtyChange={specialtyChange}
                  busy={busy}
                  patientConfirmed={
                    patientConfirmations[suggestion.id] === true
                  }
                  onPatientConfirmationChange={(id, confirmed) =>
                    setPatientConfirmations((current) => ({
                      ...current,
                      [id]: confirmed,
                    }))
                  }
                  onDecide={decideSuggestion}
                />
              );
            })
          )}
          {unresolvedAppointments.map((appointment) => (
            <article className="suggestion unresolved" key={appointment.id}>
              <div className="suggestion-head">
                <b>{appointment.patientName}</b>
                <div className="badges">
                  <span className="badge source-unknown">
                    NO REPLACEMENT YET
                  </span>
                  <span className={`badge ${typeClass(appointment.type)}`}>
                    {appointment.type.replaceAll("_", " ")}
                  </span>
                </div>
              </div>
              <p>
                The appointment needs recovery, but there is no pending
                recommendation yet. Create a future slot for an available
                provider, then retry. Cancelled slots remain cancelled.
              </p>
              <RecoverySlotForm
                providers={data.providers}
                busy={busy}
                onCreateAndRetry={(slotDetails) =>
                  createRecoverySlotAndRetry(appointment.id, slotDetails)
                }
              />
              <div className="actions">
                <button
                  type="button"
                  className="accept"
                  disabled={busy}
                  onClick={() => requestManualSuggestion(appointment.id)}
                >
                  Retry streamed suggestion
                </button>
              </div>
            </article>
          ))}
        </section>
      </div>

      <section className="card" aria-labelledby="appointments-heading">
        <h2 id="appointments-heading">Appointments</h2>
        {data.appointments.length === 0 && !loading ? (
          <EmptyState message="No appointments found." />
        ) : (
          <div className="table">
            <div className="appointment-table-head" aria-hidden="true">
              <span>Patient</span>
              <span>Provider and time</span>
              <span>Status</span>
              <span>Action</span>
            </div>
            {data.appointments.map((appointment) => (
              <div className="row appointment-row" key={appointment.id}>
                <span>
                  <b>{appointment.patientName}</b>
                  <small>
                    <span
                      className={`type-chip ${typeClass(appointment.type)}`}
                    >
                      {appointment.type.replaceAll("_", " ")}
                    </span>{" "}
                    · {appointment.id}
                  </small>
                </span>
                <span>
                  {appointment.providerName}
                  <small>{formatDateTime(appointment.startTime)}</small>
                </span>
                <span className={`state ${appointment.status}`}>
                  {appointment.status.replaceAll("_", " ")}
                </span>
                <button
                  type="button"
                  className="secondary-action"
                  disabled={
                    busy ||
                    !["CONFIRMED", "RESCHEDULE_PENDING"].includes(
                      appointment.status,
                    ) ||
                    pendingSuggestions.some(
                      (suggestion) =>
                        suggestion.appointmentId === appointment.id,
                    )
                  }
                  onClick={() => requestManualSuggestion(appointment.id)}
                  title={
                    pendingSuggestions.some(
                      (suggestion) =>
                        suggestion.appointmentId === appointment.id,
                    )
                      ? "A suggestion is already pending for this appointment"
                      : ["CONFIRMED", "RESCHEDULE_PENDING"].includes(
                            appointment.status,
                          )
                        ? "Request a manual rescheduling suggestion"
                        : "Only appointments awaiting rescheduling can be suggested"
                  }
                >
                  Suggest
                </button>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="card day-card" aria-labelledby="day-heading">
        <h2 id="day-heading">
          Full-day schedule <small>appointments and slot status</small>
        </h2>
        <div className="day-controls">
          <button
            type="button"
            onClick={() => setSelectedDay((day) => shiftDay(day, -1))}
            disabled={busy}
          >
            ← Previous day
          </button>
          <label>
            <span className="sr-only">Choose schedule date</span>
            <input
              type="date"
              value={selectedDay}
              onChange={(event) => setSelectedDay(event.target.value)}
            />
          </label>
          <button
            type="button"
            onClick={() => setSelectedDay((day) => shiftDay(day, 1))}
            disabled={busy}
          >
            Next day →
          </button>
          <span className="day-count">
            {dayAppointments.length} appointments · {daySlots.length} slots
          </span>
        </div>
        {daySlots.length === 0 ? (
          <EmptyState message="No slot records for this date. Choose another day or create slots in the backend." />
        ) : (
          <div className="day-grid">
            {daySlots.map((slot) => {
              const appointment = appointmentBySlot.get(slot.id);
              return (
                <article
                  className={`day-slot ${slot.status.toLowerCase()}`}
                  key={slot.id}
                >
                  <div className="day-slot-time">
                    {new Date(slot.startTime).toLocaleTimeString([], {
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </div>
                  <div className="day-slot-content">
                    <strong>{slot.providerName}</strong>
                    <small>
                      {slot.durationMinutes} min · {slot.id}
                    </small>
                    {appointment ? (
                      <p>
                        <span
                          className={`type-chip ${typeClass(appointment.type)}`}
                        >
                          {appointment.type.replaceAll("_", " ")}
                        </span>{" "}
                        {appointment.patientName}
                      </p>
                    ) : (
                      <p className="muted">No appointment assigned</p>
                    )}
                  </div>
                  <span className={`slot-state ${slot.status}`}>
                    {slot.status}
                  </span>
                </article>
              );
            })}
          </div>
        )}
      </section>

      <section
        className="card provider-load-card"
        aria-labelledby="load-heading"
      >
        <h2 id="load-heading">
          Provider load{" "}
          <small>
            Booked slots for {formatDate(new Date(`${selectedDay}T12:00:00`))}
          </small>
        </h2>
        <div className="load-list">
          {data.providers.map((provider) => {
            const providerSlots = daySlots.filter(
              (slot) => slot.providerId === provider.id,
            );
            const bookedCount = providerSlots.filter(
              (slot) => slot.status === "BOOKED",
            ).length;
            const loadPercent = providerSlots.length
              ? Math.round((bookedCount / providerSlots.length) * 100)
              : 0;
            const activeCount = dayAppointments.filter(
              (appointment) =>
                appointment.providerId === provider.id &&
                ["CONFIRMED", "RESCHEDULE_PENDING"].includes(
                  appointment.status,
                ),
            ).length;
            return (
              <div className="load-row" key={provider.id}>
                <div className="load-heading">
                  <span>
                    <b>{provider.name}</b>
                    <small>
                      {provider.specialty} · {provider.status}
                    </small>
                  </span>
                  <strong>
                    {bookedCount}/{providerSlots.length} booked · {activeCount}{" "}
                    active
                  </strong>
                </div>
                <div
                  className="load-track"
                  role="img"
                  aria-label={`${provider.name}: ${loadPercent}% of slots booked`}
                >
                  <span style={{ width: `${loadPercent}%` }} />
                </div>
              </div>
            );
          })}
        </div>
      </section>

      <section
        className="card waitlist-card"
        aria-labelledby="waitlist-heading"
      >
        <h2 id="waitlist-heading">
          Waitlist <small>saved in this browser</small>
        </h2>
        <form className="waitlist-form" onSubmit={addWaitlistEntry}>
          <label>
            Patient name
            <input
              required
              maxLength={120}
              value={waitlistDraft.patientName}
              onChange={(event) =>
                setWaitlistDraft((current) => ({
                  ...current,
                  patientName: event.target.value,
                }))
              }
              placeholder="Add patient waiting for a slot"
            />
          </label>
          <label>
            Appointment type
            <select
              value={waitlistDraft.type}
              onChange={(event) =>
                setWaitlistDraft((current) => ({
                  ...current,
                  type: event.target.value,
                }))
              }
            >
              <option value="ROUTINE">Routine</option>
              <option value="URGENT">Urgent</option>
              <option value="FOLLOW_UP">Follow-up</option>
            </select>
          </label>
          <button type="submit" disabled={!waitlistDraft.patientName.trim()}>
            Add to waitlist
          </button>
        </form>
        {waitlist.length === 0 ? (
          <EmptyState message="The waitlist is empty." />
        ) : (
          waitlist.map((entry) => (
            <div className="row waitlist-row" key={entry.id}>
              <span>
                <b>{entry.patientName}</b>
                <small>
                  <span className={`type-chip ${typeClass(entry.type)}`}>
                    {entry.type.replaceAll("_", " ")}
                  </span>{" "}
                  · Added {formatDateTime(entry.createdAt)}
                </small>
              </span>
              <div className="actions">
                <button
                  type="button"
                  className="accept secondary-action"
                  disabled={busy}
                  onClick={() => serveWaitlistEntry(entry)}
                >
                  Book next open slot
                </button>
                <button
                  type="button"
                  className="reject secondary-action"
                  onClick={() => removeWaitlistEntry(entry.id)}
                >
                  Remove
                </button>
              </div>
            </div>
          ))
        )}
      </section>

      <section className="card slots-card" aria-labelledby="slots-heading">
        <h2 id="slots-heading">
          All slots <small>Provider, time, and availability</small>
        </h2>
        {data.slots.length === 0 && !loading ? (
          <EmptyState message="No slots found." />
        ) : (
          <div className="slot-list">
            {data.slots
              .slice()
              .sort(
                (left, right) =>
                  new Date(left.startTime) - new Date(right.startTime),
              )
              .map((slot) => (
                <div className="slot-item" key={slot.id}>
                  <div className="slot-details">
                    <b>{slot.providerName}</b>
                    <small>
                      {formatDateTime(slot.startTime)} · {slot.durationMinutes}{" "}
                      min · {slot.id}
                    </small>
                  </div>
                  <span className={`slot-state ${slot.status}`}>
                    {slot.status}
                  </span>
                </div>
              ))}
          </div>
        )}
      </section>
    </main>
  );
}

function Stat({ value, label }) {
  return (
    <div className="stat">
      <b>{value}</b>
      <span>{label}</span>
    </div>
  );
}


