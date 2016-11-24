(ns sente-websockets-rabbitmq.db
  (:require
   [com.stuartsierra.component :as component]
   [sente-websockets-rabbitmq.config :refer [get-property]]
   [ragtime.jdbc]
   [ragtime.repl]
   [clojure.java.jdbc :as jdbc]))

(def db-config
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (get-property :db-host)
   :user (get-property :db-user)
   :password (get-property :db-pass)})

(defn get-recent-messages []
  (jdbc/query db-config
              ["select uid, msg from messages order by id DESC LIMIT 10;"]))

(defn insert-message [uid msg]
  (jdbc/insert! db-config :messages
                {:uid uid :msg msg}))

(defrecord Migrate []
  component/Lifecycle
  (start [component]
    (println "migrating...")
    (ragtime.repl/migrate {:datastore
                           (ragtime.jdbc/sql-database db-config)
                           :migrations (ragtime.jdbc/load-resources "migrations")})
    component)
  (stop [component]
    component))

(defn new-migrate []
  (map->Migrate {}))
