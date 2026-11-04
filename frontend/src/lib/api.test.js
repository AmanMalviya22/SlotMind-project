import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { API, request } from "./api.js";

describe("API request client", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("uses the configured API base and sends JSON content type by default", async () => {
    const payload = [{ id: "P1" }];
    fetch.mockResolvedValue({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValue(payload),
    });

    await expect(request("/providers")).resolves.toEqual(payload);

    expect(API).toBe("http://localhost:8080/api");
    expect(fetch).toHaveBeenCalledWith(`${API}/providers`, {
      headers: { "Content-Type": "application/json" },
    });
  });

  it("preserves request options and merges caller headers", async () => {
    fetch.mockResolvedValue({
      ok: true,
      status: 200,
      json: vi.fn().mockResolvedValue({ updated: true }),
    });

    const body = JSON.stringify({ status: "OFFLINE" });
    await request("/providers/P1/status", {
      method: "PATCH",
      headers: { Accept: "application/json" },
      body,
    });

    expect(fetch).toHaveBeenCalledWith(`${API}/providers/P1/status`, {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body,
    });
  });

  it("returns null for a no-content response without parsing JSON", async () => {
    const json = vi.fn();
    fetch.mockResolvedValue({ ok: true, status: 204, json });

    await expect(request("/suggestions/S1", { method: "PATCH" })).resolves.toBeNull();
    expect(json).not.toHaveBeenCalled();
  });

  it("uses the backend error message for a JSON error response", async () => {
    fetch.mockResolvedValue({
      ok: false,
      status: 409,
      json: vi.fn().mockResolvedValue({ error: "Slot is no longer available." }),
    });

    await expect(request("/suggestions/S1")).rejects.toThrow(
      "Slot is no longer available.",
    );
  });

  it("falls back to the HTTP status when the error response is not JSON", async () => {
    fetch.mockResolvedValue({
      ok: false,
      status: 503,
      json: vi.fn().mockRejectedValue(new Error("Invalid JSON")),
    });

    await expect(request("/providers")).rejects.toThrow("Request failed (503)");
  });

  it("propagates network errors to the caller", async () => {
    fetch.mockRejectedValue(new TypeError("Network unavailable"));

    await expect(request("/providers")).rejects.toThrow("Network unavailable");
  });
});
