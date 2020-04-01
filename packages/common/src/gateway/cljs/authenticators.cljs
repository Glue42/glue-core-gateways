(ns gateway.cljs.authenticators
  (:require             [gateway.auth.impl :refer [AuthImpl] :as impl]
                        [clojure.walk :refer [keywordize-keys]]

                        [promesa.core :as p]
                        [taoensso.timbre :as timbre]))

(deftype JSAuthenticator [auth-fn]
  AuthImpl
  (auth [this authentication-request]
    (-> (auth-fn (clj->js authentication-request))
        (p/then (fn [v] (let [v (-> (js->clj v)
                                    (keywordize-keys)
                                    (update :type keyword))]
                          (timbre/debug "auth response" v)
                          v)))
        (p/catch (fn [v]
                   (timbre/debug "auth failure" v)
                   (throw (ex-info v {:type :failure :message v})))))))

(defn js-authenticator [auth-fn]
  (->JSAuthenticator auth-fn))