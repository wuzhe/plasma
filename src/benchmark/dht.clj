(ns benchmark.dht
  (:use [plasma util graph]
        [plasma.net url connection peer bootstrap route]
        [clojure test stacktrace]
        test-utils)
  (:require [logjam.core :as log]
            [plasma.query.core :as q]))

(defn add-kademlia-route-table
  [p n-bits]
  (let [net (:id (first (query p (q/path [:net]))))]
    (with-peer-graph p
      (dotimes [i n-bits]
        (make-edge net (make-node {:bit i}) :bucket)))))

(defn peer-buckets
  [p]
  (query p (-> (q/path [b [:net :bucket]])
             (q/project [b :id :bit]))))

(defn k-peers
  [p]
  (query p (-> (q/path [b [:net :bucket]
                        p [b :peer]])
             (q/project [b :bit] [p :id :proxy]))))

(defn peers-to-k-buckets
  [p n-bits]
  (let [id (peer-id p)
        peer-ids (map :id (query p (q/path [:net :peer])))
        buckets (reduce
                  (fn [m b] (assoc m (:bit b) (:id b)))
                  {}
                  (peer-buckets p))]
    (with-peer-graph p
      (doseq [tgt peer-ids]
        (let [bucket-id (k-bucket id tgt n-bits)
              src (get buckets bucket-id)]
        (make-edge src tgt :peer))))))

(defn bucket-n
  [p n]
  (first (query p (-> (q/path [b [:net :bucket]]
                              (where (= (:bit b) n)))))))

(defn add-to-bucket
  [p n new-peer]
  (let [bucket (:id (bucket-n p n))]
    (with-peer-graph p
      (make-edge bucket (make-node new-peer) :peer))))

(defn add-bucket-peer
  [p n n-bits]
  (let [pid (peer-id p)
        tgt-id (rand-bucket-id pid n n-bits)
        result-node (dht-lookup p tgt-id n-bits)
        result-bucket (k-bucket pid (:id result-node) n-bits)]
    (add-to-bucket p result-bucket result-node)))

(defn fill-buckets
  [p n-bits]
  (dotimes [i n-bits]
    (add-bucket-peer p i n-bits)))

(defn dht-benchmark
  [n n-bit-addrs start-delay n-searches]
  (let [[strapper peers] (bootstrapped-peers n)]
    (doseq [p peers]
      (add-kademlia-route-table p n-bit-addrs))
    (Thread/sleep start-delay)
    (try
      (doseq [p peers]
        (peers-to-k-buckets p n-bit-addrs))
      [strapper peers])))

(comment
      (dotimes [i n-searches]
        (let [src-peer (rand-nth peers)
              tgt-id   (peer-id (rand-nth peers))]
          (println (format "search from peer: %s for id: %s"
                           (trim-id (peer-id src-peer))
                           (trim-id tgt-id)))
          (println "found: " (dht-lookup src-peer tgt-id n-bit-addrs))))
      (finally
        (close strapper)
        (close-peers peers)))

(defn dht
  []
  (dht-benchmark 20 8 2000 5))

#_(let [[st pe] (dht)]
  (def strap st)
  (def peers pe)
  (def p1 (first peers))
  (def p2 (second peers)))

(defn dhtr
  []
  (let [runner (bound-fn [] (dht))]
    (future (runner))))
