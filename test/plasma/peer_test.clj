(ns plasma.peer-test
  (:use [plasma core peer query]
        [lamina core]
        [jiraph graph]
        test-utils
        clojure.test
        clojure.stacktrace)
  (:require [logjam.core :as log]))

(log/file :peer "peer.log")

(deftest connection-pool-test []
  (dotimes [i 1000]
    (refresh-connection {:host "test.com"
                         :port i})
    (is (<= (count @connection-pool*)
           MAX-POOL-SIZE))))

(defn- init-peer
  [p]
  (with-graph (:graph p)
    (test-graph)))

(deftest peer-test []
  (let [port (+ 1000 (rand-int 10000))
        p1 (peer "db/p1" port)]
    (try
      (init-peer p1)
      (let [con (peer-connection "localhost" port)]
        (is (= "pong" (wait-for-message (remote-query con "ping") 1000)))
        (is (= :self (:label (wait-for-message (remote-query con ROOT-ID) 1000))))
        (is (= 4 (count (wait-for-message (remote-query con (path [:music :synths :synth])) 1000))))
        (println "result: " (wait-for-message (remote-query con (path [:music :synths :synth])) 1000)))
      (finally
        (peer-close p1)))))


