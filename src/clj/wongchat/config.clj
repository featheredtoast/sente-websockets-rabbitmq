(ns wongchat.config
  (:require [environ.core :refer [env]]))

(def defaults
  {:redis-url "redis://user:pass@localhost:6379"
   :oauth-callback "http://localhost:10555/login"
   :oauth-api-key ""
   :oauth-api-secret ""
   :db-host ""
   :db-user ""
   :db-pass ""
   :amqp-host "localhost"
   :amqp-port 5672
   :amqp-user "guest"
   :amqp-pass "guest"
   :rabbitmq-bigwig-rx-url nil
   :base-url "http://localhost:10555"
   :port 10555})

(defn get-env-map []
  (into {} (filter (fn [kv] (some? (second kv))) (zipmap (keys defaults) (map env (keys defaults))))))

(defn config []
  (merge defaults
         (try (clojure.edn/read-string (slurp (clojure.java.io/resource "config.edn"))) (catch Throwable e {}))
         (get-env-map)))
