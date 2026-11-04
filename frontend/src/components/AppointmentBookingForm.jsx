import { formatDateTime } from "../lib/formatters.js";

export default function AppointmentBookingForm({
  booking,
  setBooking,
  providers,
  slots,
  busy,
  loading,
  onSubmit,
}) {
  return (
    <section className="card booking-card" aria-labelledby="booking-heading">
      <h2 id="booking-heading">
        Book an appointment <small>Choose a patient and an open slot.</small>
      </h2>
      <form className="booking-form" onSubmit={onSubmit}>
        <label>
          Patient name
          <input
            required
            maxLength={120}
            value={booking.patientName}
            onChange={(event) =>
              setBooking((current) => ({
                ...current,
                patientName: event.target.value,
              }))
            }
            placeholder="Enter patient name"
          />
        </label>
        <label>
          Appointment type
          <select
            required
            value={booking.type}
            onChange={(event) =>
              setBooking((current) => ({ ...current, type: event.target.value }))
            }
          >
            <option value="ROUTINE">Routine</option>
            <option value="URGENT">Urgent</option>
            <option value="FOLLOW_UP">Follow-up</option>
          </select>
        </label>
        <label>
          Provider
          <select
            required
            value={booking.providerId}
            disabled={busy || providers.length === 0}
            onChange={(event) =>
              setBooking((current) => ({
                ...current,
                providerId: event.target.value,
                slotId: "",
              }))
            }
          >
            {providers.length === 0 ? (
              <option value="">No eligible provider</option>
            ) : (
              providers.map((provider) => (
                <option key={provider.id} value={provider.id}>
                  {provider.name} · {provider.specialty}
                </option>
              ))
            )}
          </select>
        </label>
        <label>
          Open slot
          <select
            required
            value={booking.slotId}
            disabled={busy || slots.length === 0}
            onChange={(event) =>
              setBooking((current) => ({
                ...current,
                slotId: event.target.value,
              }))
            }
          >
            {slots.length === 0 ? (
              <option value="">No future open slot</option>
            ) : (
              slots.map((slot) => (
                <option key={slot.id} value={slot.id}>
                  {formatDateTime(slot.startTime)} · {slot.durationMinutes} min
                </option>
              ))
            )}
          </select>
        </label>
        <div className="booking-submit">
          <button
            type="submit"
            className="accept"
            disabled={
              busy ||
              loading ||
              !booking.patientName.trim() ||
              !booking.providerId ||
              !booking.slotId
            }
          >
            Book appointment
          </button>
        </div>
      </form>
    </section>
  );
}
