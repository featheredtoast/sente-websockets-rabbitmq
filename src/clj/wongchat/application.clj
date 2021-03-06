(ns wongchat.application
  (:require
   [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
   [ring.middleware.gzip :refer [wrap-gzip]]
   [ring.middleware.logger :refer [wrap-with-logger]]
   [com.stuartsierra.component :as component]
   [system.components.http-kit :refer [new-web-server]]
   [system.components.sente :refer [new-channel-sockets sente-routes]]
   [system.components.endpoint :refer [new-endpoint]]
   [system.components.handler :refer [new-handler]]
   [system.components.middleware :refer [new-middleware]]
   [system.components.rabbitmq :refer [new-rabbit-mq]]
   [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]
   [clj-redis-session.core :as redis-session]
   [wongchat.config :refer [config]]
   [wongchat.db :as db]
   [wongchat.routes :refer [routes]]
   [wongchat.events :as events]
   [wongchat.auth :as auth])
  (:gen-class))

(defn get-site-defaults [redis-conn]
  (-> site-defaults
      (assoc :session {:store (redis-session/redis-store redis-conn)})
      (assoc-in [:security :anti-forgery] false)))

(defn get-middleware [redis-url]
  (let [redis-conn {:spec {:uri redis-url}}]
    [[wrap-defaults (get-site-defaults redis-conn)]
     wrap-with-logger
     wrap-gzip]))

(defn app-system [{:keys [rabbitmq-bigwig-rx-url
                          amqp-user amqp-pass amqp-host amqp-port
                          base-url
                          redis-url]
                   :as config}]
  (let [port (Integer. (:port config))
        rabbitmq-uri (or rabbitmq-bigwig-rx-url
                         (str "amqp://" amqp-user ":"
                              amqp-pass "@" amqp-host ":" amqp-port))]
    (println "amqp uri " rabbitmq-uri)
    (component/system-map
     :rabbit-mq (new-rabbit-mq rabbitmq-uri)
     :sente (component/using
             (new-channel-sockets
              events/sente-handler
              sente-web-server-adapter
              {:wrap-component? true
               :user-id-fn auth/get-user-id})
             [:rabbit-mq])
     :post-handler (component/using
                    (events/new-messager)
                    [:sente :rabbit-mq])
     :sente-endpoint (component/using
                      (new-endpoint sente-routes)
                      [:sente])
     :routes (new-endpoint routes)
     :middleware (new-middleware
                  {:middleware (get-middleware redis-url)})
     :handler (component/using
               (new-handler)
               [:sente-endpoint :routes :middleware])
     :http (component/using
            (new-web-server port)
            [:handler]))))

(defn -main [& [port]]
  (db/migrate)
  (component/start (app-system (config))))
