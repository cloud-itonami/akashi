(ns akashi.adapters.edn-export
  "EDN tx-data exporter for akashi records. Emits Datomic/DataScript-shaped maps
  and a Datomic scalar import bundle without requiring a live DB."
  (:require [clojure.string :as str]
            [akashi.adapters.regulator-bulk-fixture-parser :as canon]))

(defn- kebab [s]
  (-> (name s)
      (str/replace #"(?<!^)([A-Z])" "-$1")
      (str/replace #"_" "-")
      str/lower-case))

(defn- record-cid [family record]
  (str "cid:akashi:" family ":" (subs (canon/sha256-json record) 0 32)))

(defn- scalar? [v]
  (or (nil? v) (string? v) (integer? v) (boolean? v)))

(defn- attr-key [family k]
  (keyword (str "akashi." family) (kebab k)))

(defn records-to-tx-data
  "Flatten validated akashi record families into tx-data maps."
  [records]
  (loop [families (sort (keys records))
         dbid -1
         tx []]
    (if-let [family (first families)]
      (let [value (get records family)
            items (if (sequential? value) value [value])
            step (reduce
                  (fn [{:keys [dbid tx]} record]
                    {:dbid (dec dbid)
                     :tx (conj tx
                               (into (sorted-map)
                                     (concat [[:db/id dbid]
                                              [:akashi.record/family family]
                                              [:akashi.record/cid (record-cid family record)]]
                                             (map (fn [[k v]] [(attr-key family k) v])
                                                  (sort-by key record)))))} )
                  {:dbid dbid :tx tx}
                  items)]
        (recur (rest families) (:dbid step) (:tx step)))
      tx)))

(defn records-to-edn [records]
  (str (pr-str (records-to-tx-data records)) "\n"))

(defn- add-op [state dbid attr value]
  (-> state
      (update :ops conj [:db/add dbid attr value])
      (update-in [:attr-values attr] (fnil conj []) value)))

(declare emit-datomic-value)

(defn- emit-datomic-value [state dbid attr value]
  (cond
    (scalar? value)
    (add-op state dbid attr value)

    (and (sequential? value) (every? scalar? value))
    (reduce #(add-op %1 dbid attr %2) state value)

    (and (map? value) (every? scalar? (vals value)))
    (reduce (fn [s [k v]]
              (add-op s dbid (keyword (namespace attr) (str (name attr) "-" (kebab k))) v))
            state
            (sort-by key value))

    :else
    (add-op state dbid (keyword (namespace attr) (str (name attr) "-json")) (canon/canon-json value))))

(defn- cardinality-many? [attr _values]
  (let [s (str attr)]
    (or (str/ends-with? s "/evidence-cids")
        (str/ends-with? s "/method-note-cids")
        (str/ends-with? s "/region-summary")
        (str/ends-with? s "/source-cids"))))

(defn- value-type [values]
  (let [sample (or (some some? values) "")]
    (cond
      (boolean? sample) :db.type/boolean
      (and (integer? sample) (not (boolean? sample))) :db.type/long
      :else :db.type/string)))

(defn- schema [attr-values]
  (->> attr-values
       (sort-by (comp str key))
       (mapv (fn [[attr values]]
               (cond-> {:db/ident attr
                        :db/valueType (value-type values)
                        :db/cardinality (if (cardinality-many? attr values)
                                          :db.cardinality/many
                                          :db.cardinality/one)}
                 (= attr :akashi.record/cid)
                 (assoc :db/unique :db.unique/identity))))))

(defn records-to-datomic-bundle
  "Return a Datomic import bundle with schema and scalar tx ops."
  [records]
  (let [{:keys [ops attr-values]}
        (loop [families (sort (keys records))
               dbid -1
               state {:ops [] :attr-values {}}]
          (if-let [family (first families)]
            (let [items (let [value (get records family)]
                          (if (sequential? value) value [value]))
                  step (reduce
                        (fn [{:keys [dbid state]} record]
                          {:dbid (dec dbid)
                           :state (reduce (fn [s [k v]]
                                            (emit-datomic-value s dbid (attr-key family k) v))
                                          (-> state
                                              (add-op dbid :akashi.record/family family)
                                              (add-op dbid :akashi.record/cid (record-cid family record)))
                                          (sort-by key record))})
                        {:dbid dbid :state state}
                        items)]
              (recur (rest families) (:dbid step) (:state step)))
            state))]
    {:akashi.datomic/schema (schema attr-values)
     :akashi.datomic/tx-data ops}))

(defn records-to-datomic-edn [records]
  (str (pr-str (records-to-datomic-bundle records)) "\n"))
