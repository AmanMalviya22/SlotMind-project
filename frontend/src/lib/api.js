const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL;
const API_BASE_URL =
  configuredApiBaseUrl === undefined
    ? "http://localhost:8080"
    : configuredApiBaseUrl.replace(/\/+$/, "");

export const API = `${API_BASE_URL}/api`;

export async function request(path, options = {}) {
  const response = await fetch(`${API}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    },
  });

  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const body = await response.json();
      message = body.error || message;
    } catch {
      // Keep the HTTP status message when the response is not JSON.
    }
    throw new Error(message);
  }

  if (response.status === 204) return null;
  return response.json();
}
