(ns yakuwari.spec
  "役割 — a durable organizational role.

  A yakuwari decides WHAT to do (self-judgement) but cannot rewrite its own
  definition (no self-evolution). That single asymmetry is the reason it
  needs no lease: a human who reviewed this spec once stays correct,
  because the thing they approved cannot change underneath them. Only the
  AO above it — which does rewrite itself — needs a temporal re-consent
  point.

  Its identity describes **responsibility, not implementation**:
  `issue-scout`, `independent-reviewer`, `panel-steward`. Runner names
  (`codex`, `claude`, `grok`) are execution attributes and must never be
  used as yakuwari identity — that collapse is what tamaki ADR-0001 was
  written to stop.

  Extracted from `kotoba.tamaki.actor` (ADR-0001). Named `yakuwari` rather
  than `actor` because that word is already taken three times over: the
  Hewitt concurrency primitive (`kotoba-lang/actor-ipc`), the wasmCloud-style
  deployment unit on murakumo, and this. Three collisions is not a naming
  preference.

  Pure and portable — no clock, no storage, no dispatch."
  (:require [clojure.string :as str]
            [yakuwari.policy :as policy]))

(def default-scale {:min 0 :desired 1 :max 1})

(defn yakuwari-id [value]
  (cond
    (keyword? value) value
    (and (string? value) (not (str/blank? value))) (keyword value)
    :else (throw (ex-info "yakuwari requires an id" {:value value}))))

(defn validate
  "Report every problem at once — a UI showing one error per round trip
  makes a ten-field spec a ten-step form.

  Returns {:ok? bool :problems [...] :spec normalized}."
  [spec]
  (let [id (try (yakuwari-id (:yakuwari/id spec)) (catch #?(:clj Exception :cljs :default) _ nil))
        project (:yakuwari/project spec)
        objective (:yakuwari/objective spec)
        scale (merge default-scale (:yakuwari/scale spec))
        {:keys [min desired max]} scale
        runners (vec (:yakuwari/runners spec))
        pol (:yakuwari/policy spec)
        problems
        (cond-> []
          (nil? id) (conj {:problem :missing-id})

          (str/blank? (str project)) (conj {:problem :missing-project})

          (str/blank? (str objective)) (conj {:problem :missing-objective})

          (not (and (integer? min) (integer? desired) (integer? max)
                    (<= 0 min desired max) (pos? max)))
          (conj {:problem :invalid-scale :scale scale
                 :rule "0 <= min <= desired <= max, max > 0"})

          ;; A yakuwari with no runner cannot produce a single execution, so
          ;; it is a role nobody can fill — worth refusing at definition time
          ;; rather than discovering at reconcile time.
          (empty? runners) (conj {:problem :no-runners})

          :always
          (into (for [{:keys [runner weight]} runners
                      :when (or (str/blank? (str (if (keyword? runner) (name runner) runner)))
                                (not (pos-int? (or weight 1))))]
                  {:problem :invalid-runner :runner runner :weight weight}))

          :always
          (into (:problems (policy/validate-policy (or pol {})))))]
    {:ok? (empty? problems)
     :problems (vec problems)
     :spec (assoc spec :yakuwari/id id :yakuwari/scale scale
                  :yakuwari/runners runners)}))

(defn validate!
  [spec]
  (let [r (validate spec)]
    (when-not (:ok? r)
      (throw (ex-info "Invalid yakuwari spec" r)))
    (:spec r)))

(defn decide
  "What may this yakuwari do with `capability`? Delegates to yakuwari.policy,
  which fails closed on anything unlisted."
  [spec capability]
  (policy/decide (:yakuwari/policy spec) capability))
