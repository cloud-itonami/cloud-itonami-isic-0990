(ns mining-services.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [mining-services.governor :as governor]
            [mining-services.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-contractor! st {:contractor-id "contractor-1" :name "Acme Mining Services" :safety-rating 4.5})
    (store/register-mine-site! st {:site-id "site-001" :operator-id "operator-1" :location "Sierra Leone" :risk-level :medium})
    st))

(deftest accepts-clean-low-confidence-proposal
  (let [st (fresh-store)
        request {:contractor-id "contractor-1"}
        proposal {:op :intake-service-order :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (:ok? verdict))
    (is (not (:hard? verdict)))
    (is (not (:escalate? verdict)))))

(deftest hard-blocks-unregistered-contractor
  (let [st (fresh-store)
        request {:contractor-id "no-such-contractor"}
        proposal {:op :intake-service-order :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (:hard? verdict))
    (is (seq (:violations verdict)))))

(deftest hard-blocks-blasting-operation
  (let [st (fresh-store)
        request {:contractor-id "contractor-1"}
        proposal {:op :blast :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (:hard? verdict))
    (is (seq (:violations verdict)))))

(deftest escalates-safety-incident
  (let [st (fresh-store)
        request {:contractor-id "contractor-1"}
        proposal {:op :log-safety-incident :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))))

(deftest escalates-high-risk-site-dispatch
  (let [st (store/mem-store)
        _ (store/register-contractor! st {:contractor-id "contractor-1" :name "Acme Mining Services" :safety-rating 4.5})
        _ (store/register-mine-site! st {:site-id "site-high-risk" :operator-id "operator-1" :location "Sierra Leone" :risk-level :high})
        request {:contractor-id "contractor-1" :site {:site-id "site-high-risk"}}
        proposal {:op :schedule-crew-dispatch :effect :propose :confidence 0.95}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))))

(deftest escalates-low-confidence-proposal
  (let [st (fresh-store)
        request {:contractor-id "contractor-1"}
        proposal {:op :intake-service-order :effect :propose :confidence 0.5}
        verdict (governor/check request {} proposal st)]
    (is (not (:ok? verdict)))
    (is (not (:hard? verdict)))
    (is (:escalate? verdict))))
