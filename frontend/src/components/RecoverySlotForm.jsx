import { useState } from "react";

function currentLocalDateTime() {
  const now = new Date(Date.now() - new Date().getTimezoneOffset() * 60_000);
  return now.toISOString().slice(0, 16);
}

export default function RecoverySlotForm({
  providers,
  busy,
  onCreateAndRetry,
}) {
  const availableProviders = providers.filter(
    (provider) => provider.status !== "OFFLINE",
  );
  const [providerId, setProviderId] = useState(
    availableProviders[0]?.id || "",
  );
  const [startTime, setStartTime] = useState("");
  const [durationMinutes, setDurationMinutes] = useState("30");
  const selectedProviderId = availableProviders.some(
    (provider) => provider.id === providerId,
  )
    ? providerId
    : availableProviders[0]?.id || "";

  const submit = (event) => {
    event.preventDefault();
    const parsedStartTime = new Date(startTime);
    if (!selectedProviderId || Number.isNaN(parsedStartTime.getTime())) return;

    onCreateAndRetry({
      providerId: selectedProviderId,
      startTime: parsedStartTime.toISOString(),
      durationMinutes: Number(durationMinutes),
    });
  };

  if (availableProviders.length === 0) {
    return (
      <p className="recovery-slot-empty">
        No available provider can receive a replacement slot right now.
      </p>
    );
  }

  return (
    <form className="recovery-slot-form" onSubmit={submit}>
      <label>
        Provider for replacement slot
        <select
          required
          value={selectedProviderId}
          disabled={busy}
          onChange={(event) => setProviderId(event.target.value)}
        >
          {availableProviders.map((provider) => (
            <option key={provider.id} value={provider.id}>
              {provider.name} · {provider.specialty}
            </option>
          ))}
        </select>
      </label>
      <label>
        Start time for replacement slot
        <input
          required
          type="datetime-local"
          min={currentLocalDateTime()}
          value={startTime}
          disabled={busy}
          onChange={(event) => setStartTime(event.target.value)}
        />
      </label>
      <label>
        Duration in minutes
        <input
          required
          type="number"
          min="1"
          max="480"
          step="1"
          value={durationMinutes}
          disabled={busy}
          onChange={(event) => setDurationMinutes(event.target.value)}
        />
      </label>
      <button
        type="submit"
        className="accept"
        disabled={
          busy ||
          !selectedProviderId ||
          !startTime ||
          !Number.isInteger(Number(durationMinutes)) ||
          Number(durationMinutes) <= 0 ||
          Number(durationMinutes) > 480
        }
      >
        Create slot and retry
      </button>
    </form>
  );
}
