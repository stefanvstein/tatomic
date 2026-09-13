(ns tatomic-http.server
  (:require [org.httpkit.server :as http]
            [org.httpkit.client :as client]
            [tatomic-http.core :as api]
            [tatomic.core :as tatomic]
            [tatomic.impl.peer]
            [reitit.ring :as ring]
            [tatomic-http.response :as response]
            [tatomic-http.streaming-response :as streaming]
            #_[reitit.ring.middleware.parameters :as parameters]
            ))

(declare my-server)

(defn t->tx-handler [req]
  (let [t (parse-long (get-in req [:path-params :t]))]
    (if (nil? t)
      {:status 400
       :body "Invalid t path parameter."}
      (try
        (response/response req (tatomic/t->tx (:conn @my-server) t))
        (catch Exception e
          {:status 400
           :body (ex-message e)})))))

(defn tx->t-handler [req]
  (let [tx (parse-long (get-in req [:path-params :tx]))]
    (if (nil? tx)
      {:status 400
       :body "Invalid tx path parameter."}
      (try
        (response/response req (tatomic/tx->t (:conn @my-server) tx))
        (catch Exception e
          {:status 400
           :body (ex-message e)})))))



(defonce my-server (atom nil))



(defn database-call [call conn req]
  (let [body (-> req :body slurp)
        [code data] (call conn body)]
    (if (= 200 code)
      (response/response req data)
      {:status code
       :body data})))



(def app
  (ring/ring-handler
    (ring/router
      [["/pull" {:post #(database-call api/pull (:conn @my-server) %)}]
       ["/q" {:post #(streaming/ret-call api/qseq (:conn @my-server) %)}]
       ["/transact" {:post #(database-call api/transact (:conn @my-server) %)}]
       ["/txrange" {:post #(streaming/ret-call api/tx-range (:conn @my-server) %)}]
       ["/entid" {:post #(database-call api/entid (:conn @my-server) %)}]
       ["/ident" {:post #(database-call api/ident (:conn @my-server) %)}]
       ["/sync" {:post #(database-call api/sync (:conn @my-server) %)}]
       ["/totx/:t" {:get t->tx-handler}]
       ["/tot/:tx" {:get tx->t-handler}]])))

     




(defn start [conn]
  
  (swap! my-server (fn [s]
                     (if (nil? s)
                       {:conn conn
                        :server (http/run-server #'app {:port 8091
                                                        :server-header "datomictutor"})}
                       s))))
(defn stop []
  (swap! my-server (fn [{s :server}]
                     (if s
                       (do (s)
                           nil)
                       s))))

(defn read-body [resp]
  (if (string? (:body resp))
    resp         
    (update resp :body slurp)))

(comment
  (def peer-url "datomic:dev://localhost:4334/min-databas?password=datomic")
  (require '[datomic.api :as d])
  (require '[clojure.pprint :refer [pprint]])
  (require '[clojure.edn :refer [read-string]] )
  (def conn (d/connect peer-url))
  (do (stop)
      (start conn))
  

  
  (read-body @(client/post "http://localhost:8091/q"
                           {:headers { "Accept" "plain/text,application/edn" }
                            :body   (prn-str {:query [:find '(pull ?e [*])
                                                      :where ['?e :person/id ]
                                                      ]})}))
  

  (-> (read-body @(client/post "http://localhost:8091/q"
                               {:headers { "Accept" "application/json" #_"plain/text,application/edn" }
                                :body   "{:query [:find [(pull ?e [*]) ...] :where [?e :person/id ]]}"}))
      :body
      println)
   
    
  
  (-> (read-body @(client/post "http://localhost:8091/pull"
                               {:headers {"Accept" "application/edn"}
                                :body   (prn-str {:pattern ['*] :eid [:person/id "bertil-id-23"]})}))
      :body
      read-string
      :ret
      pprint)
  

  (read-body @(client/post "http://localhost:8091/entid"
                           {:headers {"Accept" "application/json"}
                            :body (prn-str {:ident [:person/id "bertil-id-23"]
                                            :t (- (d/basis-t (d/db conn)) 2)})}))

  (read-body @(client/post "http://localhost:8091/entid"
                           {:headers {"Accept" "application/edn"}
                            :body (prn-str {:ident :person/id 
                                            })}))

  (read-body @(client/post "http://localhost:8091/ident"
                           {:headers {"Accept" "application/edn"}
                            :body (prn-str {:eid 73 
                                            })}))
  (read-body @(client/get "http://localhost:8091/tx/1023"
                           ))
  (read-body @(client/get "http://localhost:8091/t/13194139534335"
                           ))
  (read-body @(client/post "http://localhost:8091/transact"
                           {:body (prn-str [{:db/id "eid" :person/name "Ville"}])}))
  (read-body @(client/post "http://localhost:8091/txrange"
                           {:body (prn-str {:from 1020 :to 1030})}))

  (d/entid (d/db conn) [:person/id "bertil-id-23" ])
  
   
   
  @(client/get "http://localhost:8091/wrong"
               {:headers { "Accept" "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7" #_"text/plain"}
                #_#_:body   (prn-str {:pattern ['*] :eid [:person/id "bertil-id-23"]})})
  
  (d/qseq {:query [:find '(pull ?e [*])
                   :where ['?e :person/id ]
                   ]
           :args [(d/db conn)]} )

  (let [db (d/db conn)]
    #_(d/ident db (d/entid db :person/id))
    (d/entid db :person/id))

(class (d/pull-many (d/db conn) '[*] [[:person/id "bertil-id-23"]]))
  :eoc)
