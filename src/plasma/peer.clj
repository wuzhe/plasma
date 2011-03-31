(ns plasma.peer
  (:use [plasma config util core connection network presence rpc]
        jiraph.graph
        clojure.stacktrace)
  (:require [logjam.core :as log]
            [lamina.core :as lamina]
            [plasma.query :as q]))

(log/repl :peer)

(defprotocol IQueryable
  (ping
    [this]
    "Simple test function to check that the peer is available.")

  ; TODO: change this to find-node once core and query are refactored...
  (node-by-uuid
    [this id]
    "Lookup a node by UUID.")

  (query 
    [this q] [this q params]
    "Issue a query against the peer's graph.")

  (query-channel
    [this q] [this q params]
    "Issue a query against the graph and return a channel onto which the
    results will be enqueued.")

  (sub-query
    [this ch plan]
    "Execute a sub-query against the peer's graph, streaming the results back
    to the source of the sub-query.")

  (recur-query
    [this q] [this pred q] [this pred q params]
    "Recursively execute a query.")
  
  (iter-n-query
    [this iq] [this n q] [this n q params]
    "Execute a query recursively, where the output of one iteration is
    used as the input to the next for count iterations."))

(def *manager* nil)
(def DEFAULT-HTL 50)

(defrecord PlasmaPeer
  [manager graph port listener options] ; peer-id, port, max-peers

  IQueryable
  (ping [_] :pong)

  (node-by-uuid
    [this id]
    (with-graph graph
                (find-node id)))

  (query 
    [this plan] 
    (query this plan {}))

  (query 
    [this plan params]
    (binding [*manager* manager]
      (with-graph graph 
                  (q/query plan params))))

  (query-channel 
    [this plan] 
    (query-channel this plan {}))

  (query-channel 
    [this plan params]
    (binding [*manager* manager]
      (with-graph (:graph this)
                  (q/query-channel plan params))))

  (sub-query
    [this ch plan]
    (binding [*manager* manager]
      (with-graph graph 
                  (q/sub-query ch plan))))

  (recur-query
    [this pred q] 
    (recur-query this pred q {}))

  (recur-query
    [this pred q params]
    (let [params (merge (:params q) params)
          iplan (assoc q
                       :type :recur-query
                       :src-url (public-url port)
                       :pred pred
                       :recur-count count
                       :htl DEFAULT-HTL
                       :params params)]
      (recur-query this iplan)))

  (recur-query
    [this plan]
     (comment let [plan (map-fn plan :recur-count dec)
           res-chan (query-channel plan (:params plan))]
       (lamina/on-closed res-chan
         (fn []
           (let [res (lamina/channel-seq res-chan)]
             ; Send the result back if we hit the end of the recursion
             (if (zero? (:recur-count plan))
               (let [src-url (:src-url plan)
                     query-id (:id plan)
                     con (get-connection manager (:src-url plan))]
                 (send-event con query-id res))

               ; or recur if not
               (doseq [n res]
                 (if (proxy-node? n)
                   (peer-recur-query 
                 (receive-all res-chan
                              (fn [v]
                                (if (proxy-node? v)
                                  (peer-recur plan v)
                                  (recur* plan v)))))))))))))

  ; TODO: Support binding to a different parameter than the ROOT-ID
  ; by passing a {:bind 'my-param} map.
  (iter-n-query
    [this n q] 
    (iter-n-query this n q {}))

  (iter-n-query
    [this n q params]
     (let [iplan (assoc q
                        :type :iter-n-query
                        :src-url (public-url port)
                        :iter-n n
                        :htl DEFAULT-HTL
                        :iter-params params)]
     (iter-n-query this iplan)))

  (iter-n-query
    [this q]
    (let [final-res (lamina/channel)
           iter-fn (fn iter-fn [q]
                     (log/to :peer "iter-n: " (:iter-n q))
                     (let [plan (update-in q [:iter-n] dec)
                           plan (update-in plan [:htl] dec)
                           res-chan (query-channel this plan (:iter-params plan))]
                       (lamina/on-closed res-chan
                         (fn []
                           (cond 
                             (zero? (:iter-n plan))
                             (lamina/siphon res-chan final-res)

                             (zero? (:htl plan))
                             (lamina/enqueue final-res 
                                             {:type :error
                                              :msg :htl-reached})

                             :default
                             (let [res (lamina/channel-seq res-chan)
                                   params (assoc (:iter-params plan) ROOT-ID res)
                                   plan (assoc plan :iter-params params)]
                               (log/to :peer "--------------------\n"
                                       "iter-fn result: " 
                                       (seq res)
                                       "\n--------------------------\n")

                               (iter-fn plan)))))))]
       (iter-fn q)
       final-res))

  IClosable
  (close 
    [this] 
    (close listener)
    (if (:internal-manager options)
      (clear-connections manager))))

(defn- setup-presence-listener
  [p]
  (lamina/receive-all (presence-listener)
    (fn [{:keys [peer-id peer-port peer-host]}]
      (register-connection (:manager p) peer-host peer-port))))

(defn- request-handler
  [peer [ch req]]
  (log/to :peer "rpc-request: " (:id req))
  (try
    (let [res (case (:method req)
                'ping (ping peer)
                'node-by-uuid (node-by-uuid peer (first (:params req)))
                'query (query peer (first (:params req))))]
      ;(log/to :peer "result: " res)
      (lamina/enqueue ch (rpc-response req res)))
    (catch Exception e
      (log/to :peer "error handling request!\n------------------\n" 
              (with-out-str (print-cause-trace e)))
      (.printStackTrace e)
      (lamina/enqueue ch 
        (rpc-error req "Exception occured while handling request." e)))))

(defn- stream-handler
  [peer [ch req]]
  (log/to :peer "stream-request: " (:id req))
  (try
    (case (:method req)
      'sub-query (sub-query peer ch (first (:params req))))
    (catch Exception e
      (log/to :peer "error handling stream request!\n"
              "-------------------------------\n" 
              (with-out-str (print-cause-trace e))))))

(defn- setup-peer-connection-handlers
  [peer con]
  (lamina/receive-all (request-channel con) 
                      (partial request-handler peer))
  (lamina/receive-all (stream-channel con) 
                      (partial stream-handler peer)))

(defn peer
  "Create a new peer using a graph database located at path, optionally
  specifying the port number to listen on."
  ([path] (peer path {}))
  ([path options]
   (let [port (get options :port (config :peer-port))
         [manager options] (if (:manager options)
                             [(:manager options) options]
                             [(connection-manager)
                              (assoc options :internal-manager true)])
         g (graph path)
         listener (connection-listener manager port)
         p (PlasmaPeer. manager g port listener options)]
     (on-connect listener (partial setup-peer-connection-handlers p))

     (when (:presence options)
       (setup-presence-listener p))
     p)))

;TODO: Make these methods of a PeerConnection

(defn peer-node
  "Lookup a node by ID on a remote peer."
  [con id & [timeout]]
  (let [res-chan (request con 'node-by-uuid [id])]
    (if timeout
      (:result (lamina/wait-for-message res-chan timeout))
      res-chan)))

; TODO: Return a result channel and enqueue an error if we fail
(defn peer-query
  "Send a query to the given peer.  Returns a constant channel
  that will get the result of the query when it arrives."
  [con q & [timeout]]
  (let [res-chan (request con 'query [q])]
    (lamina/receive-all (lamina/fork res-chan) 
                        #(log/to :peer "peer-query result: " %))
    (if timeout
      (:result (lamina/wait-for-message res-chan timeout))
      res-chan)))

(defn peer-sub-query
  [con q]
  (stream con 'sub-query [q]))

(defmethod peer-sender "plasma"
  [url]
  (partial peer-sub-query (get-connection *manager* url)))

(comment

(defn peer-dispatch [peer ch req]
  (when req
    (log/to :peer "[peer-dispatch] req-id: " (:id req))
    (let [id (:id req)
          msg (:body req)]
      (try
        (let [graph (:graph peer)
              response
              (cond
                (= ROOT-ID msg)
                (with-graph graph (root-node))

                (uuid? msg)
                (with-graph graph (find-node msg))

                (= :ping msg)
                :pong

                (= :query (:type msg))
                (do
                  (log/to :peer "[peer-handler] query")
                  (enqueue (:on-query peer) {:chan ch
                                             :query msg
                                             :client-info client-info})
                  (with-graph graph (query msg)))

                (= :sub-query (:type msg))
                (do
                  (log/to :peer "[peer-handler] sub-query")
                  (with-graph graph (sub-query ch msg))))]

          (let [res {:type :response :id id :body response}]
            (log/to :peer "[peer-dispatch] response: " res)
            (unless (= :sub-query (:type msg))
                    (enqueue ch res))))
        (catch Exception e
          (log/to :peer "server error")
          (log/to :peer "------------")
          (log/to :peer "req from " client-info ": " req)
          (log/to :peer "caused exception: " e (.printStackTrace e)))))))

(defn- register-peer-connection
  "Connect to local peer and ping hello."
  [host port])

(defn- peer-server
  "Listen on port, responding to queries against the peer graph."
  [peer]
  (log/to :peer "[peer-server] starting on port: " (:port peer))
  (let [s (start-object-server
            (fn [chan client-info]
              (log/to :peer "[peer-server] new client: " client-info)
              (enqueue (:on-connect peer)
                       (register-connection chan client-info))
              (receive-all chan (partial peer-dispatch peer chan)))
            {:port (:port peer)})]
    (reset! (:server peer) s)))

(defn- get-peer-connection
  [peer]
  (if (= :local-peer (:type peer))
    (throw (Exception. "Cannot open a connection to a local peer.")))
  (wait-for-result (:connection peer) 2000))

(defn- init-peer-graph
  []
  (when-not (:edges (find-node (root-node)))
    (let [net  (node :label :net)]
      (edge (root-node) net :label :net))))

;(log/to :peer "[peer] path:" path " port:" port)



(defn on-peer-connect
  "Returns an event channel of new peer connections."
  [p]
  (fork (:on-connect p)))

(defn on-peer-query
  "Returns an event channel of incoming query events.
  The event is a map containing a channel to the remote peer, the query,
  and the client information for the remote peer.
    {:chan ch
    :query msg
    :client-info client-info}
  "
  [p]
  (fork (:on-query p)))

; * use Upnp library to figure out public port and IP to create own URL
; * check-live-peers: ping all peers and drop ones that don't respond

; DataChannel
; Use to send audio, video, events, etc...
; - provides a generic object communication pipe
; - support encryption
; - support nat traversal and routing through a 3rd party
; -
; request a named channel with some args
; on-named-channel request handler
;

(defprotocol DHTClient
  (close []) ; leave?
  (put [obj])
  (get [obj])
  (delete [obj]))

; join (part of construction, or part of protocol?)
; (getSuccessor [id])

(defprotocol RandomWalkClient
  (find [key])
  (sample-query [query]))

; peer connection protocol to talk to remote peer
; id (name?): return the peer-id
; query: run a query
; get-node: return a node (or some properties) given a UUID
; get-peers: return all peers

; group communication
; join
; leave
; send-message
; on-message-received

; Example apps
;
; * text based chat (or maybe with basic swing gui)
(defprotocol ChatClient
  (onMessage [])
  (sendMessage []))

; * simple work queue system, where jobs can be added to a server and it forwards them to
; available client workers


; Buddy Service
; * looks up buddies on join, and then periodically pings them to monitor their status
; * allows for querying and opening channels to friends
; * store buddy info in local graph (proxy nodes)
(defprotocol ContactClient
  (onArrival [])
  (onDeparture [])
  (info [])
  (query [])
  (channel []))

; Distributed drawing or click command
; * simple P2P app where the user on one side clicks on the screen and the peers
; see dots or lines or something

; Given an arbitrary name return back the peer connection info
(defprotocol NamingService    ; NamingClient?
  (resolve [name]))

; Local network broadcaster (using UDP)
; - periodically broadcast a simple presence message with local peer connection info
; - listener gets called whenever a UDP packet arrives
;

; Gossip based updates of ??? document, peer list, named groups, service info?
; - gossip based publication of arbitrary object, or key/value pair?


; Super simple jSpeex audio streaming on top of Plasma
;
)
