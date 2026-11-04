export function formatDateTime(value) {
  if (!value) return "Time unavailable";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "Time unavailable"
    : date.toLocaleString([], {
        month: "short",
        day: "numeric",
        year: "numeric",
        hour: "numeric",
        minute: "2-digit",
      });
}

export function formatDate(value) {
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime())
    ? "Date unavailable"
    : date.toLocaleDateString([], {
        month: "short",
        day: "numeric",
        year: "numeric",
      });
}

export function displaySpecialty(specialty) {
  return String(specialty || "")
    .replaceAll("_", " ")
    .toLowerCase()
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

export function getSpecialtyChange(suggestion, appointments, providers, slots) {
  const appointment = appointments.find(
    (item) => item.id === suggestion.appointmentId,
  );
  const originalProvider = providers.find(
    (item) => item.id === appointment?.providerId,
  );
  const recommendedSlot = slots.find((item) => item.id === suggestion.slotId);
  const recommendedProvider = providers.find(
    (item) => item.id === recommendedSlot?.providerId,
  );

  if (
    !originalProvider?.specialty ||
    !recommendedProvider?.specialty ||
    originalProvider.specialty === recommendedProvider.specialty
  ) {
    return null;
  }

  return {
    from: displaySpecialty(originalProvider.specialty),
    to: displaySpecialty(recommendedProvider.specialty),
  };
}

export function displayTrigger(triggerReason) {
  return triggerReason === "INITIAL"
    ? "MANUAL"
    : String(triggerReason || "UNKNOWN").replaceAll("_", " ");
}

export function localDateKey(value) {
  if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value))
    return value;
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export function shiftDay(day, offset) {
  const [year, month, date] = day.split("-").map(Number);
  return localDateKey(new Date(year, month - 1, date + offset));
}

export function typeClass(type) {
  return `type-${String(type || "routine")
    .toLowerCase()
    .replaceAll("_", "-")}`;
}
