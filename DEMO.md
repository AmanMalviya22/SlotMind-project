# SlotMind — Five-Minute Hackathon Demo

This walkthrough follows the core rubric path: book an appointment → trigger a provider-offline workflow → inspect a recommendation → confirm with the patient → accept and verify the slot change. The application always tries the AI strategy first; configure a valid, rotated gateway credential to demonstrate a live AI recommendation. If the gateway is unavailable or its response is invalid, the application automatically uses the rule-based fallback. The project owner completed live LLM evaluation against the configured gateway; that run was separate from CI's mock-based tests.

## Before the demo

Open two terminals from the repository root.

**Terminal 1 — backend**

```powershell
cd backend
mvn spring-boot:run
```

**Terminal 2 — frontend**

```powershell
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173`. The backend seeds demo providers and slots into an in-memory H2 database at startup.

## Live walkthrough

1. **Show the dashboard (30 seconds).** Point out provider status, confirmed appointments, open slots, and the five-second refresh. The strategy is always AI-first; confirm that the gateway environment variables are configured before demonstrating a live AI recommendation.
2. **Book a new appointment (45 seconds).** Enter a demo patient name, choose a type, select a provider with a future open slot, and book it. Explain that the backend checks provider/slot ownership, future time, status, and slot availability before booking.
3. **Trigger the recovery loop (45 seconds).** In Provider status, set the booked appointment's provider to `OFFLINE`. The status request commits before asynchronous recovery begins. The workflow examines all confirmed appointments assigned to that provider; the UI queue refreshes automatically.
4. **Inspect the recommendation (60 seconds).** Show the trigger badge, AI/rule source, original provider and time, proposed provider and time, confidence, and staff-friendly reasoning. If a recommendation is unavailable, show the explicit “No replacement yet” state rather than implying that recovery succeeded.
5. **Demonstrate the human checkpoint (45 seconds).** Explain that the front desk must contact the patient first. Tick “I confirmed the proposed time with the patient,” then accept. Without that confirmation the UI disables Accept and the backend rejects an acceptance request that omits the confirmation flag.
6. **Verify the atomic result (45 seconds).** The appointment should show `RESCHEDULED` with the new provider/time; the old slot should be open (unless cancelled), the new slot should be booked, and sibling pending recommendations should be resolved. This is the central correctness claim.
7. **Show extension features (45 seconds).** Change the date in Full-day schedule, point out the routine/urgent/follow-up color markers and provider-load bars, add a waitlist entry, and try “Book next open slot.” The entry is removed only after a real appointment API call succeeds. Waitlist entries are local to this browser in the hackathon build.

## Live AI verification status

The live LLM evaluation has been executed by the project owner. Normal CI remains mock-based by design and does not call the external gateway. For the evaluation scenarios and repeatable command, see [AI_EVALUATION.md](AI_EVALUATION.md).

## Optional SSE bonus

Use **Suggest** on a confirmed or pending appointment. The browser calls `POST /api/appointments/{id}/suggest/stream`, displays progress events, then refreshes the queue after the validated suggestion arrives. The suggestion still requires the patient-confirmation checkpoint and human acceptance.

## Troubleshooting during the demo

- **No recommendation appears:** verify there are future `OPEN` slots with providers who are not `OFFLINE`. Provider-offline recovery cannot suggest another slot from the offline provider.
- **“No replacement yet” appears:** create a new future slot for an available provider using the recovery form, then retry the streamed suggestion. Cancelled slots remain immutable.
- **AI is slow or unavailable:** use the rule-based strategy. AI mode validates IDs, availability, provider eligibility, confidence, and reasoning, then falls back to deterministic rules if the gateway response is unusable.
- **Data looks different after restart:** the seeded H2 database is in-memory, so it resets when the backend restarts.
- **Waitlist entry disappears after a browser reset:** waitlist entries are saved in browser local storage, not in the backend database; a durable multi-user waitlist is future scope.

## What to say about production readiness

The core booking decision is transactional and revalidates the destination slot under database locks. The AI suggestion is advisory only. For production, the in-memory database, in-process async event delivery, browser-local waitlist, authentication/authorization, audit history, and durable retries need production-grade implementations.
