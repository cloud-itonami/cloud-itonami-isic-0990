# ADR-0001: Core Architecture — Mining Services Contractor Coordination Actor

Date: 2026-07-14
Status: Accepted

## Summary

The cloud-itonami ISIC-08 0990 actor is a langgraph-clj StateGraph that gates
contractor-side coordination operations (service order intake, crew dispatch,
logistics) through a governor-enforced invariant: mine operators make blasting/extraction
decisions; contractors make field coordination decisions under human-in-the-loop
escalation for safety.

## Context

ISIC-08 0990 classifies **support activities for other mining and quarrying**,
covering contractor services (drilling rigs, support crews, logistics, equipment rental),
NOT the operator's extraction/blasting authority. A mining services company needs to:

1. Intake and verify service orders from clients (mine operators)
2. Schedule crew dispatch to mine-sites
3. Coordinate site logistics (transport, supplies, crew movement)
4. Log safety incidents (always escalating for review)

The actor must enforce a hard boundary: no contractor software makes blasting,
extraction, or mine-safety decisions — those are operator-exclusive. Violations are
instant hard blocks with no override path.

## Design

### Domain Entities

**Contractor**: A registered mining services company (`:contractor-id`, `:name`, `:safety-rating`).

**Mine-site**: A registered mine/quarry operated by one of the contractor's clients
(`:site-id`, `:operator-id`, `:location`, `:risk-level` ∈ `:low`/`:medium`/`:high`).

**Record**: A committed operational action (service order, dispatch, logistics,
safety log) — write-once via `commit-record!`, never mutated in place.

**Ledger**: Append-only audit trail of all proposals, verdicts, and dispositions,
regardless of outcome (commit or hold).

### StateGraph Flow

```
:intake → :advise → :govern → :decide ─┬─→ :commit            (ok? ∧ ¬escalate)
                                         ├─→ :request-approval  (escalate?)
                                         └─→ :hold              (hard?)
```

- **:intake**: Noop; entry point to initialize request/context channels.
- **:advise**: Calls `Advisor/-advise` to propose an operation. Default is
  `mock-advisor` (deterministic); `llm-advisor` wraps a real LLM with parse
  failure → confidence 0.0 (never fabricated).
- **:govern**: Calls `Governor/check` to assess the proposal. Returns
  `{:ok? :violations :confidence :hard? :escalate?}`. Pure function, never
  mutates store.
- **:decide**: Routes on `:hard?` / `:escalate?` to `:hold`, `:request-approval`,
  or `:commit`.
- **:request-approval**: Checkpoint node with `interrupt-before` — the run
  pauses and only resumes on explicit human call to `approve!`.
- **:commit**: Writes the record and appends ledger entry.
- **:hold**: Appends ledger entry only; no write. Irreversible.

### Governor Rules

**Hard Invariants** (`:hard? true` → `:hold`, no override):
1. Contractor provenance — request's `:contractor-id` must be registered.
2. Mine-site provenance — dispatch/logistics ops must reference a registered site.
3. No actuation — proposal `:effect` must be `:propose` (no direct store write).
4. **No operator-class ops** — any proposal with `:op` ∈
   `{:blast, :extract, :mine-safety-auth, :excavate-sequence, :ore-grade-assess,
    :ventilation-auth, :hazmat-handle, ...}`
   is instantly blocked. This is the core domain boundary.

**Escalation Invariants** (`:escalate? true` → `:request-approval`, human sign-off):
1. `:log-safety-incident` — ALL safety logging escalates.
2. High-risk site — dispatch to a site with `:risk-level :high` escalates.
3. Low confidence — advisor confidence < 0.6 escalates (LLM parse failures = 0.0).

### Scope Exclusion: What This Actor Does NOT Do

**Contractor-side (✓ in scope)**:
- Service order intake and client/site verification
- Crew dispatch scheduling
- Site logistics coordination
- Safety incident logging

**Operator-side (✗ hard-blocked, not in scope)**:
- Blasting decisions (powder type, load size, timing, sequence)
- Extraction sequencing (excavator control, ore/waste classification)
- Mine-safety determinations (ventilation adequacy, gas levels, hazard classification)
- Equipment operation authority (hauler control, drill control, hoist operation)
- Hazardous-material handling authorization
- Subsurface geology interpretation

Any proposal attempting to venture into operator decisions is flagged
`:operator-class-blocked` and routed to `:hold` with no escalation override.

### Portability and Swappable Components

`Store` is a protocol: `MemStore` (zero-dep, deterministic, default in CI/dev)
can be swapped for a Datomic/kotoba-server-backed implementation without touching
the actor or governor graph. Advisor is similarly a protocol: `mock-advisor`
(deterministic, default) can be swapped for `llm-advisor` (wraps a real ChatModel)
without touching the governor or actor wiring.

## Consequences

- **Safety**: Hard blocks (unregistered contractor, operator-class ops) prevent
  accidental escalation of decisions outside the contractor's domain. These are
  irreversible; they cannot be overridden by future human approval.
- **Auditability**: Every proposal, verdict, and disposition is appended to the
  ledger, whether committed or blocked. No silent rejections.
- **Extensibility**: New operators/coordinators or advisors can be added without
  rewriting the actor/governor logic, per the itonami pattern.

## References

- ADR-2607011000 / CLAUDE.md: itonami actor pattern (StateGraph + separate Governor + human-in-the-loop interrupt)
- ADR-2607062330 / ADR-2607062400: kotoba wasm runtime and WASM component contracts
- kotoba-lang/occupation: ISIC-08 0990 capability registry
