import EmptyState from "./EmptyState.jsx";

export default function ProviderStatusPanel({ providers, loading, busy, onStatusChange }) {
  return (
    <section className="card" aria-labelledby="providers-heading">
      <h2 id="providers-heading">Provider status</h2>
      {providers.length === 0 && !loading ? (
        <EmptyState message="No providers are available from the backend." />
      ) : (
        providers.map((provider) => (
          <div className="provider" key={provider.id}>
            <div>
              <b>{provider.name}</b>
              <small>
                {provider.specialty} · {provider.id}
              </small>
            </div>
            <label>
              <span className="sr-only">Status for {provider.name}</span>
              <select
                disabled={busy}
                value={provider.status}
                onChange={(event) =>
                  onStatusChange(provider.id, event.target.value)
                }
              >
                <option value="AVAILABLE">AVAILABLE</option>
                <option value="BUSY">BUSY</option>
                <option value="OFFLINE">OFFLINE</option>
              </select>
            </label>
          </div>
        ))
      )}
    </section>
  );
}
