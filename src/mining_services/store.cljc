(ns mining-services.store
  "SSoT for the ISIC-08 0990 mining services contractor coordination
  actor. Store is a protocol injected into the `mining-services.actor`
  StateGraph — `MemStore` is the default, deterministic, zero-dep
  backend; a Datomic/kotoba-server-backed implementation can be
  swapped in without touching the actor or governor (itonami actor
  pattern, per ADR-2607011000 / CLAUDE.md Actors section).

  Domain:

    contractor — a registered mining services contractor
                 (:contractor-id, :name, :safety-rating)
    mine-site  — a registered mine/quarry operated by the contractor's
                 clients; target of scheduling/logistics coordination
                 (:site-id, :operator-id, :location, :risk-level)
    record     — a committed operational record under a contractor
                 (service order intake, crew dispatch, logistics
                 coordination, safety incident log) — written ONLY via
                 commit-record!, never mutated in place
    ledger     — an append-only audit trail of every proposal/verdict/
                 disposition, regardless of outcome (commit, escalate, hold)")

(defprotocol Store
  (contractor [s contractor-id])
  (mine-site [s site-id])
  (records-of [s contractor-id])
  (ledger [s])
  (register-contractor! [s contractor])
  (register-mine-site! [s site])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (contractor [_ contractor-id] (get-in @a [:contractors contractor-id]))
  (mine-site [_ site-id] (get-in @a [:mine-sites site-id]))
  (records-of [_ contractor-id] (filter #(= contractor-id (:contractor-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-contractor! [s contractor]
    (swap! a assoc-in [:contractors (:contractor-id contractor)] contractor) s)
  (register-mine-site! [s site]
    (swap! a assoc-in [:mine-sites (:site-id site)] site) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:contractors {} :mine-sites {} :records [] :ledger []} seed)))))
