import { displayTrigger, formatDateTime } from "../lib/formatters.js";

export default function SuggestionCard({
  suggestion,
  specialtyChange,
  busy,
  patientConfirmed,
  onPatientConfirmationChange,
  onDecide,
}) {
  const source = ["AI", "RULE_BASED"].includes(suggestion.source)
    ? suggestion.source
    : "UNKNOWN";

  return (
    <article className="suggestion">
      <div className="suggestion-head">
        <div className="patient-summary">
          <b>{suggestion.patientName}</b>
          <span>Needs a new appointment time</span>
        </div>
        <div className="badges">
          <span className="badge">{displayTrigger(suggestion.triggerReason)}</span>
          <span className={`badge source-${source.toLowerCase()}`}>
            Source: {source}
          </span>
        </div>
      </div>
      <div className="schedule-change">
        <div className="schedule-detail">
          <span>Current booking</span>
          <strong>{suggestion.originalProviderName}</strong>
          <small>{formatDateTime(suggestion.originalTime)}</small>
        </div>
        <span className="schedule-arrow" aria-hidden="true">
          →
        </span>
        <div className="schedule-detail recommended">
          <span>Suggested booking</span>
          <strong>{suggestion.providerName}</strong>
          <small>{formatDateTime(suggestion.recommendedTime)}</small>
        </div>
      </div>
      {specialtyChange && (
        <p className="specialty-warning" role="note">
          Specialty change: {specialtyChange.from} to {specialtyChange.to}.
          Confirm this alternative is appropriate before accepting.
        </p>
      )}
      <div className="confidence">
        {source === "AI" && suggestion.confidence > 0 ? (
          <>
            <span title="Self-reported by the language model; not calibrated against historical outcomes.">
              Model-estimated confidence
            </span>{" "}
            <b>{Math.round(suggestion.confidence * 100)}%</b>
          </>
        ) : source === "AI" ? (
          <>
            <span title="Self-reported by the language model; not calibrated against historical outcomes.">
              Model-estimated confidence
            </span>{" "}
            <b>Not scored</b>
          </>
        ) : source === "RULE_BASED" ? (
          "Rule-based recommendation"
        ) : (
          "Confidence unavailable"
        )}
      </div>
      <p className="reason-label">Why this was suggested</p>
      <p className="reason">“{suggestion.reasoning}”</p>
      <label className="patient-confirmation">
        <input
          type="checkbox"
          checked={patientConfirmed}
          disabled={busy}
          onChange={(event) =>
            onPatientConfirmationChange(suggestion.id, event.target.checked)
          }
        />
        <span>I confirmed the proposed time with the patient.</span>
      </label>
      <div className="actions">
        <button
          type="button"
          className="accept"
          disabled={busy || !patientConfirmed}
          onClick={() => onDecide(suggestion.id, "ACCEPT")}
        >
          Accept recommendation
        </button>
        <button
          type="button"
          className="reject"
          disabled={busy}
          onClick={() => onDecide(suggestion.id, "REJECT")}
        >
          Reject
        </button>
      </div>
    </article>
  );
}
