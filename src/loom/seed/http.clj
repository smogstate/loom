(ns loom.seed.http
  "HTTP fetch/post tools available from day one."
  (:require [hato.client :as http]
            [cheshire.core :as json]
            [loom.envelope :refer [with-provenance]]))

(defn fetch
  "Fetch a URL and return the body string."
  {:doc "Fetch a URL via HTTP GET. Returns body as string."
   :tags ["http" "fetch" "get"]}
  [ctx url]
  (with-provenance "loom.seed.http/fetch" 1
    (:body (http/get url {:as :string}))))

(defn get-json
  "Fetch a URL and parse the JSON body."
  {:doc "Fetch a URL via HTTP GET and parse JSON response."
   :tags ["http" "json" "get"]}
  [ctx url]
  (with-provenance "loom.seed.http/get-json" 1
    (-> (http/get url {:as :string})
        :body
        (json/parse-string true))))

(defn post-json
  "POST JSON body to url, return parsed JSON response."
  {:doc "POST a JSON payload to a URL. Returns parsed JSON response."
   :tags ["http" "json" "post"]}
  [ctx url body-map]
  (with-provenance "loom.seed.http/post-json" 1
    (-> (http/post url {:content-type :json
                        :body         (json/encode body-map)
                        :as           :string})
        :body
        (json/parse-string true))))
