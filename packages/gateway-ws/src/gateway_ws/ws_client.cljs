(ns gateway-ws.ws-client
  (:require [gateway.cljs.common :as common]
            [gateway.common.filters :as f]

            [clojure.string :as string]

            [clojure.core.async :as a]
            [taoensso.timbre :as timbre]
            [ws :as ws]))


(defn- ws->source
  [host-ip ws]
  (let [ch (a/chan)]
    (a/go-loop [m (a/<! ch)]
      (when m
        (try
          (timbre/debug "Sending outgoing message " m " to ws " ws)
          (.send ws (js/JSON.stringify (clj->js m)))
          (catch js/Error ex
            (timbre/error ex "Unable to send message" m)))
        (recur (a/<! ch))))
    {:type     :local
     :channel  ch
     :endpoint host-ip}))


(defn- on-message
  [clients node socket message]

  (timbre/debug "Processing incoming message" message "from socket" socket)
  (try
    (common/process-message (js->clj (js/JSON.parse message))
                            clients
                            node
                            socket)
    (catch js/Error ex
      (.send socket (ex-message ex)))))

(defn- request->host-ip
  [request]
  (let [headers (js->clj (.-headers request))]
    (if-let [forwarded-for (get headers "x-forwarded-for")]
      (-> (string/split forwarded-for #",")
          (first)
          (string/trim))
      (-> request
          (.-connection)
          (.-remoteAddress)
          (js->clj)))))

(defn- on-connection!
  [clients node websocket request]

  (let [host (request->host-ip request)]
    (timbre/info "connection for host" host "established")
    (let [source (ws->source host websocket)]
      (common/add-client! clients node websocket source)))

  (.on websocket "message" (partial on-message clients node websocket))
  (.on websocket "close" (partial common/remove-client! clients node websocket)))


(defn regexify-origin-filters [origin-filters]
  (-> origin-filters
      (update :non-matched (fnil keyword :whitelist))
      (update :missing (fnil keyword :whitelist))
      (update :blacklist #(when % (mapv common/regexify %)))
      (update :whitelist #(when % (mapv common/regexify %)))))

(defn accepts-origin?
  [origin-filters origin]
  (timbre/info "checking origin" origin)
  (cond
    (nil? origin-filters) true
    (nil? origin) (case (:missing origin-filters)
                    :whitelist true
                    :blacklist false
                    false)
    :else (let [result (cond
                         (and (:blacklist origin-filters) (f/values-match? (:blacklist origin-filters) origin))
                         (do
                           (timbre/warn "origin" origin "matches blacklist filter")
                           false)
                         (and (:whitelist origin-filters) (f/values-match? (:whitelist origin-filters) origin))
                         (do
                           (timbre/debug "origin" origin "match whiltelist filter")
                           true)
                         :else nil)]
            (if-not (nil? result)
              result
              (case (:non_matched origin-filters)
                :whitelist true
                :blacklist false
                false)))))

(defn server
  "starts a WebSocket server given a handler and a Node server instance"
  [config clients node {:keys [resolve reject]}]
  (let [p (:port config 3434)
        origin-filters (-> (get-in config [:security :origin_filters])
                           (regexify-origin-filters))]
    (timbre/info "starting web socket server on port" p)
    (timbre/info "origin filters configured as" origin-filters)
    (let [wss (ws/Server. #js{:port         p
                              :verifyClient (fn [request-info]
                                              (let [r (js->clj request-info)]
                                                (if (accepts-origin? origin-filters (get r "origin"))
                                                  (do
                                                    (timbre/info "accepting connection for request" r)
                                                    true)
                                                  (do
                                                    (timbre/warn "dropping connection for request" r)
                                                    false))))})]
      (.on wss "error" (fn [err] (reject err)))
      (.on wss "listening" (fn [_] (resolve true)))
      (.on wss "connection" (partial on-connection! clients node))
      wss)))