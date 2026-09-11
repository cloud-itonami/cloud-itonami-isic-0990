(ns mining-services.governor
  "MiningServicesGovernor — the independent safety/traceability layer
  for the ISIC-08 0990 mining services contractor actor. Wired as its
  own `:govern` node in `mining-services.actor`'s StateGraph, downstream
  of `:advise` — the Advisor has no notion of contractor/site provenance or
  high-risk operations, so this MUST be a separate system able to reject a
  proposal (itonami actor pattern, per ADR-2607011000 / CLAUDE.md Actors
  section).

  CRITICAL DOMAIN NOTE: This actor supports a CONTRACTOR's back-office
  coordination — dispatch, scheduling, logistics, safety logging. It does
  NOT make blasting, extraction, or mine-safety decisions. Those
  remain the OPERATOR's exclusive authority. Scope boundaries:
    ✓ Service order intake and verification
    ✓ Crew dispatch scheduling
    ✓ Site logistics coordination (transport, supplies, crew movement)
    ✓ Safety incident logging (always escalated)
    ✗ Blasting/extraction decisions
    ✗ Mine-safety determinations
    ✗ Equipment operation authority

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. contractor provenance   — the request's contractor must be registered.
    2. site provenance         — dispatch/logistics ops must involve a
                               registered mine-site.
    3. no-actuation            — proposal :effect must be :propose.
    4. no-blasting/extraction  — any proposal flagged :op with operator-class
                               ops (:blast/:extract/:mine-safety-auth)
                               is an instant hard block with no override path.

  ESCALATION invariants (:escalate? true, ALWAYS human sign-off):
    5. :op :log-safety-incident — ALL safety incident logging escalates.
    6. high-risk site          — dispatch to a site flagged :risk-level :high
                               escalates.
    7. low confidence          — < `confidence-floor`."
  (:require [mining-services.store :as store]))

(def confidence-floor 0.6)

;; Ops that are hard-blocked (not in contractor's domain; operator only)
(def ^:private operator-class-ops
  #{:blast :extract :mine-safety-auth :excavate-sequence :ore-grade-assess
    :ventilation-auth :hazmat-handle :equipment-operation})

;; Ops that always escalate
(def ^:private always-escalate-ops #{:log-safety-incident})

(defn- hard-violations [{:keys [proposal]} contractor-record site-record]
  (cond-> []
    (nil? contractor-record)
    (conj {:rule :no-contractor :detail "未登録 contractor"})

    (and (not (nil? site-record)) (nil? site-record))
    (conj {:rule :no-site :detail "未登録 mine-site"})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

    (contains? operator-class-ops (:op proposal))
    (conj {:rule :operator-class-blocked
           :detail "blasting/extraction/mine-safety ops are OPERATOR-exclusive; not in contractor scope"})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `mining-services.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [contractor-record (store/contractor store (:contractor-id request))
        site-id (get-in request [:site :site-id])
        site-record (when site-id (store/mine-site store site-id))
        hard (hard-violations {:proposal proposal} contractor-record site-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        safety-op? (contains? always-escalate-ops (:op proposal))
        high-risk? (and site-record (= :high (:risk-level site-record)))]
    {:ok? (and (not hard?) (not low?) (not safety-op?) (not high-risk?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? safety-op? high-risk?))}))
