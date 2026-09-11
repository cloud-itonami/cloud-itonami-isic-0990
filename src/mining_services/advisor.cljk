(ns mining-services.advisor
  "MiningServicesAdvisor — proposes a mining services coordination
  operation (service order intake, crew dispatch scheduling, site logistics
  coordination, or safety incident logging) for a registered contractor and
  mine-site. The advisor is swappable: `mock-advisor` (deterministic,
  default in dev/tests/CI) or `llm-advisor` (wraps a real
  `langchain.model/ChatModel`). Either way the advisor ONLY produces a
  PROPOSAL — it never writes to the store and has no notion of contractor/site
  provenance or high-risk decisions; `mining-services.governor` is the
  independent system that decides whether the proposal may proceed, per the
  itonami actor pattern.

  A proposal is a map:
    {:op :intake-service-order
         | :schedule-crew-dispatch
         | :coordinate-site-logistics
         | :log-safety-incident
     :effect :propose              ; advisor NEVER emits a raw store write
     :stake :low|:medium|:high
     :confidence 0.0-1.0
     :rationale str}

  LLM parse failures always yield `:confidence 0.0` (never fabricate
  confidence), which forces the governor to escalate/hold."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer
  "Deterministic mock inference: reads the request's declared op/stake
  straight through (a stand-in for what an LLM would extract from free
  text), with a stake-derived confidence."
  [_store {:keys [op stake] :as request}]
  {:op op
   :effect :propose
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for contractor " (:contractor-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a mining services contractor coordination advisor. Given a
   coordination operation request (service order, crew dispatch, logistics,
   or safety incident), propose an :op, an honest :confidence (0.0-1.0),
   and a :stake (:low/:medium/:high). Never fabricate confidence you don't
   have. You do NOT make blasting, extraction, or mine-safety decisions — those
   are operator-exclusive.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`. `gen-opts` is passed through to
  `model/-generate`. Kept decoupled from any concrete model so this ns
  has no hard dependency beyond `langchain.model`'s protocol."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "coordination request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
