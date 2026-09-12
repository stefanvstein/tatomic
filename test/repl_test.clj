(ns repl-test
  (:require  [clojure.test :refer [deftest]]
             testify
             tatomic.test))

(deftest qseq-tests
 (testify/eval-all 'tatomic.qseq #'tatomic.test/unit-test))

(deftest pull-tests
 (testify/eval-all 'tatomic.pull #'tatomic.test/unit-test))
