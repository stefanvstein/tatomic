(ns tatomic.inline-test
  (:require  [clojure.test :refer [deftest]]
             testify
             tatomic.test))

(deftest qseq-tests
 (testify/eval-all 'tatomic.impl.qseq #'tatomic.test/unit-test))

(deftest pull-tests
 (testify/eval-all 'tatomic.impl.pull #'tatomic.test/unit-test))
