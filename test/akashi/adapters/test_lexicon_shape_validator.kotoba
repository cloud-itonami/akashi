(ns akashi.adapters.test-lexicon-shape-validator
  "Tests for the akashi lexicon shape validator (ADR-2606071600 port of lexicon_shape_validator).
  A representative lexicon drives the happy path plus every rejection branch: missing required,
  unknown field, const mismatch, knownValues miss, string min/max, integer min/max/type, boolean
  type, array minLength + item type, ref-object missing/unknown field, unsupported type."
  (:require [clojure.test :refer [deftest is]]
            [akashi.adapters.lexicon-shape-validator :as v]))

(def ^:private lex
  {"id" "com.example.test"
   "defs" {"main" {"record" {"required" ["name" "count"]
                             "properties" {"name" {"type" "string" "minLength" 1 "maxLength" 10}
                                           "count" {"type" "integer" "minimum" 0 "maximum" 100}
                                           "active" {"type" "boolean"}
                                           "kind" {"type" "string" "knownValues" ["a" "b"]}
                                           "tags" {"type" "array" "minLength" 1 "items" {"type" "string"}}
                                           "owner" {"type" "ref" "ref" "#person"}
                                           "version" {"type" "integer" "const" 1}
                                           "weird" {"type" "nonsense"}}}}
           "person" {"required" ["pid"] "properties" {"pid" {"type" "string"}}}}})

(def ^:private good
  {"name" "ok" "count" 5 "active" true "kind" "a" "tags" ["x"] "owner" {"pid" "p1"} "version" 1})

(defn- bad? [record]
  (try (v/validate-record record lex) false
       (catch clojure.lang.ExceptionInfo _ true)))

(deftest test-valid-record-passes
  (is (nil? (v/validate-record good lex)))
  (is (nil? (v/validate-records [good good] lex))))

(deftest test-missing-required
  (is (bad? (dissoc good "name")))
  (is (bad? (dissoc good "count"))))

(deftest test-unknown-field
  (is (bad? (assoc good "extra" 1))))

(deftest test-const-and-known-values
  (is (bad? (assoc good "version" 2)))      ; const mismatch
  (is (bad? (assoc good "kind" "z"))))      ; not in knownValues

(deftest test-string-bounds-and-type
  (is (bad? (assoc good "name" "")))                       ; below minLength 1
  (is (bad? (assoc good "name" "0123456789x")))            ; above maxLength 10
  (is (bad? (assoc good "name" 42))))                      ; not a string

(deftest test-integer-bounds-and-type
  (is (bad? (assoc good "count" -1)))                      ; below minimum
  (is (bad? (assoc good "count" 101)))                     ; above maximum
  (is (bad? (assoc good "count" "5")))                     ; not integer
  (is (bad? (assoc good "count" true))))                   ; bool is not integer

(deftest test-boolean-type
  (is (bad? (assoc good "active" "yes"))))

(deftest test-array-bounds-and-items
  (is (bad? (assoc good "tags" [])))                       ; shorter than minLength
  (is (bad? (assoc good "tags" [123])))                    ; item not a string
  (is (bad? (assoc good "tags" "notarray"))))              ; not an array

(deftest test-ref-object
  (is (bad? (assoc good "owner" {})))                      ; missing required object field pid
  (is (bad? (assoc good "owner" {"pid" "p" "x" 1})))       ; unknown object field
  (is (bad? (assoc good "owner" "notanobject"))))          ; not an object

(deftest test-unsupported-type
  (is (bad? (assoc good "weird" "anything"))))

(deftest test-error-message-shape
  (try (v/validate-record (dissoc good "name") lex)
       (is false "should have thrown")
       (catch clojure.lang.ExceptionInfo e
         (is (= "com.example.test: missing required field name" (.getMessage e))))))
