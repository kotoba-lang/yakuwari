(ns yakuwari.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [yakuwari.spec :as spec]
            [yakuwari.policy :as policy]
            [yakuwari.reconcile :as rec]))

(def t0 1785000000000)

(def base
  {:yakuwari/id :panel-steward
   :yakuwari/project "network-awai/person-awai-ryo"
   :yakuwari/objective "run panel studies and return findings to the product repo"
   :yakuwari/scale {:min 0 :desired 2 :max 4}
   :yakuwari/runners [{:runner :claude :weight 1}]
   :yakuwari/policy {:mail.inbound :autonomous
                     :mail.send :approval-required
                     :account.create :blocked}})

(defn run-of [id status & [at]]
  {:agent.run/id id
   :agent.run/yakuwari :panel-steward
   :agent.run/status status
   :agent.run/updated-at (or at t0)})

;; ───────────────────────────── spec ─────────────────────────────

(deftest a-valid-spec-passes
  (is (:ok? (spec/validate base)))
  (is (= ["claude"] (spec/runner-pool base))))

(deftest every-problem-is-reported-at-once
  (testing "one error per round trip turns a ten-field spec into a ten-step form"
    (let [r (spec/validate {:yakuwari/scale {:min 5 :desired 1 :max 2}
                            :yakuwari/policy {:x :nonsense}})]
      (is (not (:ok? r)))
      (is (>= (count (:problems r)) 5))
      (is (some #(= :missing-id (:problem %)) (:problems r)))
      (is (some #(= :missing-project (:problem %)) (:problems r)))
      (is (some #(= :missing-objective (:problem %)) (:problems r)))
      (is (some #(= :invalid-scale (:problem %)) (:problems r)))
      (is (some #(= :no-runners (:problem %)) (:problems r)))
      (is (some #(= :unknown-decision (:problem %)) (:problems r))))))

(deftest a-role-nobody-can-fill-is-refused
  (is (some #(= :no-runners (:problem %))
            (:problems (spec/validate (dissoc base :yakuwari/runners))))))

(deftest scale-bounds-are-ordered
  (doseq [bad [{:min 0 :desired 5 :max 2}
               {:min 3 :desired 1 :max 4}
               {:min 0 :desired 0 :max 0}]]
    (is (not (:ok? (spec/validate (assoc base :yakuwari/scale bad))))
        (str "should reject " bad))))

(deftest a-yakuwari-has-no-lease
  (testing "no lifespan anywhere in the spec — the whole point of this layer.
            A role that cannot rewrite itself needs no temporal re-consent."
    (let [ks (set (keys (:spec (spec/validate base))))]
      (is (not-any? #(re-find #"lifetime|expires|lease|lifespan" (str %)) ks)))))

;; ──────────────────────────── policy ────────────────────────────

(deftest capability-decisions-fail-closed
  (is (= :autonomous (spec/decide base :mail.inbound)))
  (is (= :approval-required (spec/decide base :mail.send)))
  (is (= :blocked (spec/decide base :account.create)))
  (testing "unlisted is blocked — permission must not arrive by omission"
    (is (= :blocked (spec/decide base :some.capability.nobody.reviewed)))))

(deftest legacy-spellings-still-resolve-but-are-visible
  (let [p {:a :self-executing :b :propose :c :forbidden}]
    (is (= :autonomous (policy/decide p :a)))
    (is (= :approval-required (policy/decide p :b)))
    (is (= :blocked (policy/decide p :c)))
    (is (= 3 (count (policy/deprecated-spellings p))))))

;; ─────────────────────────── reconcile ───────────────────────────

(deftest plan-spawns-up-to-desired
  (let [p (rec/plan base [] t0)]
    (is (= 2 (:desired p)))
    (is (= 2 (:spawn p)))
    (is (empty? (:cancel p)))))

(deftest plan-counts-only-its-own-runs
  (let [other (assoc (run-of "x" :running) :agent.run/yakuwari :someone-else)
        p (rec/plan base [other] t0)]
    (is (= 2 (:spawn p)))))

(deftest running-work-is-never-cancelled-to-shed-capacity
  (let [runs [(run-of "a" :running) (run-of "b" :running) (run-of "c" :running)]
        p (rec/plan (assoc base :yakuwari/scale {:min 0 :desired 1 :max 4}) runs t0)]
    (testing "over capacity, but nothing eligible — cancelling running work
              would discard work already paid for"
      (is (= 0 (:spawn p)))
      (is (empty? (:cancel p))))))

(deftest queued-and-held-runs-are-the-cancellable-ones
  (let [runs [(run-of "a" :running) (run-of "b" :queued) (run-of "c" :held)]
        p (rec/plan (assoc base :yakuwari/scale {:min 0 :desired 1 :max 4}) runs t0)]
    (is (= 2 (count (:cancel p))))
    (is (= #{"b" "c"} (set (:cancel p))))))

(deftest a-stale-lease-is-reaped-not-counted
  (testing "a run nobody is executing still holds a slot; leaving it there
            starves the role it was meant to fill"
    (let [stale (run-of "old" :leased (- t0 (* 10 60 1000)))
          p (rec/plan base [stale] t0)]
      (is (= ["old"] (:reap p)))
      (is (= 2 (:spawn p)) "the stale run must not count toward capacity"))))

(deftest held-runs-raise-capacity-so-hil-does-not-stall-the-role
  (let [held [(run-of "a" :held) (run-of "b" :held)]
        s (assoc base
                 :yakuwari/scale {:min 0 :desired 2 :max 4}
                 :yakuwari/control-pressure {:blocker-count 2})
        p (rec/plan s held t0)]
    (testing "all workers waiting on a human means no progress; capacity rises"
      (is (= 3 (:desired p)))
      (is (= 2 (:blocked p))))))

(deftest capacity-never-escapes-its-bounds
  (let [held (repeat 4 (run-of "h" :held))
        s (assoc base :yakuwari/scale {:min 0 :desired 4 :max 4}
                 :yakuwari/control-pressure {:queue-depth 1 :blocker-count 1})]
    (is (= 4 (:desired (rec/plan s (vec held) t0))))))

(deftest scale-down-waits-out-the-idle-window
  (let [runs [(run-of "a" :running) (run-of "b" :queued t0)]
        s (assoc base :yakuwari/scale {:min 0 :desired 1 :max 4
                                       :scale-down-after-ms 60000})]
    (testing "freshly queued work is not cancelled the instant capacity dips"
      (is (empty? (:cancel (rec/plan s runs t0)))))
    (testing "once it has sat idle past the window, it is eligible"
      (is (= ["b"] (:cancel (rec/plan s runs (+ t0 61000))))))))

(deftest host-adapters-can-preserve-their-durable-run-schema
  (let [actor-spec (-> base
                       (assoc :yakuwari/id :revenue/test
                              :yakuwari/run-key :agent.run/actor
                              :yakuwari/stale-policy :execution-deadline
                              :yakuwari/pressure-mode :tamaki
                              :yakuwari/control-pressure 0.8)
                       (assoc :yakuwari/scale
                              {:min 1 :desired 2 :max 5
                               :scale-up-on {:business-pressure 0.65}}))
        run {:agent.run/id "legacy"
             :agent.run/actor :revenue/test
             :agent.run/status :running
             :agent.run/updated-at t0
             :agent.run/budget {:deadline-ms 1000}}
        live (rec/plan actor-spec [run] (+ t0 1000))
        expired (rec/plan actor-spec [run]
                          (+ t0 1000 rec/default-lease-grace-ms))]
    (is (= 4 (:desired live)))
    (is (= 1 (:running live)))
    (is (= ["legacy"] (:reap expired)))
    (is (= 4 (:spawn expired)))))
