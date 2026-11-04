# AI Integration and Evaluation

## Purpose

SlotMind uses the language model as an advisory scheduling component. The model proposes one candidate and a short explanation. The backend remains responsible for deciding whether that proposal is valid. A model response never books or reschedules an appointment by itself.

When the gateway is unavailable, credentials are missing, the response cannot be parsed, or a proposal violates scheduling rules, SlotMind uses the deterministic rule-based strategy.

## Decision contract

The model receives the trigger, appointment context, original provider context, eligible provider roster, and eligible candidate slots.

The candidate list is an allowlist. The model must return one exact `slotId` from that list and the matching `alternateProviderId`. It must also return a numeric confidence value from 0.01 to 1.0 and concise reasoning. Context is serialized as escaped JSON, and names or IDs are treated as untrusted data rather than instructions.

The backend independently checks:
- The selected slot is present in the supplied candidate list.
- The slot is still open and in the future.
- The selected provider is not offline.
- Provider-offline recovery never selects the offline original provider.
- The selected provider ID matches the reported alternate provider ID. The alternate provider ID is null only when the original provider is selected.
- The selected candidate belongs to the highest-priority provider/specialty group defined by the deterministic policy.
- Within that group, the model may use original appointment duration and start time to choose a candidate; equal-time choices must use the lexicographically smaller slot ID.
- Confidence is in range and reasoning is present, concise, and does not contain a clock time or timezone.

The caller must still revalidate the selected slot inside the booking transaction. Passing AI validation is not permission to book.

## Candidate selection order

| Appointment type / trigger | Hard preference group | Model preference within that group |
|---|---|---|
| `URGENT` (all triggers) | Original provider's specialty; for `PROVIDER_OFFLINE`, exclude the original provider first. | Earliest start time. This is an operational scheduling priority, not a clinical urgency assessment. |
| `ROUTINE` / `FOLLOW_UP` + `PROVIDER_OFFLINE` | Exclude the original provider; prefer the original specialty. | Match the original appointment duration, then prefer the earliest start time. |
| `ROUTINE` / `FOLLOW_UP` + `SLOT_CANCELLED` or `INITIAL` | Prefer the original provider when it has an eligible candidate; otherwise prefer the original specialty. | Match the original appointment duration, then prefer the earliest start time. |

The backend enforces the candidate allowlist, hard eligibility checks, the highest-priority provider/specialty group, and the stable slot-ID tie-break. The model may choose among candidates in the highest-priority group using the supplied duration and time context; it is not forced to select the exact first rule-based result.

## Automated test coverage

Run from the project root:

```powershell
cd backend
mvn --batch-mode test

cd ..\frontend
npm ci
npm test
npm run build
```

The backend tests cover the gateway request URL and headers, model and prompt content, valid JSON, Markdown-fenced JSON, malformed content, missing fields, incorrect field types, invalid confidence, server errors, no-key behavior, candidate allowlisting, wrong or missing alternate provider IDs, unverified time text, provider/specialty priority tiers, urgent specialty-first priority, duration-aware AI selection within a tier, and stable slot-ID tie-breaking.

The gateway tests use a local mock HTTP server. They verify the HTTP contract and fallback behavior; they do not call the live LLM. CI intentionally does not depend on a live credential or external model availability.

## Live model evaluation — completed

**Execution status:** the project owner has completed live LLM evaluation against the configured gateway. The test was run manually outside CI using live gateway access. The test harness remains opt-in and is disabled unless `SLOTMIND_LLM_LIVE_TESTS=true`. It makes five real gateway calls and asserts that the first recommendation is from the model (not fallback) and selects the expected candidate in each scenario:

| Scenario | Trigger | Expected behavior |
|---|---|---|
| Specialty priority | `PROVIDER_OFFLINE` | Matching specialty beats an earlier slot from another specialty. |
| Original provider priority | `INITIAL` | An eligible slot from the original provider beats an earlier slot from another provider. |
| Cancelled slot recovery | `SLOT_CANCELLED` | Another eligible slot from the original provider is preferred. |
| Earliest preferred candidate | `PROVIDER_OFFLINE` | The earliest slot in the matching-specialty group is selected. |
| Stable tie-break | `INITIAL` | Equal-time candidates use the lexicographically smaller slot ID. |

Run it from `backend` after setting the rotated gateway environment variables:

```powershell
$env:SLOTMIND_LLM_LIVE_TESTS = "true"
mvn -Dtest=LiteLLMGatewayLiveTest test
```

The test can incur model usage charges and gateway latency. A failed live evaluation may mean the model's choice was rejected and the deterministic fallback took over; the test intentionally fails in that case so fallback is not mistaken for a successful AI recommendation.

Do not run this test in ordinary CI. Do not commit tokens, cookies, authorization headers, patient names, or other secrets.

## Live-gateway verification record

The project owner completed live LLM gateway testing. This run is separate from CI's mock-based tests. The updated urgency and duration-aware selection policy is covered by automated regression tests; the live gateway was not re-run as part of this code change. For an interview demonstration or a fresh verification, repeat the check with a valid, rotated credential:

1. Set the gateway environment variables documented in [README.md](README.md). `AISchedulingStrategy` is always the primary strategy; no strategy-selection variable is used.
2. Start the backend and frontend.
3. Trigger a provider-offline or slot-cancelled recovery scenario with multiple eligible candidates.
4. Confirm the recommendation source is `AI`, the chosen slot exists in the eligible list, the provider matches that slot, and the explanation is suitable for staff.
5. Confirm that accepting still requires patient confirmation and that the transactional service revalidates the slot.
6. Repeat with a deliberately unavailable gateway (for example, an invalid test base URL) and verify that the workflow falls back to `RULE_BASED`.

Record the configured model, trigger type, sanitized outcome, and whether AI or fallback produced the displayed recommendation. Do not record credentials, patient names, or full request headers.

## Evaluation measures

A live model-quality score should be calculated from a recorded set of scenarios, not inferred from one successful API call.

| Measure | Target / interpretation |
|---|---|
| Candidate validity | 100% of accepted AI recommendations reference a supplied candidate. Invalid outputs must fall back. |
| Provider consistency | 100% of accepted AI recommendations report the actual selected provider correctly. |
| Rule adherence | 100% of accepted AI recommendations belong to the documented highest-priority eligible group and obey hard safety constraints. Duration/time preferences are evaluated separately. |
| Output validity | Track the percentage of live responses that satisfy the JSON contract without repair. |
| Fallback rate | Track how often missing credentials, gateway errors, malformed output, or rejected choices lead to rule-based fallback. |
| Latency | Record gateway p50/p95 latency separately from the end-to-end workflow. |
| Explanation quality | Review whether reasoning is factual, brief, patient-friendly, and free of unverified appointment times. |

The model-reported confidence is a self-assessment. It is not a calibrated probability until it has been compared with outcomes on a representative, labelled evaluation set.

## Current limitations

- CI tests the gateway protocol with mocks and does not measure live model quality.
- The live evaluation was completed manually by the project owner; it is intentionally not run by normal CI.
- The evaluation is a finite integration check, not a large labelled production dataset, so it does not establish a statistically measured real-world accuracy rate.
- The model remains advisory. Deterministic validation and transactional booking checks are the correctness boundary.
