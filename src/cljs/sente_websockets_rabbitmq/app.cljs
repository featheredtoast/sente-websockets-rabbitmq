(ns sente-websockets-rabbitmq.app
  (:require-macros
   [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [com.stuartsierra.component :as component]
            [cljs.core.async :as async :refer (<! >! <! poll! put! chan)]
            [taoensso.sente  :as sente :refer (cb-success?)]
            [system.components.sente :refer [new-channel-socket-client]]
            [rum.core :as rum]
            [cognitect.transit :as transit])
  (:import goog.net.Cookies))

(enable-console-print!)

(defonce app-state (atom {:messages []
                          :typing #{}
                          :user-typing false
                          :input ""
                          :user ""
                          :initializing true}))

(defonce message-chan (chan))

(defn from-json [json]
  (transit/read (transit/reader :json) json))

(defn to-json [data]
  (transit/write (transit/writer :json) data))

(defn chat-init [chsk-send!]
  (chsk-send!
   [:chat/init] ;event
   8000 ; timeout
   (fn [reply])))

(defn send-message [chsk-send! msg type]
  (chsk-send!
   [type {:msg msg}] ;event
   8000 ; timeout
   (fn [reply])))

(defn start-message-sender [chsk-send!]
  (go-loop []
    (let [{:keys [msg type]} (<! message-chan)]
      (if (= type :shutdown)
        (println "shutting down message sender")
        (do
          (println "sending message... " msg)
          (send-message chsk-send! msg type)
          (recur))))))

(defn handle-message [{:keys [msg uid] :as message}]
  (let [new-value-uncut (conj (:messages @app-state) message)
        new-value-count (count new-value-uncut)
        limit 10
        new-value-offset (or (and (< 0 (- new-value-count limit)) limit) new-value-count)
        new-value-cut (- new-value-count new-value-offset)
        new-value (subvec new-value-uncut new-value-cut)]
    (swap! app-state assoc :messages new-value)))

(defn handle-init [payload]
  (swap! app-state assoc :messages payload))

(defn handle-typing [{:keys [uid msg]}]
  (let [typists (:typing @app-state)
        new-typists(if msg
          (conj typists uid)
          (disj typists uid))]
    (swap! app-state assoc :typing new-typists))
  (println "typing notification by " uid " and it is " msg))

(defmulti event-msg-handler (fn [_ msg] (:id msg))) ; Dispatch on event-id
;; Wrap for logging, catching, etc.:
(defn     event-msg-handler* [chsk-send! {:as ev-msg :keys [id ?data event]}]
  (event-msg-handler chsk-send! ev-msg))
(do ; Client-side methods
  (defmethod event-msg-handler :default ; Fallback
    [_ {:as ev-msg :keys [event]}]
    (println "Unhandled event: %s" event))

  (defmethod event-msg-handler :chsk/state
    [_ {:as ev-msg :keys [?data]}]
    (if (= ?data {:first-open? true})
      (println "Channel socket successfully established!")
      (println "Channel socket state change: %s" ?data)))

  (defmethod event-msg-handler :chsk/recv
    [_ {:as ev-msg :keys [?data]}]
    (let [data-map (apply array-map ?data)
          payload (:chat/message data-map)
          init-payload (:chat/init data-map)
          typing (:chat/typing data-map)]
      (or (nil? payload) (handle-message payload))
      (or (nil? init-payload) (handle-init init-payload))
      (or (nil? typing) (handle-typing typing))))

  (defmethod event-msg-handler :chsk/handshake
    [chsk-send! {:as ev-msg :keys [?data]}]
    (let [[?uid ?csrf-token ?handshake-data] ?data]
      (chat-init chsk-send!)))

  ;; Add your (defmethod handle-event-msg! <event-id> [ev-msg] <body>)s here...
  )

(defrecord MessageSendHandler []
  component/Lifecycle
  (start [component]
    component)
  (stop [component]
    (println "sending shutdown message!")
    (put! message-chan {:type :shutdown})
    component))
(defn new-message-send-handler []
  (map->MessageSendHandler {}))

(defrecord SenteHandler [router chsk sente]
  component/Lifecycle
  (start [component]
    (let [{:keys [chsk-send! chsk ch-chsk]} sente]
      (chat-init chsk-send!)
      (start-message-sender chsk-send!)
      (assoc component
             :chsk chsk
             :router
             (sente/start-chsk-router! ch-chsk (partial event-msg-handler* chsk-send!)))))
  (stop [component]
    (when chsk
      (println "disconnecting...")
      (sente/chsk-disconnect! chsk))
    (when-let [stop-f router]
      (println "stopping router...") 
      (stop-f))
    (assoc component
           :chsk nil
           :router nil)))

(defn new-sente-handler []
  (map->SenteHandler {}))

(defn chat-system []
  (component/system-map
   :sente (new-channel-socket-client)
   :sente-handler (component/using
                   (new-sente-handler)
                   [:sente])
   :message-sender (new-message-send-handler)))

(defn send-typing-notification [is-typing]
  (if (not= is-typing (:user-typing @app-state))
    (do
      (swap! app-state assoc :user-typing is-typing)
      (put! message-chan {:type :chat/typing :msg is-typing}))))

(defn input-change [e]
  (let [input (-> e .-target .-value)]
       (send-typing-notification (not= input ""))
       (swap! app-state assoc :input input)))

(defn get-cookie-map []
  (->> (map #(.split % "=") (.split (.-cookie js/document) #";"))
     (map vec)
     (map (fn [key-val] [(keyword (.trim (first key-val))) (.trim (second key-val))]))
     (map (partial apply hash-map))
     (apply merge)))

(defn get-cookie [key]
  (js/decodeURIComponent (.get (goog.net.Cookies. js/document) (name key))))

(defn keywordize-map [value]
  (cond
    (map? value)
    (into {} 
          (for [[k v] value] 
            [(keyword k) (keywordize-map v)]))
    (set? value)
    (into #{}
          (map keywordize-map value))
    (vector? value)
    (into []
          (map keywordize-map value))
    :else
    value))

(defn get-app-state-cookies []
  (let [app-state-raw (from-json (js/decodeURIComponent (clojure.string/replace (get-cookie :app-state) "+" " ")))]
    (keywordize-map app-state-raw)))

(defn submit-message []
  (when-let [msg (and (not= "" (:input @app-state)) (:input @app-state))]
    (put! message-chan {:type :chat/submit :msg msg}))
  (send-typing-notification false)
  (swap! app-state assoc :input ""))

(when (:initializing @app-state)
  (reset! app-state (get-app-state-cookies))
  (swap! app-state assoc :typing (set (:typing @app-state))))