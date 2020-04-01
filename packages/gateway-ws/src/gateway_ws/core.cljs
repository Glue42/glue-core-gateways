(ns gateway-ws.core
  (:require [gateway-ws.ws-client :as ws-client]

            [gateway.cljs.local-client :as local-client]
            [gateway.cljs.authenticators :refer [js-authenticator]]
            
            [gateway.auth.impl :as impl]

            [gateway.node :as node]
            [gateway.local-node.core :as local-node]

            [gateway.domains.agm.core :as agm]
            [gateway.domains.activity.core :as activity]
            [gateway.domains.global.core :as global]
            [gateway.domains.metrics.core :as metrics]
            [gateway.domains.bus.core :as bus]
            [gateway.domains.context.core :as context]

            [gateway.basic-auth.core :as basic]
            [gateway.auth.core :as auth]

            [taoensso.timbre :as timbre]
            [os :as os]
            [process :as process]
            
            [clojure.walk :as walk]))

(def environment {:os         (os/type)
                  :process-id (.-pid process)
                  :start-time (- (.getTime (js/Date.)) (* 1000 (process/uptime)))})

(def local-ip (let [addresses (->> (os/networkInterfaces)
                                   (js->clj)
                                   (vals)
                                   (mapcat (fn [details] (filter #(= (get % "family") "IPv4") details)))
                                   (filter seq)
                                   (group-by #(get % "internal")))]
                (some-> (first (or (get addresses false)
                                   (get addresses true)))
                        (get "address"))))

(defn wrapping-appender
  [appender-fn]
  {:enabled?   true
   :async?     false
   :min-level  nil
   :rate-limit nil
   :output-fn  :inherit

   :fn (fn [data]
         (appender-fn #js {:time       (:instant data)
                           :level      (name (:level data))
                           :namespace  (:?ns-str data)
                           :file       (:?file data)
                           :line       (:?line data)
                           :stacktrace (:?err data)
                           :message    (force (:msg_ data))
                           :output     (force (:output_ data))}))})

(defn ^:export configure-logging
  [config]
  (let [c              (js->clj config :keywordize-keys true)
        logging-config (cond-> {:level     :info
                                :appenders {:js (timbre/console-appender {})}}

                         (:level c)     (assoc :level (keyword (:level c)))
                         (:whitelist c) (assoc :whitelist-ns (:whitelist c))
                         (:blacklist c) (assoc :blacklist-ns (:blacklist c))
                         (:appender c)  (assoc-in [:appenders :js] (wrapping-appender (:appender c))))]
    (timbre/set-config! logging-config)))


(defn ->node [configuration domains]
  (local-node/local-node domains {}))

(defn authenticators [configuration]
  (if-let [auth-fn (get-in configuration [:authentication :authenticator])]
    (do
      (timbre/info "will be using custom JS authenticator")
      {:default   :basic
       :available {:basic
                   (impl/authenticator {} (js-authenticator auth-fn))}})

    {:default   :basic
     :available {:basic (basic/authenticator {})}}))

(defn start-impl [gateway resolve reject]
  (let [configuration  (:config @gateway)]
    (timbre/info "starting gateway with configuration" configuration)
    (swap! gateway (fn [gateway]
                     (let [auths          (authenticators configuration)
                           domains        [(agm/agm-domain)
                                           (activity/activity-domain)
                                           (global/global-domain auths nil nil {:local-ip local-ip})
                                           (bus/bus-domain)
                                           (context/context-domain)
                                           (metrics/metrics-domain environment nil nil)]
                           node           (->node configuration domains)
                           clients          (atom {})]
                       (-> gateway
                           (assoc :auth auths
                                  :node node
                                  :clients clients
                                  :local-ip local-ip
                                  :server (ws-client/server (:config gateway)
                                                            clients
                                                            node
                                                            {:resolve resolve :reject reject}))))))))

(defn stop-impl [gateway]
  (timbre/info "stopping gateway")
  (swap! gateway (fn [gateway]
                   (let [srv  (:server gateway)
                         node (:node gateway)
                         auth (:auth gateway)]
                     (when srv (.close srv nil))
                     (when node (node/close node))
                     (when auth (doseq [a (vals (:available auth))]
                                  (auth/stop a))))
                   {:config (:config gateway)})))

(defn ^:export create [config]
  (let [gw (atom {:config (-> (js->clj config)
                              (walk/keywordize-keys))})]
    (reify Object
      (start [this]
        (js/Promise. (fn [resolve reject]
                       (start-impl gw resolve reject))))
      (stop [this]
        (js/Promise. (fn [resolve _]
                       (stop-impl gw)
                       (resolve this))))
      (connect [this message-cb]
        (local-client/connect gw message-cb)))))


(defn generate-exports [] #js {:create            create
                               :configure_logging configure-logging})


(set! (.-exports js/module) (generate-exports))
