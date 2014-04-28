(ns mvxcvi.crypto.pgp.data
  (:require
    byte-streams
    [clojure.java.io :as io]
    [clojure.string :as str]
    (mvxcvi.crypto.pgp
      [key :refer [public-key]]
      [tags :as tags]
      [util :refer [hex-str read-pgp-objects]]))
  (:import
    (java.io
      ByteArrayOutputStream
      FilterOutputStream
      InputStream
      OutputStream)
    (java.security
      SecureRandom)
    (org.bouncycastle.bcpg
      ArmoredOutputStream)
    (org.bouncycastle.openpgp
      PGPCompressedData
      PGPCompressedDataGenerator
      PGPEncryptedData
      PGPEncryptedDataGenerator
      PGPEncryptedDataList
      PGPLiteralData
      PGPObjectFactory
      PGPPrivateKey
      PGPUtil)
    (org.bouncycastle.openpgp.operator.bc
      BcPGPDataEncryptorBuilder
      BcPGPDigestCalculatorProvider
      BcPublicKeyDataDecryptorFactory
      BcPublicKeyKeyEncryptionMethodGenerator)))


;; DATA ENCRYPTION

(defn- encryption-wrapper
  ^PGPEncryptedDataGenerator
  [algorithm pubkey]
  (fn [^OutputStream stream]
    (-> (tags/symmetric-key-algorithm algorithm)
        BcPGPDataEncryptorBuilder.
        (.setSecureRandom (SecureRandom.))
        PGPEncryptedDataGenerator.
        (doto (.addMethod (BcPublicKeyKeyEncryptionMethodGenerator. (public-key pubkey))))
        (.open stream 1024))))


(defn encrypt-stream
  "Wraps the given output stream with encryption layers. The data will be
  encrypted with a symmetric algorithm, whose key will be encrypted by the
  given PGP public key.

  Opts may contain:
  - :algorithm    symmetric key algorithm to use
  - :compress     if specified, compress the cleartext with the given algorithm
  - :armor        whether to ascii-encode the output"
  ^OutputStream
  [^OutputStream output
   pubkey
   opts]
  (let [wrap-stream
        (fn [streams wrapper]
          (conj streams (wrapper (last streams))))

        streams
        (->
          (vector output)
          (cond->
            (:armor opts)
            (wrap-stream
              #(ArmoredOutputStream. %))
            (:compress opts)
            (wrap-stream
              #(-> (:compress opts)
                   tags/compression-algorithm
                   PGPCompressedDataGenerator.
                   (.open %))))
          (wrap-stream
            (encryption-wrapper (:algorithm opts :aes-256) pubkey))
          rest reverse)]
    (proxy [FilterOutputStream] [(first streams)]
      (close []
        (->> streams (map #(.close ^OutputStream %)) dorun)))))


(defn encrypt
  "Encrypts the given data source and returns an array of bytes with the
  encrypted value. Opts are as in encrypt-stream."
  ([data pubkey]
   (encrypt data pubkey nil))
  ([data pubkey opt-key opt-val & opts]
   (encrypt data pubkey
            (assoc (apply hash-map opts)
                   opt-key opt-val)))
  ([data pubkey opts]
   (let [buffer (ByteArrayOutputStream.)]
     (with-open [stream (encrypt-stream buffer pubkey opts)]
       (io/copy (byte-streams/to-input-stream data) stream))
     (.toByteArray buffer))))



;; DATA DECRYPTION

(defn- read-encrypted-data
  "Reads a raw input stream to find a PGPEncryptedDataList. Returns a sequence
  of encrypted data objects."
  [^InputStream input]
  (some->
    input
    read-pgp-objects
    (->> (filter (partial instance? PGPEncryptedDataList)))
    first
    .getEncryptedDataObjects
    iterator-seq))


(defn- find-data
  "Finds which of the encrypted data objects in the given list is decryptable
  by a local private key. Returns a vector of the encrypted data and the
  corresponding private key."
  [get-privkey data-list]
  (some #(when-let [privkey (get-privkey (.getKeyID ^PGPEncryptedData %))]
           [% privkey])
        data-list))


(defn decrypt-stream
  "Wraps the given input stream with decryption layers. The get-privkey
  function should accept a key-id and return the corresponding unlocked private
  key, or nil if such a key is not available."
  ^InputStream
  [^InputStream input
   get-privkey]
  (let [[encrypted-data privkey]
        (->>
          input
          PGPUtil/getDecoderStream
          read-encrypted-data
          (find-data get-privkey))]
    (->
      encrypted-data
      (.getDataStream (BcPublicKeyDataDecryptorFactory. privkey))
      read-pgp-objects
      first
      (as-> object
        (if (instance? PGPCompressedData object)
          (-> ^PGPCompressedData object .getDataStream read-pgp-objects first)
          object)
        (if (instance? PGPLiteralData object)
          (.getInputStream ^PGPLiteralData object)
          (throw (IllegalArgumentException.
                   "Encrypted PGP data did not contain a literal data packet.")))))))


(defn decrypt
  "Decrypts the given data source and returns an array of bytes with the
  decrypted value."
  [data get-privkey]
  (let [buffer (ByteArrayOutputStream.)]
    (with-open [stream (decrypt-stream
                         (byte-streams/to-input-stream data)
                         get-privkey)]
      (io/copy stream buffer))
    (.toByteArray buffer)))
