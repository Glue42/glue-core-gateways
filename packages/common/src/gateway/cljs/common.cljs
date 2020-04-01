(ns gateway.cljs.common
  (:require [gateway.node :as node]
            [gateway.common.utilities :as util]

            [taoensso.timbre :as timbre]
            [clojure.string :as string]))

(defn now [] (.getTime (js/Date.)))

(defn- valid-message? [message] true)

(defn- transform-incoming [message]
  (let [message (util/keywordize message)]
    (if-let [type (:type message)]
      (assoc message :type (keyword type))
      message)))

(defn process-message
  [message clients node key]
  (if (valid-message? message)
    (let [t (transform-incoming message)]
      (if-let [source (get-in @clients [key :source])]
        (do
          (when-not (= :ping (:type t))
            (node/message node
                          {:origin :local
                           :source source
                           :body   t}))
          (let [n (now)]
            (swap! clients (fn [p] (assoc-in p [key :last-access] n)))))
        (timbre/warn "Cannot process message for not-registered key" key)))
    (timbre/warn "Ignoring invalid message " message)))

(defn- cleanup!
  [source node]
  (util/close-and-flush! (:channel source))
  (try
    (node/remove-source node source)
    (catch js/Error ex (timbre/error ex "Unable to remove client for" key))))

(defn remove-client!
  [clients node key]
  (timbre/info "removing client for" key)
  (let [[o _] (swap-vals! clients dissoc key)]
    (when-let [source (get-in o [key :source])]
      (cleanup! source node))))

(defn add-client!
  [clients node key source]
  (let [n (now)]
    (swap! clients assoc key {:source      source
                              :last-access n}))
  (node/add-source node source))

(defn older-clients
  [f older-than clients]
  (into {} (f (fn [[_ v]] (< (:last-access v) older-than)) clients)))

(defn scavenge-clients!
  [clients node older-than]
  (timbre/debug "running client scavenger. collecting everything older than" older-than)
  (let [[o _] (swap-vals! clients (partial older-clients remove older-than))
        to-remove (older-clients filter older-than o)]
    (doseq [[key v] to-remove
            :let [source (:source v)]]
      (timbre/info "scavenging client for" key)
      (cleanup! source node))))

(defn regexify [v]
  (if (and (string? v)
           (string/starts-with? v "#")
           (pos? (count v)))
    (re-pattern (subs v 1))
    v))
