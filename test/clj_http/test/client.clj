(ns clj-http.test.client
  (:use [clojure.test]
        [clj-http.test.core :only [run-server]])
  (:require [clj-http.client :as client]
            [clj-http.util :as util]
            [cheshire.core :as json])
  (:import (java.net UnknownHostException)
           (java.util Arrays)))

(def base-req
  {:scheme :http
   :server-name "localhost"
   :server-port 18080})

(deftest ^{:integration true} roundtrip
  (run-server)
  (Thread/sleep 1000)
  ;; roundtrip with scheme as a keyword
  (let [resp (client/request (merge base-req {:uri "/get" :method :get}))]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (is (= "get" (:body resp))))
  ;; roundtrip with scheme as a string
  (let [resp (client/request (merge base-req {:uri "/get" :method :get
                                              :scheme "http"}))]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (is (= "get" (:body resp)))))

(deftest ^{:integration true} nil-input
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
               (client/get nil)))
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
               (client/post nil)))
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
               (client/put nil)))
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
               (client/delete nil))))


(defn is-passed [middleware req]
  (let [client (middleware identity)]
    (is (= req (client req)))))

(defn is-applied [middleware req-in req-out]
  (let [client (middleware identity)]
    (is (= req-out (client req-in)))))

(deftest redirect-on-get
  (let [client (fn [req]
                 (if (= "foo.com" (:server-name req))
                   {:status 302
                    :headers {"location" "http://bar.com/bat"}}
                   {:status 200
                    :req req}))
        r-client (-> client client/wrap-url client/wrap-redirects)
        resp (r-client {:server-name "foo.com" :url "http://foo.com" :request-method :get})]
    (is (= 200 (:status resp)))
    (is (= :get (:request-method (:req resp))))
    (is (= :http (:scheme (:req resp))))
    (is (= ["http://foo.com" "http://bar.com/bat"] (:trace-redirects resp)))

    (is (= "/bat" (:uri (:req resp))))))

(deftest relative-redirect-on-get
  (let [client (fn [req]
                 (if (:redirects-count req)
                   {:status 200
                    :req req}
                   {:status 302
                    :headers {"location" "/bat"}}))
        r-client (-> client client/wrap-url client/wrap-redirects)
        resp (r-client {:server-name "foo.com" :url "http://foo.com" :request-method :get})]
    (is (= 200 (:status resp)))
    (is (= :get (:request-method (:req resp))))
    (is (= :http (:scheme (:req resp))))
    (is (= ["http://foo.com" "http://foo.com/bat"] (:trace-redirects resp)))

    (is (= "/bat" (:uri (:req resp))))))

(deftest max-redirects
  (let [client (fn [req]
                 (if (= "foo.com" (:server-name req))
                   {:status 302
                    :headers {"location" "http://bar.com/bat"}}
                   {:status 200
                    :req req}))
        r-client (-> client client/wrap-url client/wrap-redirects)
        resp (r-client {:server-name "foo.com" :url "http://foo.com" :request-method
                        :get :max-redirects 0})]
    (is (= 302 (:status resp)))
    (is (= ["http://foo.com"] (:trace-redirects resp)))
    (is (= "http://bar.com/bat" (get (:headers resp) "location")))))

(deftest redirect-to-get-on-head
  (let [client (fn [req]
                 (if (= "foo.com" (:server-name req))
                   {:status 303
                    :headers {"location" "http://bar.com/bat"}}
                   {:status 200
                    :req req}))
        r-client (-> client client/wrap-url client/wrap-redirects)
        resp (r-client {:server-name "foo.com" :url "http://foo.com" :request-method :head})]
    (is (= 200 (:status resp)))
    (is (= :get (:request-method (:req resp))))
    (is (= :http (:scheme (:req resp))))
    (is (= ["http://foo.com" "http://bar.com/bat"] (:trace-redirects resp)))
    (is (= "/bat" (:uri (:req resp))))))

(deftest pass-on-non-redirect
  (let [client (fn [req] {:status 200 :body (:body req)})
        r-client (client/wrap-redirects client)
        resp (r-client {:body "ok" :url "http://foo.com"})]
    (is (= 200 (:status resp)))
    (is (= ["http://foo.com"] (:trace-redirects resp)))
    (is (= "ok" (:body resp)))))

(deftest pass-on-follow-redirects-false
  (let [client (fn [req] {:status 302 :body (:body req)})
        r-client (client/wrap-redirects client)
        resp (r-client {:body "ok" :follow-redirects false})]
    (is (= 302 (:status resp)))
    (is (= "ok" (:body resp)))
    (is (nil? (:trace-redirects resp)))))

(deftest throw-on-exceptional
  (let [client (fn [req] {:status 500})
        e-client (client/wrap-exceptions client)]
    (is (thrown-with-msg? Exception #"500"
          (e-client {}))))
  (let [client (fn [req] {:status 500 :body "foo"})
        e-client (client/wrap-exceptions client)]
    (is (thrown-with-msg? Exception #":body"
          (e-client {:throw-entire-message? true})))))

(deftest pass-on-non-exceptional
  (let [client (fn [req] {:status 200})
        e-client (client/wrap-exceptions client)
        resp (e-client {})]
    (is (= 200 (:status resp)))))

(deftest pass-on-exceptional-when-surpressed
  (let [client (fn [req] {:status 500})
        e-client (client/wrap-exceptions client)
        resp (e-client {:throw-exceptions false})]
    (is (= 500 (:status resp)))))

(deftest apply-on-compressed
  (let [client (fn [req]
                 (is (= "gzip, deflate"
                        (get-in req [:headers "Accept-Encoding"])))
                 {:body (util/gzip (util/utf8-bytes "foofoofoo"))
                  :headers {"Content-Encoding" "gzip"}})
        c-client (client/wrap-decompression client)
        resp (c-client {})]
    (is (= "foofoofoo" (util/utf8-string (:body resp))))))

(deftest apply-on-deflated
  (let [client (fn [req]
                 (is (= "gzip, deflate"
                        (get-in req [:headers "Accept-Encoding"])))
                 {:body (util/deflate (util/utf8-bytes "barbarbar"))
                  :headers {"Content-Encoding" "deflate"}})
        c-client (client/wrap-decompression client)
        resp (c-client {})]
    (is (= "barbarbar" (util/utf8-string (:body resp))))))

(deftest pass-on-non-compressed
  (let [c-client (client/wrap-decompression (fn [req] {:body "foo"}))
        resp (c-client {:uri "/foo"})]
    (is (= "foo" (:body resp)))))

(deftest apply-on-accept
  (is-applied client/wrap-accept
              {:accept :json}
              {:headers {"Accept" "application/json"}}))

(deftest pass-on-no-accept
  (is-passed client/wrap-accept
             {:uri "/foo"}))

(deftest apply-on-accept-encoding
  (is-applied client/wrap-accept-encoding
              {:accept-encoding [:identity :gzip]}
              {:headers {"Accept-Encoding" "identity, gzip"}}))

(deftest pass-on-no-accept-encoding
  (is-passed client/wrap-accept-encoding
             {:uri "/foo"}))

(deftest apply-on-output-coercion
  (let [client (fn [req] {:body (util/utf8-bytes "foo")})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo"})]
    (is (= "foo" (:body resp)))))

(deftest pass-on-no-output-coercion
  (let [client (fn [req] {:body nil})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo"})]
    (is (nil? (:body resp))))
  (let [client (fn [req] {:body :thestream})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo" :as :stream})]
    (is (= :thestream (:body resp))))
  (let [client (fn [req] {:body :thebytes})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo" :as :byte-array})]
    (is (= :thebytes (:body resp)))))

(deftest apply-on-input-coercion
  (let [i-client (client/wrap-input-coercion identity)
        resp (i-client {:body "foo"})
        resp2 (i-client {:body "foo2" :body-encoding "ASCII"})
        data (slurp (.getContent (:body resp)))
        data2 (slurp (.getContent (:body resp2)))]
    (is (= "UTF-8" (:character-encoding resp)))
    (is (= "foo" data))
    (is (= "ASCII" (:character-encoding resp2)))
    (is (= "foo2" data2))))

(deftest pass-on-no-input-coercion
  (is-passed client/wrap-input-coercion
             {:body nil}))

(deftest apply-on-content-type
  (is-applied client/wrap-content-type
              {:content-type :json}
              {:content-type "application/json"}))

(deftest pass-on-no-content-type
  (is-passed client/wrap-content-type
             {:uri "/foo"}))

(deftest apply-on-query-params
  (is-applied client/wrap-query-params
              {:query-params {"foo" "bar" "dir" "<<"}}
              {:query-string "foo=bar&dir=%3C%3C"}))

(deftest pass-on-no-query-params
  (is-passed client/wrap-query-params
             {:uri "/foo"}))

(deftest apply-on-basic-auth
  (is-applied client/wrap-basic-auth
              {:basic-auth ["Aladdin" "open sesame"]}
              {:headers {"Authorization"
                         "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ=="}}))

(deftest pass-on-no-basic-auth
  (is-passed client/wrap-basic-auth
             {:uri "/foo"}))

(deftest apply-on-method
  (let [m-client (client/wrap-method identity)
        echo (m-client {:key :val :method :post})]
    (is (= :val (:key echo)))
    (is (= :post (:request-method echo)))
    (is (not (:method echo)))))

(deftest pass-on-no-method
  (let [m-client (client/wrap-method identity)
        echo (m-client {:key :val})]
    (is (= :val (:key echo)))
    (is (not (:request-method echo)))))

(deftest apply-on-url
  (let [u-client (client/wrap-url identity)
        resp (u-client {:url "http://google.com:8080/foo?bar=bat"})]
    (is (= :http (:scheme resp)))
    (is (= "google.com" (:server-name resp)))
    (is (= 8080 (:server-port resp)))
    (is (= "/foo" (:uri resp)))
    (is (= "bar=bat" (:query-string resp)))))

(deftest pass-on-no-url
  (let [u-client (client/wrap-url identity)
        resp (u-client {:uri "/foo"})]
    (is (= "/foo" (:uri resp)))))

(deftest provide-default-port
  (is (= nil  (-> "http://example.com/" client/parse-url :server-port)))
  (is (= 8080 (-> "http://example.com:8080/" client/parse-url :server-port)))
  (is (= nil  (-> "https://example.com/" client/parse-url :server-port)))
  (is (= 8443 (-> "https://example.com:8443/" client/parse-url :server-port))))

(deftest apply-on-form-params
  (testing "With form params"
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:request-method :post
                              :form-params {:param1 "value1"
                                            :param2 "value2"}})]
      (is (= "param1=value1&param2=value2" (:body resp)))
      (is (= "application/x-www-form-urlencoded" (:content-type resp)))
      (is (not (contains? resp :form-params))))
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:request-method :put
                              :form-params {:param1 "value1"
                                            :param2 "value2"}})]
      (is (= "param1=value1&param2=value2" (:body resp)))
      (is (= "application/x-www-form-urlencoded" (:content-type resp)))
      (is (not (contains? resp :form-params)))))
  (testing "With json form params"
    (let [param-client (client/wrap-form-params identity)
          params {:param1 "value1" :param2 "value2"}
          resp (param-client {:request-method :post
                              :content-type :json
                              :form-params params})]
      (is (= (json/encode params) (:body resp)))
      (is (= "application/json" (:content-type resp)))
      (is (not (contains? resp :form-params))))
    (let [param-client (client/wrap-form-params identity)
          params {:param1 "value1" :param2 "value2"}
          resp (param-client {:request-method :put
                              :content-type :json
                              :form-params params})]
      (is (= (json/encode params) (:body resp)))
      (is (= "application/json" (:content-type resp)))
      (is (not (contains? resp :form-params)))))
  (testing "Ensure it does not affect GET requests"
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:request-method :get
                              :body "untouched"
                              :form-params {:param1 "value1"
                                            :param2 "value2"}})]
      (is (= "untouched" (:body resp)))
      (is (not (contains? resp :content-type)))))

  (testing "with no form params"
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:body "untouched"})]
      (is (= "untouched" (:body resp)))
      (is (not (contains? resp :content-type))))))

(deftest t-ignore-unknown-host
  (is (thrown? UnknownHostException (client/get "http://aorecuf892983a.com")))
  (is (nil? (client/get "http://aorecuf892983a.com"
                        {:ignore-unknown-host? true}))))