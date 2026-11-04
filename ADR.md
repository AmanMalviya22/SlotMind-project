# Architecture Decision Record — SlotMind

## 1. Where scheduling logic lives

**Context:** The same scheduling behavior must be available to on-demand HTTP requests and automatic recovery workflows. Booking constraints must not depend on which entry point called the engine.

**Options considered:** Put selection logic in REST controllers, embed it in JPA entities, or centralize it behind a service and strategy contract.

**Decision:** Controllers handle HTTP mapping and validation. `SchedulingService` owns the scheduling workflow, eligibility re-checks, suggestion lifecycle, and transactional acceptance. Implementations of `SchedulingStrategy` select candidate slots.

**Tradeoffs:** The extra layers create more classes, but make scheduling rules testable and replaceable without duplicating business logic in the API or event handler.

## 2. AI-first recommendation strategy

**Context:** The application needs an AI-first recommendation flow for both on-demand and asynchronous scheduling, with deterministic rule-based recovery when the gateway or AI response cannot be used.

**Options considered:** Mark the AI bean as primary, select a strategy separately at each call site, or select one strategy centrally during Spring configuration.

**Decision:** `SchedulingStrategyConfiguration` always selects `AISchedulingStrategy` as the primary strategy. It calls the LLM gateway first and delegates to `RuleBasedSchedulingStrategy` when gateway credentials are missing, the request fails, the response is empty or invalid, or the AI recommendation cannot be validated. Deterministic alternatives are also retained if the AI-selected slot becomes unavailable during the transactional recheck. There is no strategy-selection environment variable.

**Tradeoffs:** Configuration is explicit and consistent for every caller. AI mode adds gateway latency and can return no usable result, so AI output is validated and never finalizes a booking directly.

## 3. AI safety, decision contract, and validation

**Context:** An LLM can return an unknown slot, an incorrect provider, malformed JSON, incorrectly typed fields, invalid confidence, a later-than-preferred candidate, or patient-unfriendly reasoning. A syntactically valid answer is not necessarily a valid scheduling decision.

**Options considered:** Trust the LLM response, validate only that the returned slot exists, or enforce the complete scheduling contract against the current candidate set and deterministic business rules.

**Decision:** The gateway sends trigger context, appointment type/urgency, original booking context, eligible provider roster, and eligible candidate slots. The system prompt defines a strict candidate allowlist, trigger-specific selection order, a typed JSON response contract, and concise reasoning constraints. Patient names are not needed for the decision and are not sent to the model.

The gateway rejects malformed JSON and incorrectly typed or missing fields. `AISchedulingStrategy` then verifies that the selected slot is one of the actual candidates, is open and in the future, and belongs to an available provider. It requires `alternateProviderId` to exactly match the selected slot's provider (or be null only for the original provider). It also checks that the model's choice belongs to the highest-priority eligible provider/specialty group and enforces a stable slot-ID tie-break for equal start times. The prompt asks the model to consider original appointment duration and start time within that group, allowing the AI to choose among equally preferred candidates instead of forcing the exact first rule-based result. Urgent appointments prioritize the original specialty and earliest time as an operational scheduling rule, not a clinical assessment. Invalid or unavailable AI output falls back to `RuleBasedSchedulingStrategy`.

The AI recommendation remains advisory. The booking transaction independently locks and revalidates the destination slot before committing a reschedule. The UI describes the displayed confidence as a model estimate, not a calibrated probability.

**Tradeoffs:** Strict validation can reject a potentially useful model response and increase fallback frequency. This is intentional: deterministic rules, not the LLM, define scheduling correctness. Mock HTTP tests verify request construction, response parsing, and fallback behavior. The project owner completed a separate live LLM gateway evaluation; normal CI remains mock-based and does not depend on live credentials. The live integration run is not a substitute for measuring model accuracy on a large labelled production dataset.
## 4. Transactional acceptance and double-booking protection

**Context:** Two coordinators can attempt to accept recommendations that compete for a slot, and one appointment can have more than one pending suggestion across trigger types.

**Options considered:** Rely on UI state, use optimistic version columns only, or lock and revalidate the appointment and relevant slots inside a database transaction.

**Decision:** Acceptance occurs in one `@Transactional` operation. Both the controller and `SchedulingService` reject an acceptance request unless the client explicitly states that the front desk confirmed the proposed time with the patient; the service check protects non-HTTP callers too. The service then locks the appointment before the selected suggestion, locks and refreshes the destination slot and provider, and revalidates current state before booking. It releases the old slot when appropriate, books the replacement slot, updates the appointment, marks the accepted suggestion, and rejects sibling pending suggestions in the same transaction. Suggestion generation also locks and re-checks the selected slot before saving, while a unique pending key prevents duplicate pending suggestions for the same appointment and trigger reason.

**Tradeoffs:** Row locks can briefly reduce throughput, but make conflicts explicit and prevent the UI from being the only guard. Production databases should also be used for concurrent integration tests because in-memory unit tests cannot prove all database race behavior.

## 5. Automatic workflow and event handling

**Context:** Provider-offline events can affect multiple appointments, while slot cancellation affects the appointment associated with one slot. The status endpoint should not wait for the full recovery workflow.

**Options considered:** Run recovery work synchronously inside the status endpoint, duplicate logic in separate callers, or publish a post-commit domain event consumed by an asynchronous workflow handler.

**Decision:** Status changes publish `WorkflowEvent` instances. `WorkflowService` handles them after transaction commit with `@Async`, branches by trigger type, skips duplicate pending suggestions, and delegates to the same `SchedulingService`. AI failures use rule-based fallback.

**Tradeoffs:** The API remains responsive and the logic is shared. The current event mechanism is in-process rather than durable; a process crash can lose an event, so persistent jobs or an outbox/message broker would be a next step for production.

## 6. Front-desk interaction and scope

**Context:** Staff need to review a proposed change and confirm it with the patient before a booking is finalized.

**Decision:** The UI shows provider status, appointments, slot availability, pending recommendations, trigger/source badges, confidence, patient-friendly reasoning, and accept/reject controls. Manual suggestions stream progress through a Server-Sent Events endpoint, while the final recommendation stays pending until a staff member accepts it. The UI also includes a date-filtered full-day schedule, appointment-type color markers, selected-day provider load, and a browser-local waitlist. The waitlist books through the normal appointment API; entries are removed only after a successful booking.

**Tradeoffs:** Polling remains the simple freshness mechanism for shared lists, while SSE provides immediate progress for an individual suggestion request. The browser-local waitlist is a deliberate hackathon UI extension rather than a durable multi-user queue; a production version should store waitlist entries in the backend and expose authorization, audit history, and fairness/priority rules.

## 7. Extensibility and deferred scope

**Context:** The brief identifies room/equipment constraints as a future strategy, while waitlists, notifications, multi-clinic routing, and patient self-service are explicitly beyond this sprint.

**Decision:** Keep the `SchedulingStrategy` contract as the extension point for a future `RoomConstraintStrategy`. Defer waitlists, billing, notifications, patient portals, and durable distributed workflow infrastructure.

**Tradeoffs:** The current scope protects the core re-scheduling loop and the five-minute demo path. It is a hackathon implementation, not a production clinic system; persistent storage, authentication/authorization, operational monitoring, and durable retries are still required before real use.
