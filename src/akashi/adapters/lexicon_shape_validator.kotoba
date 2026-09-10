(ns akashi.adapters.lexicon-shape-validator
  "Small dependency-free validator for akashi fixture-parser outputs. Covers
  the lexicon-shape subset akashi R0/R1 fixtures use: required fields,
  primitive types, arrays, object refs, const values, knownValues, and
  numeric/string bounds. Records/lexicons are JSON-shaped string-keyed maps."
  (:require [clojure.string :as str]))

(declare validate-value validate-object)

(defn- verr [msg] (throw (ex-info msg {:akashi/validation true})))

(defn validate-record
  "Raise (ex-info) when a record does not match the lexicon main record."
  [record lexicon]
  (let [schema (get-in lexicon ["defs" "main" "record"])
        required (get schema "required" [])
        props (get schema "properties")
        lid (get lexicon "id")]
    (doseq [field required]
      (when-not (contains? record field)
        (verr (str lid ": missing required field " field))))
    (doseq [[field value] record]
      (when-not (contains? props field)
        (verr (str lid ": unknown field " field)))
      (validate-value value (get props field) lexicon (str lid "." field)))
    nil))

(defn validate-records
  "Validate a sequence of records against one akashi lexicon."
  [records lexicon]
  (doseq [record records] (validate-record record lexicon))
  nil)

(defn- validate-value [value schema lexicon path]
  (when (and (contains? schema "const") (not= value (get schema "const")))
    (verr (str path ": expected const " (pr-str (get schema "const")))))
  (when (and (contains? schema "knownValues")
             (not (contains? (set (get schema "knownValues")) value)))
    (verr (str path ": unknown value " (pr-str value))))
  (let [t (get schema "type")]
    (cond
      (= t "ref")
      (validate-object value (get-in lexicon ["defs" (str/replace (get schema "ref") #"^#+" "")])
                       lexicon path)

      (= t "object")
      (validate-object value schema lexicon path)

      (= t "array")
      (do
        (when-not (sequential? value) (verr (str path ": expected array")))
        (when (and (contains? schema "minLength") (< (count value) (get schema "minLength")))
          (verr (str path ": shorter than minLength " (get schema "minLength"))))
        (when-let [item-schema (get schema "items")]
          (doseq [[i item] (map-indexed vector value)]
            (validate-value item item-schema lexicon (str path "[" i "]")))))

      (= t "string")
      (do
        (when-not (string? value) (verr (str path ": expected string")))
        (when (and (contains? schema "minLength") (< (count value) (get schema "minLength")))
          (verr (str path ": shorter than minLength " (get schema "minLength"))))
        (when (and (contains? schema "maxLength") (> (count value) (get schema "maxLength")))
          (verr (str path ": longer than maxLength " (get schema "maxLength")))))

      (= t "integer")
      (do
        (when (or (not (integer? value)) (boolean? value)) (verr (str path ": expected integer")))
        (when (and (contains? schema "minimum") (< value (get schema "minimum")))
          (verr (str path ": below minimum " (get schema "minimum"))))
        (when (and (contains? schema "maximum") (> value (get schema "maximum")))
          (verr (str path ": above maximum " (get schema "maximum")))))

      (= t "boolean")
      (when-not (boolean? value) (verr (str path ": expected boolean")))

      :else
      (verr (str path ": unsupported schema type " t)))))

(defn- validate-object [value schema lexicon path]
  (when-not (map? value) (verr (str path ": expected object")))
  (let [props (get schema "properties" {})]
    (doseq [field (get schema "required" [])]
      (when-not (contains? value field)
        (verr (str path ": missing required object field " field))))
    (doseq [[field child] value]
      (when-not (contains? props field)
        (verr (str path ": unknown object field " field)))
      (validate-value child (get props field) lexicon (str path "." field)))))
