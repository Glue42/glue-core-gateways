(ns gateway.cljs.local-client
  (:require [gateway.cljs.common :as common]

            [clojure.core.async :as a]
            [taoensso.timbre :as timbre]))

(defn- local->source
  [cb gw ch client]
  (a/go-loop []
    (when-let [m (a/<! ch)]
      (try
        (when cb
          (cb client
              (clj->js m)))
        (catch js/Error e (timbre/error e "error while invoking the client callback")))
      (recur)))
  {:type     :local
   :channel  ch
   :endpoint (:local-ip gw "127.0.0.1")})


(defn disconnect-impl
  [gateway channel]
  (when (and gateway channel)
    (let [g @gateway]
      (common/remove-client! (:clients g) (:node g) channel))
    (timbre/info "local client disconnected"))
  (js/Promise.resolve true))

(defn send-impl
  [gateway channel message]
  (when (and gateway channel)
    (timbre/debug "Processing incoming message" message "from local client")
    (let [jmsg (js->clj message)
          g @gateway]
      (common/process-message jmsg (:clients g) (:node g) channel))))


(defn connect
  [g cb]
  (let [gw @g
        ch (a/chan (a/dropping-buffer 100))
        client (reify Object
                 (disconnect [this] (disconnect-impl g ch))
                 (send [this message] (send-impl g ch message)))]
    (timbre/info "local client connected")
    (let [source (local->source cb gw ch client)]
      (common/add-client! (:clients gw) (:node gw) ch source)
      (js/Promise.resolve client))))
