(ns clj-pgp.keyring
  "This namespace handles interactions with PGP keyrings.

  Literal keyring files are directly supported, and key servers and other
  stores can extend the `KeyRing` protocol for further extension."
  (:require
    [byte-streams :as bytes]
    [clj-pgp.core :as pgp])
  (:import
    (org.bouncycastle.openpgp
      PGPPublicKeyRing
      PGPPublicKeyRingCollection
      PGPSecretKeyRing
      PGPSecretKeyRingCollection
      PGPUtil)
    (org.bouncycastle.openpgp.operator.bc
      BcKeyFingerprintCalculator)))


(defprotocol KeyRing
  "Protocol for obtaining PGP keys."

  (list-public-keys [this]
    "Enumerates the available public keys.")

  (list-secret-keys [this]
    "Enumerates the available secret keys.")

  (get-public-key [this id]
    "Loads a public key by id.")

  (get-secret-key [this id]
    "Loads a secret key by id."))


(extend-protocol KeyRing

  PGPPublicKeyRing

  (list-public-keys
    [this]
    (->> this .getPublicKeys iterator-seq))

  (get-public-key
    [this id]
    (.getPublicKey this (pgp/key-id id)))


  PGPPublicKeyRingCollection

  (list-public-keys
    [this]
    (->> this .getKeyRings iterator-seq (map list-public-keys) flatten))

  (get-public-key
    [this id]
    (.getPublicKey this (pgp/key-id id)))


  PGPSecretKeyRing

  (list-public-keys
    [this]
    (->> this .getPublicKeys iterator-seq))

  (list-secret-keys
    [this]
    (->> this .getSecretKeys iterator-seq))

  (get-public-key
    [this id]
    (.getPublicKey this (pgp/key-id id)))

  (get-secret-key
    [this id]
    (.getSecretKey this (pgp/key-id id)))


  PGPSecretKeyRingCollection

  (list-public-keys
    [this]
    (->> this .getKeyRings iterator-seq (map list-public-keys) flatten))

  (list-secret-keys
    [this]
    (->> this .getKeyRings iterator-seq (map list-secret-keys) flatten))

  (get-public-key
    [this id]
    (let [id (pgp/key-id id)]
      (-> this (.getSecretKeyRing id) (.getPublicKey id))))

  (get-secret-key
    [this id]
    (.getSecretKey this (pgp/key-id id))))


(defn load-public-keyring
  "Loads a public keyring collection from a data source."
  [source]
  (with-open [stream (PGPUtil/getDecoderStream
                       (bytes/to-input-stream source))]
    (PGPPublicKeyRingCollection. stream (BcKeyFingerprintCalculator.))))


(defn load-secret-keyring
  "Loads a secret keyring collection from a data source."
  [source]
  (with-open [stream (PGPUtil/getDecoderStream
                       (bytes/to-input-stream source))]
    (PGPSecretKeyRingCollection. stream (BcKeyFingerprintCalculator.))))
