(ns gateway-web.core
  (:require [gateway.cljs.local-client :as local-client]
            [gateway.cljs.common :as common]
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

            [taoensso.timbre :refer-macros [info] :as timbre]
            [promesa.core :as p]
            [promesa.exec :as exec]
            
            [clojure.walk :as walk]))

(timbre/set-level! :info)

(def environment {:start-time (common/now)})

(defn wrapping-appender
  [appender-fn]
  {:enabled?   true
   :async?     false
   :min-level  nil
   :rate-limit nil
   :output-fn  :inherit

   :fn         (fn [data]
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
  (let [c (js->clj config :keywordize-keys true)
        logging-config (cond-> {:level     :info
                                :appenders {:js (timbre/console-appender {})}}

                         (:level c) (assoc :level (keyword (:level c)))
                         (:whitelist c) (assoc :whitelist-ns (:whitelist c))
                         (:blacklist c) (assoc :blacklist-ns (:blacklist c))
                         (:appender c) (assoc-in [:appenders :js] (wrapping-appender (:appender c))))]
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

(defn scavenge-clients!
  [started? scavenger-task clients node inactive-seconds]
  (when (and @started? (pos? inactive-seconds))
    (let [older-than (- (common/now) (* inactive-seconds 1000))]
      (common/scavenge-clients! clients node older-than)
      (swap! scavenger-task #(exec/schedule! 1000 (fn [] (scavenge-clients! started? scavenger-task clients node inactive-seconds)))))))

(defn start-impl [gateway]
  (let [configuration (:config @gateway)]
    (info "starting gateway with configuration" configuration)
    (swap! gateway (fn [gateway]
                     (let [auths (authenticators configuration)
                           domains [(agm/agm-domain)
                                    (activity/activity-domain)
                                    (global/global-domain auths nil nil {:local-ip "127.0.0.1"})
                                    (bus/bus-domain)
                                    (context/context-domain)
                                    (metrics/metrics-domain environment nil nil)]
                           node (->node configuration domains)
                           clients (atom {})
                           scavenger (atom nil)
                           started (atom true)
                           inactive-seconds (get-in configuration [:clients :inactive_seconds]  60)]
                       (when (pos? inactive-seconds)
                         (timbre/info "clients inactive for" inactive-seconds "will be scavenged")
                         (scavenge-clients! started
                                            scavenger 
                                            clients 
                                            node 
                                            inactive-seconds))
                       (-> gateway
                           (assoc :started? started
                                  :auth auths
                                  :node node
                                  :clients clients
                                  :scavenger scavenger)))))))
(defn stop-auth
  [auth]
  (doseq [a (vals (:available auth))]
    (auth/stop a)))

(defn stop-impl [gateway]
  (info "stopping gateway")
  (swap! gateway (fn [gateway]
                   (swap! (:started? gateway) (constantly false))
                   (some-> (:scavenger gateway)
                           deref
                           (p/cancel!))
                   (some-> (:node gateway)
                           (node/close))
                   (some-> (:auth gateway)
                           (stop-auth))
                   {:config (:config gateway)})))

(defn ^:export create [config]
  (let [gw (atom {:config (-> (js->clj config)
                              (walk/keywordize-keys))})]
    (reify Object
      (start [this]
        (js/Promise. (fn [resolve reject]
                       (start-impl gw)
                       (resolve this))))
      (stop [this]
        (js/Promise. (fn [resolve reject]
                       (stop-impl gw)
                       (resolve this))))
      (connect [this message-cb]
        (local-client/connect gw message-cb)))))


(defn generate-exports [] #js {:create            create
                               :configure_logging configure-logging})

;; (defn count-bahors

;;   []
;;   (when (< (swap! bahors inc) 5)

;;     (exec/schedule! 1000 count-bahors)))

;; (exec/schedule! 1000 count-bahors)