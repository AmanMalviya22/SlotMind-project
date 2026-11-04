# SlotMind Backend Flow — Interview Guide

For the current source tree, class responsibilities, and frontend code map, see [CODE_DOCUMENTATION.md](CODE_DOCUMENTATION.md).

## 1. One-minute explanation

SlotMind is a Spring Boot backend for appointment re-scheduling when a provider becomes unavailable or an appointment slot is cancelled. It does not allow an AI model to directly change a booking. Instead, it:

1. Detects a provider or slot event.
2. Finds the affected confirmed appointment or appointments.
3. Builds a safe list of future, open candidate slots.
4. Runs `AISchedulingStrategy`, which calls the configured LiteLLM-compatible gateway first.
5. Independently validates any AI response against the real database candidates and scheduling rules.
6. Uses `RuleBasedSchedulingStrategy` automatically if gateway credentials are missing, the gateway fails, or the AI response is empty or invalid; deterministic alternatives are also retained for the transactional availability recheck.
7. Stores a pending suggestion for a human coordinator.
8. Atomically books the replacement only after the coordinator accepts it.

The important design principle is **AI-assisted, but application-controlled**. AI can recommend; the backend validates and the human accepts.

---

## 2. High-level architecture

```text
React frontend
      |
      | HTTP GET/POST/PATCH /api/...
      v
Controllers
      |
      v
Application services
  - AppointmentService
  - ProviderService
  - SlotService
  - WorkflowService (@Async)
  - SchedulingService (@Transactional)
      |
      +--> SchedulingStrategyConfiguration
              +--> RuleBasedSchedulingStrategy (default)
              +--> AISchedulingStrategy (AI mode)
                      +--> LiteLLMGateway --> external LLM
                      +--> RuleBasedSchedulingStrategy fallback
      |
      +--> Spring Data JPA repositories
              |
              v
        H2 database
```

### Main responsibilities

| Layer | Responsibility |
|---|---|
| Controller | Defines the REST API, validates request DTOs, parses status/action values, and converts entities to response DTOs. |
| Service | Owns business rules and transaction boundaries. |
| WorkflowService | Reacts to provider/slot events asynchronously and isolates failures per appointment. |
| SchedulingService | Builds candidates, prevents duplicate pending suggestions, creates suggestions, and performs acceptance transactions. |
| AISchedulingStrategy | Calls AI, validates its answer, labels valid results as `AI`, and delegates failures to the fallback. |
| RuleBasedSchedulingStrategy | Provides deterministic, explainable scheduling when AI cannot be used. |
| LiteLLMGateway | Converts the domain request to an OpenAI-compatible `/chat/completions` call and parses the response. |
| Repository | Reads/writes entities and supplies pessimistic locks for critical operations. |
| MappingService | Prevents JPA entities from being returned directly and creates stable API response shapes. |

The application starts with Spring Boot and `@EnableAsync`. CORS is restricted to the local frontend origin `http://localhost:5173`.

---

## 3. Domain model and state values

### Entities

#### Provider

Represents a clinician. It has an ID, display name, specialty, status, and JPA optimistic-lock version.

Provider statuses:

- `AVAILABLE`
- `BUSY`
- `OFFLINE`

An offline provider cannot be selected as the destination of a recommendation.

#### Slot

Represents an appointment time owned by one provider. It has an ID, provider, start time, duration, status, and version.

Slot statuses:

- `OPEN` — eligible for consideration if it is also in the future.
- `BOOKED` — already allocated.
- `CANCELLED` — permanently unavailable through the status API.

#### Appointment

Connects a patient, appointment type, provider, and booked slot. Its statuses are:

- `CONFIRMED` — normal active booking.
- `RESCHEDULE_PENDING` — an automatic workflow has detected a problem and is seeking a replacement.
- `RESCHEDULED` — a replacement was accepted and booked.
- `COMPLETED` — finalized appointment; no new suggestion is generated.

#### RescheduleSuggestion

Stores a human-reviewable recommendation, including:

- the affected appointment;
- the recommended slot and alternate provider;
- the original provider and original time;
- confidence and reasoning;
- `source` (`AI` or `RULE_BASED`);
- trigger reason;
- status and creation time.

Suggestion statuses:

- `PENDING`
- `ACCEPTED`
- `REJECTED`

### Trigger reasons

- `INITIAL` — a coordinator manually requested a suggestion.
- `PROVIDER_OFFLINE` — the appointment’s provider became offline.
- `SLOT_CANCELLED` — the appointment’s slot was cancelled.

---

## 4. REST API surface

All endpoints are under `/api`.

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/providers` | List providers. |
| `PATCH` | `/providers/{id}/status` | Change provider status. Setting `OFFLINE` starts an automatic workflow. |
| `GET` | `/slots` | List slots. |
| `POST` | `/slots` | Create a future OPEN slot for an available provider during recovery. |
| `PATCH` | `/slots/{id}/status` | Change slot status. Setting `CANCELLED` starts an automatic workflow. |
| `GET` | `/appointments` | List appointments, optionally filtered by status. |
| `POST` | `/appointments` | Create and book a new appointment. |
| `POST` | `/appointments/{id}/suggest` | Manually generate one suggestion with trigger `INITIAL`. |
| `POST` | `/appointments/{id}/suggest/stream` | Stream progress and the validated suggestion as SSE. |
| `GET` | `/suggestions` | List suggestions, optionally filtered by status. |
| `PATCH` | `/suggestions/{id}` | Accept or reject a pending suggestion. |

### Example request bodies

Set a provider offline:

```json
{ "status": "OFFLINE" }
```

Create an appointment:

```json
{
  "patientName": "John Martinez",
  "type": "ROUTINE",
  "providerId": "PRV-001",
  "slotId": "SLT-001"
}
```

Accept a suggestion:

```json
{ "action": "ACCEPT", "patientConfirmed": true }
```

The controller and `SchedulingService` both reject `ACCEPT` unless `patientConfirmed` is `true`, so direct service callers cannot bypass the checkpoint. Rejection does not require this field.

The suggestion response exposes `source`, so the UI can show whether the recommendation came from `AI` or `RULE_BASED`.

---

## 5. Complete automatic provider-offline flow

This is the most important end-to-end flow to explain in an interview.

### Step 1: HTTP request enters the controller

The frontend sends:

```text
PATCH /api/providers/PRV-001/status
Body: { "status": "OFFLINE" }
```

`ProviderController.providerStatus(...)` parses the text into `ProviderStatus.OFFLINE` and calls `ProviderService.status(...)`.

### Step 2: ProviderService persists the event

`ProviderService`:

1. Loads the provider or returns `404` if it does not exist.
2. Updates the status.
3. Saves the provider.
4. Publishes a `WorkflowEvent` with reason `PROVIDER_OFFLINE` and the provider ID. The event is handled after the transaction commits.

The status change is returned quickly to the frontend. The expensive recommendation work is asynchronous.

### Step 3: WorkflowService finds affected appointments

`WorkflowService.onWorkflowEvent(...)` is an asynchronous, after-commit event listener. It branches on the event reason, then its provider-offline handler queries only appointments matching:

```text
provider = PRV-001 AND status = CONFIRMED
```

For every result it calls:

```text
scheduling.processAutomatic(appointmentId, PROVIDER_OFFLINE)
```

Each appointment is processed through `processSafely(...)` with its own `try/catch`. Therefore, if one appointment fails because of a database, gateway, or validation problem, the exception is logged and processing continues for the other appointments.

This is the per-appointment fallback/isolation behavior: **one failure does not abort the batch**.

### Step 4: SchedulingService marks the appointment pending

`processAutomatic(...)` obtains a pessimistic lock on the appointment. For a provider-offline event, it also refreshes and locks the provider and continues only if the provider is still `OFFLINE`; a delayed event is ignored if the provider has returned online. It then changes a still-confirmed appointment to `RESCHEDULE_PENDING` and saves it.

It then calls `generate(...)` with `TriggerReason.PROVIDER_OFFLINE`.

### Step 5: SchedulingService prevents duplicate work

`generate(...)` performs these checks:

1. Locks and reloads the appointment.
2. Stops if the appointment is already `RESCHEDULED` or `COMPLETED`.
3. Stops if a pending suggestion already exists for the same appointment and trigger reason.

This makes repeated events and repeated requests idempotent at the suggestion level.

### Step 6: Candidate slots are built from the database

The repository initially selects slots that are:

- `OPEN`;
- after the current time;
- sorted by start time.

The service then removes slots already used by other pending suggestions. This reduces the chance that several appointments receive the same proposed destination.

The candidate list is passed to the strategy. In AI mode, the model receives those candidate IDs; the application rejects a response if its selected ID is not in the candidate list.

### Step 7: AI-first recommendation path

`SchedulingStrategyConfiguration` always provides `AISchedulingStrategy` as the primary `SchedulingStrategy`. It calls the configured gateway first and retains rule-based alternatives. There is no runtime switch to make rule-based scheduling the primary strategy.

`AISchedulingStrategy` calls `LLMGateway.recommend(...)`. `LiteLLMGateway`:

1. Returns empty immediately when no API key is configured or there are no candidates.
2. Builds an OpenAI-compatible chat-completions request.
3. Sends the configured model, system message, trigger, appointment context, and candidate list.
4. Uses `POST /chat/completions` with a bearer token and the configured `product` header.
5. Reads JSON from `choices[0].message.content`.
6. Removes optional Markdown code fences.
7. Parses the content into a typed recommendation.

The expected content is:

```json
{
  "slotId": "SLT-005",
  "alternateProviderId": "PRV-004",
  "confidence": 0.9,
  "reasoning": "Found a suitable general practitioner slot with same specialty as original provider"
}
```

Credentials are loaded from environment variables and are not exposed to the frontend or written into logs.

### Step 8: AI response is validated independently

The backend never trusts the AI response merely because it is valid JSON. `AISchedulingStrategy.validate(...)` checks:

- `slotId` exists and is not blank;
- confidence is finite and between `0` and `1`;
- reasoning exists, is not blank, and is at most 1000 characters;
- the slot ID exactly matches one of the supplied database candidates;
- the selected slot is still `OPEN`;
- the selected slot is still in the future;
- the destination provider is not `OFFLINE`;
- for `PROVIDER_OFFLINE`, the destination is not the original provider;
- if `alternateProviderId` is supplied, it matches the selected slot’s actual provider.

Only after all checks pass does the service create a recommendation with `source = "AI"`.

This protects against hallucinated IDs, stale slots, invalid confidence values, wrong providers, and recommendations that violate the trigger-specific business rule.

### Step 9: Rule-based fallback

The fallback is used when:

- the API key is absent;
- there are no candidates;
- the gateway times out or returns an error;
- the response shape is invalid;
- the response contains malformed JSON;
- AI selects an unknown or ineligible slot;
- AI throws any runtime failure during recommendation.

`RuleBasedSchedulingStrategy`:

1. Excludes non-open and offline-provider slots.
2. Excludes the original provider for a provider-offline trigger.
3. For routine and follow-up appointments, prefers the same provider when allowed, then matching specialty, then another eligible provider.
4. For urgent appointments, prioritizes the original specialty and earliest available time. This is an operational scheduling priority, not a medical assessment.
5. Uses the earliest available slot within the selected preference group.

It returns a deterministic explanation and `source = "RULE_BASED"`. This means the workflow still produces a useful recommendation even when the external AI dependency is down.

### Step 10: Suggestion is persisted, not booked

`SchedulingService.generate(...)` performs a final safety check on the selected slot, creates a `RescheduleSuggestion` with status `PENDING`, and saves it.

At this point:

- the appointment is `RESCHEDULE_PENDING`;
- the destination slot remains `OPEN`;
- the suggestion contains the source badge data;
- a coordinator must review the suggestion.

The AI has not changed the appointment.

---

## 6. Complete manual suggestion flow

The coordinator can request a suggestion directly:

```text
POST /api/appointments/{appointmentId}/suggest
```

The controller loads the appointment and calls:

```java
gs.generate(appointment, TriggerReason.INITIAL)
```

The same candidate filtering, AI validation, fallback, duplicate prevention, and pending-suggestion persistence are used. If no slot is available or a pending `INITIAL` suggestion already exists, the API returns a conflict instead of creating a duplicate.

This reuse is intentional: automatic and manual workflows go through the same scheduling service and cannot silently diverge in business logic.

---

## 7. Complete slot-cancelled flow

The frontend sends:

```text
PATCH /api/slots/{slotId}/status
Body: { "status": "CANCELLED" }
```

`SlotService` validates the transition, saves the cancelled slot, and publishes a `WorkflowEvent` with reason `SLOT_CANCELLED`. The asynchronous listener handles it after the transaction commits.

The worker:

1. Loads the cancelled slot.
2. Finds its appointment.
3. Continues only if the appointment is `CONFIRMED`.
4. Calls `processSafely(appointmentId, SLOT_CANCELLED, ...)`.

The downstream scheduling flow is then identical. The difference is strategy preference: for a cancelled slot, the rule-based fallback first tries to preserve the same provider; if that is not possible, it uses specialty and then earliest availability.

---

## 8. Suggestion decision and atomic acceptance

The decision endpoint is:

```text
PATCH /api/suggestions/{suggestionId}
Body: { "action": "ACCEPT" }
```

`SchedulingService.decide(...)` is transactional and validates `ACCEPT` or `REJECT`.

### Reject path

1. Pessimistically lock the suggestion.
2. Lock the associated appointment.
3. Reload the suggestion under lock.
4. Require status `PENDING`.
5. Set suggestion status to `REJECTED`.
6. Save it.

The appointment and slots are unchanged.

### Accept path

1. Lock the suggestion.
2. Lock the appointment.
3. Reload the suggestion.
4. Require suggestion status `PENDING`.
5. Reject if the appointment is already `RESCHEDULED` or `COMPLETED`.
6. Pessimistically lock the destination slot.
7. Revalidate destination slot status, future time, and provider status.
8. Release the old slot unless it was cancelled. Cancelled slots are immutable; unresolved appointments recover by creating a new future slot for an available provider through `POST /api/slots`.
9. Mark the destination slot `BOOKED`.
10. Attach the destination slot and provider to the appointment.
11. Set appointment status to `RESCHEDULED`.
12. Set the selected suggestion to `ACCEPTED`.
13. Reject other pending suggestions for the same appointment.
14. Commit the transaction.

This protects against two coordinators accepting the same suggestion or two suggestions competing for one slot. Pessimistic locks serialize the critical rows, while `@Version` fields provide optimistic version tracking on entities.

If the destination became unavailable after recommendation creation, acceptance returns a conflict such as `Recommended slot is no longer available` rather than overwriting current data.

---

## 9. New appointment flow

`POST /api/appointments` is handled by `AppointmentService.create(...)` inside a transaction.

The service:

1. Loads the requested provider.
2. Pessimistically locks the requested slot.
3. Requires the slot to be `OPEN`.
4. Requires the slot to belong to the requested provider.
5. Requires the provider not to be `OFFLINE`.
6. Marks the slot `BOOKED`.
7. Creates a `CONFIRMED` appointment.

This prevents a normal appointment creation request from booking a slot that is already taken or belongs to a different provider.

---

## 10. Error handling

`ApiExceptionHandler` maps common failures to predictable JSON:

- `404` for domain resources that do not exist;
- `409` for business conflicts, such as an unavailable slot or resolved suggestion;
- `400` for validation errors, invalid enum/status values, and invalid actions.

The asynchronous workflow does not send an error response because the original status request has already completed. Instead, it logs the appointment-specific failure and leaves that appointment available for investigation or another workflow attempt.

Logs intentionally record non-sensitive diagnostic information such as the exception class and workflow identifiers, not API credentials or full sensitive payloads.

---

## 11. Configuration and external AI dependency

Optional environment variables:

```powershell
$env:SLOTMIND_LLM_API_KEY = "..."
$env:SLOTMIND_LLM_PRODUCT = "PC1"
$env:SLOTMIND_LLM_BASE_URL = "https://litellm-qc.zycus.net/v1"
$env:SLOTMIND_LLM_MODEL = "qwen-cursor"
$env:SLOTMIND_LLM_COOKIE = "..." # only if required by the gateway
```

The gateway uses a 3-second connection timeout and an 8-second read timeout. Any failure is converted into an empty recommendation and handled by the deterministic fallback.

The backend uses H2 for the demo environment and seeds providers, slots, and appointments at startup when the database is empty. Production would normally replace H2 with a durable database and add authentication, authorization, audit persistence, notifications, and retry/dead-letter handling.

---

## 12. Why this design is safe

### AI is bounded

The model receives a finite candidate list and must return an exact candidate ID. The application verifies every important property again.

### AI failure is non-fatal

The application has a deterministic strategy for missing credentials, timeout, malformed response, invalid IDs, or any runtime exception.

### Automatic processing is isolated

Every affected appointment is processed inside its own guarded call. One bad appointment does not prevent other affected appointments from receiving recommendations.

### Booking is human-in-the-loop

Recommendation creation and booking are separate operations. A suggestion is only a proposal until a coordinator accepts it.

### Booking is concurrency-safe

Suggestion, appointment, and destination slot rows are locked during acceptance. State is rechecked immediately before committing.

### Source is auditable in the API

The persisted `source` field is mapped into `SuggestionView`, allowing the frontend and interviewer to distinguish `AI` from `RULE_BASED` recommendations.

---

## 13. Testing and verification

The backend tests cover the strategy boundary:

- valid AI output is accepted and marked `AI`;
- trigger-specific prompt data is included;
- an unknown AI slot is rejected and falls back to `RULE_BASED`;
- rule-based scheduling excludes cancelled/offline destinations and prefers matching specialty.

**Live LLM evaluation status:** the project owner has completed a live evaluation against the configured gateway. This run is separate from CI, where the gateway contract is tested with a local mock server. See [AI_EVALUATION.md](AI_EVALUATION.md) for the five-scenario scope and rerun instructions.

Recommended verification commands:

```powershell
cd backend
mvn test
```

Manual acceptance test:

1. Start the backend.
2. Set `PRV-001` to `OFFLINE`.
3. Wait for the asynchronous workflow.
4. Query `GET /api/suggestions`.
5. Confirm affected appointments have independent pending suggestions.
6. Confirm each suggestion has `source: "AI"` when the gateway returns a valid answer, otherwise `source: "RULE_BASED"`.
7. Accept one suggestion.
8. Verify the appointment is `RESCHEDULED`, the destination is `BOOKED`, the old slot is reopened unless cancelled, and competing pending suggestions are rejected.

---

## 14. Interview questions and concise answers

### Why not let the AI book the slot directly?

Because an LLM is probabilistic and can return stale or invented data. It only recommends a known candidate; the backend validates it, a human reviews it, and a transaction performs the booking.

### How do you handle an AI outage?

The gateway returns an empty result or throws. `AISchedulingStrategy` catches that and calls `RuleBasedSchedulingStrategy`, so the core workflow remains available.

### How do you prevent hallucinated slot IDs?

The model receives candidate IDs, and validation requires the returned ID to exactly match one of the database candidate objects.

### How do you prevent one provider event from failing the entire batch?

`WorkflowService` processes each appointment through `processSafely(...)`, with an independent `try/catch` and appointment-specific logging.

### How do you prevent double booking?

Appointment creation and suggestion acceptance use transactions and pessimistic row locks. Acceptance rechecks the destination immediately before changing it to `BOOKED`.

### How does the UI know whether AI was used?

The recommendation source is persisted on `RescheduleSuggestion` and returned by `SuggestionView.source` as `AI` or `RULE_BASED`.

### Why use a strategy interface?

It separates orchestration from recommendation policy. The same scheduling service can use the configured rule-based or AI policy without changing controller or booking code.

### What would you improve for production?

Use a durable database, authentication/authorization, structured audit events, notification delivery, retry and dead-letter handling for async workflows, metrics/tracing, an explicit executor configuration, and stronger integration tests for concurrent acceptance and provider-event races.

---

## 15. Key source files

| File | Role |
|---|---|
| `backend/src/main/java/com/slotmind/controller/AppointmentController.java` | Appointment creation, listing, and manual suggestion endpoints, including SSE. |
| `backend/src/main/java/com/slotmind/controller/ProviderController.java` | Provider listing and status changes. |
| `backend/src/main/java/com/slotmind/controller/SlotController.java` | Slot listing and status changes. |
| `backend/src/main/java/com/slotmind/controller/SuggestionController.java` | Suggestion listing and accept/reject actions. |
| `backend/src/main/java/com/slotmind/service/WorkflowService.java` | Asynchronous provider/slot event processing. |
| `backend/src/main/java/com/slotmind/service/SchedulingService.java` | Candidate generation, suggestion persistence, and acceptance. |
| `backend/src/main/java/com/slotmind/config/SchedulingStrategyConfiguration.java` | Runtime strategy selection. |
| `backend/src/main/java/com/slotmind/strategy/AISchedulingStrategy.java` | AI validation and fallback selection. |
| `backend/src/main/java/com/slotmind/strategy/LiteLLMGateway.java` | External LLM request/response integration. |
| `backend/src/main/java/com/slotmind/strategy/RuleBasedSchedulingStrategy.java` | Deterministic fallback rules. |
| `backend/src/main/java/com/slotmind/repository/*.java` | Database queries and locks. |
| `backend/src/main/java/com/slotmind/entity/*.java` | JPA domain entities. |
| `backend/src/main/java/com/slotmind/dto/*.java` | Request and response contracts. |

## Final summary

The backend is an event-driven, AI-first rescheduling system. Provider and slot status changes trigger asynchronous, per-appointment processing. The AI strategy calls the gateway first, validates its recommendation against current database candidates, and automatically falls back to deterministic rules when the gateway or response cannot be used. The result is stored as a reviewable suggestion, while acceptance is a separate, locked transaction that safely updates the appointment and slot state.
