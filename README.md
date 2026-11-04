# SlotMind

SlotMind is a Spring Boot + React front-desk application for **AI-first appointment rescheduling**. It creates human-reviewable recommendations when a provider goes offline or an appointment slot is cancelled.

## Requirements

- Java 21
- Maven 3.9+
- Node.js 22.22.2+ and npm

The backend and frontend run as separate processes. A Maven Wrapper is not currently included.

## Run locally (Windows PowerShell)

From the repository root, open two terminals.

**Terminal 1 — backend**

Configure the LLM gateway in the same PowerShell session used to start the backend:

```powershell
cd backend
$env:SLOTMIND_LLM_API_KEY = "YOUR_ROTATED_TOKEN"
$env:SLOTMIND_LLM_PRODUCT = "PC1"
$env:SLOTMIND_LLM_BASE_URL = "https://litellm-qc.zycus.net/v1"
$env:SLOTMIND_LLM_MODEL = "qwen-cursor"
# Only if the gateway owner confirms it is required:
# $env:SLOTMIND_LLM_COOKIE = "ROTATED_COOKIE_VALUE"

mvn spring-boot:run
```

**Terminal 2 — frontend**

```powershell
cd frontend
npm ci
npm run dev
```

Open `http://localhost:5173`. The frontend reads `VITE_API_BASE_URL` from `frontend/.env`; the default backend URL is `http://localhost:8080`.

The backend uses an in-memory H2 database and seeds demo records at startup. Set provider `PRV-001` to `OFFLINE`; the workflow should create suggestions for both affected appointments. Accepting a suggestion books the replacement slot.

The backend always enters the AI scheduling strategy first. Configure `SLOTMIND_LLM_API_KEY` to receive a live AI recommendation. When the key is missing, the gateway is unavailable, or an AI response cannot be used, the application automatically produces the recommendation with the deterministic rule-based fallback. The application does not require a strategy-selection environment variable.

## Architecture

- **AI-first recommendation flow:** every scheduling request enters `AISchedulingStrategy` first. It calls the configured LLM gateway, validates the response against the eligible candidate list and scheduling rules, and places a valid AI recommendation first.
- **Automatic rule-based fallback:** `RuleBasedSchedulingStrategy` runs when gateway credentials are missing, the gateway request fails, the response is empty or invalid, or the selected AI recommendation does not pass validation. Rule-based alternatives are also kept behind a valid AI recommendation so the service can try the next eligible candidate if the AI-selected slot becomes unavailable during the database-lock recheck.
- **AI safeguards:** AI output must identify a real eligible candidate, use the correct provider ID, stay within the confidence and reasoning contract, and obey the provider/specialty priority rules. The model cannot create slot IDs or override backend eligibility checks.
- **REST API:** endpoints for providers, appointments, slots, and rescheduling suggestions.
- **Service and repository layers:** business operations are separated from HTTP request handling and persistence.
- **Workflow events:** provider and slot changes dispatch post-commit events to an asynchronous workflow listener.
- **Concurrency protection:** appointment and slot updates use database locks; a slot is rechecked before a pending suggestion is saved and before a suggestion is accepted.
- **Front-desk UI:** shows providers, appointments, slot availability, recommendation source and trigger, original/proposed times, model-estimated confidence (not calibrated) when available, specialty-change warnings, reasoning, accept/reject controls, an explicit patient-confirmation checkbox required by both UI and API, a live SSE suggestion flow, date-filtered full-day schedule, appointment-type colors, provider-load indicators, and a browser-local waitlist that books through the real appointment API.

For the AI decision contract and evaluation scenarios, see [AI_EVALUATION.md](AI_EVALUATION.md). For a source-code map, class responsibilities, and end-to-end implementation flows, see [CODE_DOCUMENTATION.md](CODE_DOCUMENTATION.md).

## API

- `GET /api/providers`
- `GET /api/appointments`
- `GET /api/slots`
- `GET /api/suggestions`
- `POST /api/appointments`
- `POST /api/slots` (create a future OPEN slot for a non-offline provider during recovery)
- `PATCH /api/providers/{id}/status`
- `PATCH /api/slots/{id}/status`
- `POST /api/appointments/{id}/suggest`
- `POST /api/appointments/{id}/suggest/stream` (SSE progress + validated suggestion)
- `PATCH /api/suggestions/{id}`

Supported list filters: `GET /api/appointments?status=CONFIRMED` and `GET /api/suggestions?status=PENDING`.

## AI gateway configuration

The AI strategy is the primary recommendation path. Set these environment variables before starting the backend:

```powershell
$env:SLOTMIND_LLM_API_KEY = "YOUR_ROTATED_TOKEN"
$env:SLOTMIND_LLM_PRODUCT = "PC1"
$env:SLOTMIND_LLM_BASE_URL = "https://litellm-qc.zycus.net/v1"
$env:SLOTMIND_LLM_MODEL = "qwen-cursor"
# Only if the gateway owner confirms it is required:
# $env:SLOTMIND_LLM_COOKIE = "ROTATED_COOKIE_VALUE"

cd backend
mvn spring-boot:run
```

If the gateway cannot return a valid recommendation, the same request continues through the rule-based strategy. The backend remains able to produce recommendations when the gateway key is not configured, because the AI strategy delegates to the deterministic fallback.

Never place credentials in Java source, application properties, frontend variables, Git, or logs. Any credential previously shared in chat or committed to Git must be rotated.

**Live gateway verification — completed previously:** the project owner has executed the live LLM evaluation against the configured gateway. This was a real gateway run outside the automated test suite. Normal CI uses a local mock HTTP server and does not call the live LLM service. The five-scenario evaluation scope and repeatable command are documented in [AI_EVALUATION.md](AI_EVALUATION.md). Do not store the token, cookie, patient data, or full authorization headers in the repository.

## Hackathon demo

Follow [`DEMO.md`](DEMO.md) for the five-minute rubric-aligned walkthrough, including the human-confirmation checkpoint, atomic acceptance verification, SSE bonus, UI ceiling features, and troubleshooting.

## Live LLM integration test

The project owner previously completed live LLM evaluation using the configured gateway. The normal CI suite remains mock-based and never calls the live model. To execute the live decision test again, first configure the gateway environment variables from the section above, then enable the test explicitly:

```powershell
$env:SLOTMIND_LLM_LIVE_TESTS = "true"
cd backend
mvn -Dtest=LiteLLMGatewayLiveTest test
```

This test verifies that a real model response passes the backend contract and follows the provider-offline specialty priority. It can incur gateway latency or usage charges. Keep the credential in the process environment and never commit it. Remove the test flag when the test is complete.

## Verification

Run backend tests and generate the JaCoCo HTML/XML coverage report:

```powershell
cd backend
mvn verify
```

The local coverage report is written to `backend/target/site/jacoco/index.html`. GitHub Actions uploads the report as the `backend-jacoco-report` artifact after a successful backend verification. Backend verification also enforces minimum overall coverage of 90% for lines and 75% for branches to prevent regressions.

Run frontend tests and build:

```powershell
cd frontend
npm ci
npm test
npm run build
```

GitHub Actions runs backend tests, frontend tests, and a production frontend build on pushes to `main` or a `quality/**` branch and on pull requests to `main`.

## Known limitations

- The H2 database is in-memory and resets when the backend restarts. Cancelled slots are immutable; unresolved appointments can recover by creating a new future slot for an available provider.
- Automated LLM gateway tests use a local mock HTTP server. They verify request construction, response handling, and fallback behavior; they do not verify live service availability or model quality.
- Workflow events are handled asynchronously in-process, not through a durable message broker; a process crash can lose an in-flight event.
- Production use still requires persistent storage, authentication and authorization, monitoring, and operational retry/reconciliation.

## Front-desk UI extensions

- Use **Full-day schedule** to inspect slots and linked appointments by date. Previous/next controls and the date picker change the displayed day.
- **Provider load** summarizes booked slots and active appointments for the chosen day.
- Appointment types use separate visual markers for routine, urgent, and follow-up visits.
- **Waitlist** entries are stored in the current browser's local storage, not the backend database. "Book next open slot" creates a real appointment through `POST /api/appointments`; the waitlist entry is removed only after that booking succeeds.
- Manual **Suggest** actions use the SSE endpoint to stream progress and the validated recommendation. The final recommendation remains pending until a staff member confirms they have checked the proposed time with the patient and accepts it; the API rejects acceptance without that confirmation flag.
- If an automatic workflow cannot find an eligible slot, the appointment remains visible in the recovery queue as **No replacement yet** so staff can create a new future slot for an available provider and retry rather than losing the exception in the UI.
