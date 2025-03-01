(ns ^:no-doc datalevin.bits
  "Handle binary encoding, byte buffers, etc."
  (:require
   [datalevin.constants :as c]
   [datalevin.util :as u]
   [datalevin.sparselist :as sl]
   [clojure.string :as s]
   [taoensso.nippy :as nippy])
  (:import
   [java.util Arrays UUID Date Base64 Base64$Decoder Base64$Encoder]
   [java.util.regex Pattern]
   [java.math BigInteger BigDecimal]
   [java.io Writer]
   [java.nio ByteBuffer]
   [java.nio.charset StandardCharsets]
   [java.lang String Character]
   [org.roaringbitmap RoaringBitmap RoaringBitmapWriter]
   [datalevin.utl BitOps BufOps]))

(defonce base64-encoder (.withoutPadding (Base64/getEncoder)))

(defonce base64-decoder (Base64/getDecoder))

(defn encode-base64
  "encode bytes into a base64 string"
  [bs]
  (.encodeToString ^Base64$Encoder base64-encoder bs))

(defn decode-base64
  "decode a base64 string to return the bytes"
  [^String s]
  (.decode ^Base64$Decoder base64-decoder s))

;; bytes <-> text

(def hex [\0 \1 \2 \3 \4 \5 \6 \7 \8 \9 \A \B \C \D \E \F])

(defn hexify-byte
  "Convert a byte to a hex pair"
  [b]
  (let [v (bit-and ^byte b 0xFF)]
    [(hex (bit-shift-right v 4)) (hex (bit-and v 0x0F))]))

(defn hexify
  "Convert bytes to hex string"
  [bs]
  (apply str (mapcat hexify-byte bs)))

(defn ^:no-doc unhexify-2c
  "Convert two hex characters to a byte"
  [c1 c2]
  (unchecked-byte
    (+ (bit-shift-left (Character/digit ^char c1 16) 4)
       (Character/digit ^char c2 16))))

(defn ^:no-doc unhexify
  "Convert hex string to byte sequence"
  [s]
  (map #(apply unhexify-2c %) (partition 2 s)))

(defn ^:no-doc hexify-string [^String s] (hexify (.getBytes s)))

(defn ^:no-doc unhexify-string [s] (String. (byte-array (unhexify s))))

(defn text-ba->str
  "Convert a byte array to string, the array is known to contain text data"
  [^bytes ba]
  (String. ba StandardCharsets/UTF_8))

(defmethod print-method (Class/forName "[B")
  [^bytes bs, ^Writer w]
  (doto w
    (.write "#datalevin/bytes ")
    (.write "\"")
    (.write ^String (encode-base64 bs))
    (.write "\"")))

(defn ^bytes bytes-from-reader [s] (decode-base64 s))

(defmethod print-method Pattern
  [^Pattern p, ^Writer w]
  (doto w
    (.write "#datalevin/regex ")
    (.write "\"")
    (.write ^String (s/escape (.toString p) {\\ "\\\\"}))
    (.write "\"")))

(defn ^Pattern regex-from-reader [s] (Pattern/compile s))

(defmethod print-method BigInteger
  [^BigInteger bi, ^Writer w]
  (doto w
    (.write "#datalevin/bigint ")
    (.write "\"")
    (.write ^String (.toString bi))
    (.write "\"")))

(defn ^BigInteger bigint-from-reader [^String s] (BigInteger. s))

;; nippy

(defn serialize
  "Serialize data to bytes"
  [x]
  (nippy/fast-freeze x))

(defn deserialize
  "Deserialize from bytes. "
  [bs]
  (binding [nippy/*thaw-serializable-allowlist*
            (into nippy/*thaw-serializable-allowlist*
                  c/*data-serializable-classes*)]
    (nippy/fast-thaw bs)))

;; bitmap

(defn bitmap
  "Create a roaringbitmap. Expect a sorted integer collection"
  ([]
   (RoaringBitmap.))
  ([ints]
   (let [^RoaringBitmapWriter writer (-> (RoaringBitmapWriter/writer)
                                         (.initialCapacity (count ints))
                                         (.get))]
     (doseq [^int i ints] (.add writer i))
     (.get writer))))

(defn bitmap-del
  "Delete an int from the bitmap"
  [^RoaringBitmap bm i]
  (.remove bm ^int i)
  bm)

(defn bitmap-add
  "Add an int from the bitmap"
  [^RoaringBitmap bm i]
  (.add bm ^int i)
  bm)

(defn- get-bitmap
  [^ByteBuffer bf]
  (let [bm (RoaringBitmap.)] (.deserialize bm bf) bm))

(defn- put-bitmap [^ByteBuffer bf ^RoaringBitmap x] (.serialize x bf))

;; byte buffer

(defn ^ByteBuffer allocate-buffer
  "Allocate JVM off-heap ByteBuffer in the Datalevin expected endian order"
  [size]
  (ByteBuffer/allocateDirect size))

(defn buffer-transfer
  "Transfer content from one bytebuffer to another"
  ([^ByteBuffer src ^ByteBuffer dst]
   (.put dst src))
  ([^ByteBuffer src ^ByteBuffer dst n]
   (dotimes [_ n] (.put dst (.get src)))))

(defn compare-buffer
  ^long [^ByteBuffer b1 ^ByteBuffer b2]
  (BufOps/compareByteBuf b1 b2))


(defn- get-long
  "Get a long from a ByteBuffer"
  [^ByteBuffer bb]
  (.getLong bb))

(defn get-int
  "Get an int from a ByteBuffer"
  [^ByteBuffer bb]
  (.getInt bb))

(defn get-short
  "Get a short from a ByteBuffer"
  [^ByteBuffer bb]
  (.getShort bb))

(defn- get-byte
  "Get a byte from a ByteBuffer"
  [^ByteBuffer bb]
  (.get bb))

(defn get-bytes
  "Copy content from a ByteBuffer to a byte array, useful for
   e.g. read txn result, as buffer content is gone when txn is done"
  ([^ByteBuffer bb]
   (let [n  (.remaining bb)
         ba (byte-array n)]
     (.get bb ba)
     ba))
  ([^ByteBuffer bb n]
   (let [ba (byte-array n)]
     (.get bb ba 0 n)
     ba)))

(defn- get-bytes-val
  [^ByteBuffer bf ^long post-v]
  (get-bytes bf (- (.remaining bf) post-v)))

(defn- get-data
  "Read data from a ByteBuffer"
  ([^ByteBuffer bb]
   (when-let [bs (get-bytes bb)]
     (deserialize bs)))
  ([^ByteBuffer bb post-v]
   (when-let [bs (get-bytes-val bb post-v)] (deserialize bs))))

(def ^:no-doc ^:const float-sign-idx 31)
(def ^:no-doc ^:const double-sign-idx 63)
(def ^:no-doc ^:const instant-sign-idx 63)

(defn- code-instant [^long x] (bit-flip x instant-sign-idx))

(defn- get-instant
  [^ByteBuffer bf]
  (Date. ^long (code-instant (.getLong bf))))

(defn- put-instant
  [^ByteBuffer bf x]
  (.putLong bf (code-instant
                 (if (inst? x)
                   (inst-ms x)
                   (u/raise "Expect an inst? value" {:value x})))))

(defn- decode-float
  [x]
  (if (bit-test x float-sign-idx)
    (Float/intBitsToFloat (BitOps/intFlip x float-sign-idx))
    (Float/intBitsToFloat (BitOps/intNot ^int x))))

(defn- get-float
  [^ByteBuffer bb]
  (decode-float (.getInt bb)))

(defn- decode-double
  [x]
  (if (bit-test x double-sign-idx)
    (Double/longBitsToDouble (bit-flip x double-sign-idx))
    (Double/longBitsToDouble (bit-not ^long x))))

(defn- get-double
  [^ByteBuffer bb]
  (decode-double (.getLong bb)))

(defn boolean-value
  "Datalevin's boolean value in byte"
  [b]
  (case (short b)
    2 true
    1 false
    (u/raise "Illegal value, expecting a Datalevin boolean value" {})))

(defn- get-boolean [^ByteBuffer bb] (boolean-value (get-byte bb)))

(defn- put-long [^ByteBuffer bb n] (.putLong bb ^long n))

(defn- encode-float
  [x]
  (assert (not (Float/isNaN x)) "Cannot index NaN")
  (let [x (if (= x -0.0) 0.0 x)]
    (if (neg? ^float x)
      (BitOps/intNot (Float/floatToIntBits x))
      (BitOps/intFlip (Float/floatToIntBits x) ^long float-sign-idx))))

(defn- put-float [^ByteBuffer bb x] (.putInt bb (encode-float (float x))))

(defn- encode-double
  [^double x]
  (assert (not (Double/isNaN x)) "Cannot index NaN")
  (let [x (if (= x -0.0) 0.0 x)]
    (if (neg? x)
      (bit-not (Double/doubleToLongBits x))
      (bit-flip (Double/doubleToLongBits x) double-sign-idx))))

(defn- put-double [^ByteBuffer bb x] (.putLong bb (encode-double (double x))))

(defn put-int [^ByteBuffer bb n] (.putInt bb ^int (int n)))

(defn put-short [^ByteBuffer bb n] (.putShort bb ^short (short n)))

(defn put-bytes [^ByteBuffer bb ^bytes bs] (.put bb bs))

(defn- put-byte [^ByteBuffer bb b] (.put bb ^byte (unchecked-byte b)))

(defn encode-bigint
  [^BigInteger x]
  (let [bs (.toByteArray x)
        n  (alength bs)]
    (assert (< n 128)
            "Does not support integer beyond the range of [-2^1015, 2^1015-1]")
    (let [bs1 (byte-array (inc n))]
      (aset bs1 0 (byte (if (= (.signum x) -1)
                          (- 127 n)
                          (bit-flip n 7))))
      (System/arraycopy bs 0 bs1 1 n)
      bs1)))

(defn- put-bigint
  [^ByteBuffer bb ^BigInteger x]
  (put-bytes bb (encode-bigint x)))

(defn ^BigInteger decode-bigint
  [^bytes bs]
  (BigInteger. ^bytes (Arrays/copyOfRange bs 1 (alength bs))))

(defn- get-bigint
  [^ByteBuffer bb]
  (let [^byte b   (get-byte bb)
        n         (if (bit-test b 7) (byte (bit-flip b 7)) (byte (- 127 b)))
        ^bytes bs (get-bytes bb n)]
    (BigInteger. bs)))

(defn- put-bigdec
  [^ByteBuffer bb ^BigDecimal x]
  (put-double bb (.doubleValue x))
  (put-bigint bb (.unscaledValue x))
  (put-byte bb c/separator)
  (put-int bb (.scale x)))

(defn- encode-bigdec
  [^BigDecimal x]
  (let [^BigInteger v (.unscaledValue x)
        bs            (.toByteArray v)
        n             (alength bs)]
    (assert (< n 128)
            "Unscaled value of big decimal cannot go beyond the range of
             [-2^1015, 2^1015-1]")
    (let [bf (ByteBuffer/allocate (+ n 14))]
      (put-bigdec bf x)
      (.array bf))))

(defn- get-bigdec
  [^ByteBuffer bb]
  (get-double bb)
  (let [^BigInteger value (get-bigint bb)
        _                 (get-byte bb)
        ^int scale        (get-int bb)]
    (BigDecimal. value scale)))

(defn- put-data [^ByteBuffer bb x] (put-bytes bb (serialize x)))


(defn- put-uuid
  [bf ^UUID val]
  (put-long bf (.getMostSignificantBits val))
  (put-long bf (.getLeastSignificantBits val)))

(defn- get-uuid [bf] (UUID. (get-long bf) (get-long bf)))

(defn- sep->slash
  [^bytes bs]
  (let [n-1 (dec (alength bs))]
    (loop [i 0]
      (cond
        (= (aget bs i) c/separator) (aset-byte bs i c/slash)
        (< i n-1)                   (recur (inc i)))))
  bs)

(defn- get-string
  ([^ByteBuffer bf]
   (String. ^bytes (get-bytes bf) StandardCharsets/UTF_8))
  ([^ByteBuffer bf ^long post-v]
   (String. ^bytes (get-bytes-val bf post-v) StandardCharsets/UTF_8)))

(defn- get-key-sym-str
  [^ByteBuffer bf ^long post-v]
  (let [^bytes ba (sep->slash (get-bytes-val bf post-v))
        s         (String. ^bytes ba StandardCharsets/UTF_8)]
    (if (= (aget ba 0) c/slash) (subs s 1) s)))

(defn- get-keyword
  [^ByteBuffer bf ^long post-v]
  (keyword (get-key-sym-str bf post-v)))

(defn- get-symbol
  [^ByteBuffer bf ^long post-v]
  (symbol (get-key-sym-str bf post-v)))

;; bytes

(defn type-size
  "Return the storage size (bytes) of the fixed length data type (a keyword).
  Useful when calling `open-dbi`. E.g. return 9 for `:long` (i.e. 8 bytes plus
  an 1 byte header). Return nil if the given data type has no fixed size."
  [type]
  (case type
    :int     4
    :long    9
    :id      8
    :float   5
    :double  9
    :byte    1
    :boolean 2
    :instant 9
    :uuid    17
    nil))

(defn measure-size
  "measure size of x in number of bytes"
  [x]
  (cond
    (bytes? x)         (inc (alength ^bytes x))
    (int? x)           8
    (instance? Byte x) 1
    :else              (alength ^bytes (serialize x))))

;; index

(defmacro wrap-extrema
  [v vmin vmax b]
  `(if (keyword? ~v)
     (condp = ~v
       c/v0   ~vmin
       c/vmax ~vmax
       (u/raise "Expect other data types, got keyword instead" ~v {}))
     ~b))

(defn- string-bytes
  [^String v]
  (wrap-extrema
    v c/min-bytes c/max-bytes (.getBytes v StandardCharsets/UTF_8)))

(defn- key-sym-bytes
  [x]
  (let [^bytes nmb (string-bytes (name x))
        nml        (alength nmb)]
    (if-let [ns (namespace x)]
      (let [^bytes nsb (string-bytes ns)
            nsl        (alength nsb)
            res        (byte-array (+ nsl nml 1))]
        (System/arraycopy nsb 0 res 0 nsl)
        (System/arraycopy c/separator-ba 0 res nsl 1)
        (System/arraycopy nmb 0 res (inc nsl) nml)
        res)
      (let [res (byte-array (inc nml))]
        (System/arraycopy c/separator-ba 0 res 0 1)
        (System/arraycopy nmb 0 res 1 nml)
        res))))

(defn- keyword-bytes
  [x]
  (condp = x
    c/v0   c/min-bytes
    c/vmax c/max-bytes
    (key-sym-bytes x)))

(defn- symbol-bytes
  [x]
  (wrap-extrema x c/min-bytes c/max-bytes (key-sym-bytes x)))

(defn- data-bytes
  [v]
  (condp = v
    c/v0   c/min-bytes
    c/vmax c/max-bytes
    (serialize v)))

(defn- bigint-bytes
  [v]
  (condp = v
    c/v0   c/min-bytes
    c/vmax c/max-bytes
    (encode-bigint v)))

(defn- bigdec-bytes
  [v]
  (condp = v
    c/v0   c/min-bytes
    c/vmax c/max-bytes
    (encode-bigdec v)))

(defn- get-sparse-list
  [bf]
  (let [sl (sl/sparse-arraylist)]
    (sl/deserialize sl bf)
    sl))

(defn- get-tuple-sizes
  [^ByteBuffer bf ^long c ^long post-v]
  (let [ss (volatile! [])]
    (.position bf (- (.limit bf) c post-v))
    (dotimes [_ c] (vswap! ss conj (short (get-byte bf))))
    @ss))

(defn- long-header
  [v]
  (if (keyword? v)
    (condp = v
      c/v0   c/type-long-neg
      c/vmax c/type-long-pos
      (u/raise "Expecting long, got keyword" v {}))
    (if (neg? ^long v) c/type-long-neg c/type-long-pos)))

(defn- raw-header
  [v t]
  (case t
    :keyword c/type-keyword
    :symbol  c/type-symbol
    :string  c/type-string
    :boolean c/type-boolean
    :float   c/type-float
    :double  c/type-double
    :instant c/type-instant
    :uuid    c/type-uuid
    :bytes   c/type-bytes
    :bigint  c/type-bigint
    :bigdec  c/type-bigdec
    :long    (long-header v)
    nil))

(def known-size-types #{:boolean :long :double :float :instant :uuid})

(defn- put-fixed
  [bf v hdr]
  (case (short hdr)
    -64 (put-long bf (wrap-extrema v Long/MIN_VALUE -1 v))
    -63 (put-long bf (wrap-extrema v 0 Long/MAX_VALUE v))
    -11 (put-float bf (wrap-extrema v Float/NEGATIVE_INFINITY
                                    Float/POSITIVE_INFINITY v))
    -10 (put-double bf (wrap-extrema v Double/NEGATIVE_INFINITY
                                     Double/POSITIVE_INFINITY v))
    -9  (put-long bf (code-instant
                       (wrap-extrema v Long/MIN_VALUE Long/MAX_VALUE
                                     (.getTime ^Date v))))
    -8  (put-long bf (wrap-extrema v c/e0 Long/MAX_VALUE v))
    -7  (put-uuid bf (wrap-extrema v c/min-uuid c/max-uuid v))
    -3  (put-byte bf (wrap-extrema v c/false-value c/true-value
                                   (if v c/true-value c/false-value)))))

(defn- put-varied
  [bf v hdr]
  (case (short hdr)
    -2  (put-bytes bf (wrap-extrema v c/min-bytes c/max-bytes v))
    -4  (put-bytes bf (symbol-bytes v))
    -5  (put-bytes bf (keyword-bytes v))
    -6  (put-bytes bf (string-bytes v))
    -14 (put-bytes bf (bigdec-bytes v))
    -15 (put-bytes bf (bigint-bytes v))))

(defn put-homo-tuple
  "x-type is a single raw value type"
  [^ByteBuffer bf x x-type]
  (let [c   (count x)
        f   (nth x 0)
        hdr (raw-header f x-type)]
    (put-byte bf hdr)
    (put-byte bf (unchecked-byte c))
    (if (known-size-types x-type)
      (dotimes [i c] (put-fixed bf (nth x i) hdr))
      (let [c-1   ^long (dec c)
            sizes (loop [ss [] i 0 cp (.position bf)]
                    (if (< i c)
                      (let [v (nth x i)]
                        (put-varied bf v hdr)
                        (let [np   (.position bf)
                              size (- np cp)]
                          (when (< 255 size)
                            (u/raise "The maximal tuple element size is
                              255 bytes" {:too-large v}))
                          (when (< i c-1) (put-byte bf c/separator))
                          (recur (conj ss size) (inc i) (inc np))))
                      ss))]
        (put-byte bf c/truncator)
        (doseq [s sizes] (put-byte bf (unchecked-byte s)))))))

(defn put-hete-tuple
  "x-type is a vector of raw value types"
  [^ByteBuffer bf x x-type]
  (let [c (count x-type)]
    (put-byte bf (unchecked-byte c))
    (let [c-1   ^long (dec c)
          sizes (loop [ss [] i 0 cp (.position bf)]
                  (if (< i c)
                    (let [v     (nth x i)
                          t     (nth x-type i)
                          hdr   (raw-header v t)
                          known (known-size-types t)]
                      (put-byte bf hdr)
                      (if known (put-fixed bf v hdr) (put-varied bf v hdr))
                      (when (and (not known) (< i c-1))
                        (put-byte bf c/separator))
                      (let [np   (.position bf)
                            size (- np cp)]
                        (when (< 255 size)
                          (u/raise "The maximal tuple element size is
                              255 bytes" {:too-large v}))
                        (recur (conj ss size) (inc i) np)))
                    ss))]
      (put-byte bf c/truncator)
      (doseq [s sizes] (put-byte bf (unchecked-byte s))))))

(defn- header->type
  [header]
  (case (short header)
    (-64 -63 -8) :long
    -15          :bigint
    -14          :bigdec
    -11          :float
    -10          :double
    -9           :instant
    -7           :uuid
    -6           :string
    -5           :keyword
    -4           :symbol
    -3           :boolean
    -2           :bytes))

(declare get-value*)

(defn- get-homo-tuple
  ([bf] (get-homo-tuple bf 0))
  ([^ByteBuffer bf ^long outer-post-v]
   (let [hdr   (get-byte bf)
         c     (short (get-byte bf))
         c-1   (dec c)
         tuple (volatile! [])]
     (if (known-size-types (header->type hdr))
       (dotimes [_ c] (vswap! tuple conj (get-value* bf hdr)))
       (let [op      (.position bf)
             post-vs (->> (get-tuple-sizes bf c outer-post-v)
                          reverse
                          (reduce
                            (fn [vs s]
                              (conj vs (+ ^long s (inc ^long (first vs)))))
                            '(0))
                          rest
                          (map #(+ ^long % c 1 outer-post-v))
                          vec)
             np      (.position bf)]
         (.position bf op)
         (dotimes [i c]
           (vswap! tuple conj (get-value* bf (nth post-vs i) hdr))
           (when-not (= i c-1) (get-byte bf)))
         (.position bf np)))
     @tuple)))

(defn- get-hete-tuple
  ([bf] (get-hete-tuple bf 0))
  ([^ByteBuffer bf ^long outer-post-v]
   (let [c       (short (get-byte bf))
         c-1     (dec c)
         tuple   (volatile! [])
         op      (.position bf)
         post-vs (->> (get-tuple-sizes bf c outer-post-v)
                      reverse
                      (reduce
                        (fn [vs s] (conj vs (+ ^long s ^long (first vs))))
                        '(0))
                      rest
                      (map #(+ ^long % c 1 outer-post-v))
                      vec)
         np      (.position bf)]
     (.position bf op)
     (dotimes [i c]
       (let [hdr    (get-byte bf)
             known  (known-size-types (header->type hdr))
             post-v (if (or known (= i c-1))
                      (nth post-vs i)
                      (inc ^long (nth post-vs i)))]
         (vswap! tuple conj (get-value* bf post-v hdr))
         (when-not known (get-byte bf))))
     (.position bf np)
     @tuple)))

(defonce ^ByteBuffer tuple-bf (ByteBuffer/wrap (byte-array c/+max-key-size+)))

(defn- dl-type->raw
  [t]
  (get {:db.type/string  :string
        :db.type/bigdec  :bigdec
        :db.type/bigint  :bigint
        :db.type/boolean :boolean
        :db.type/bytes   :bytes
        :db.type/double  :double
        :db.type/float   :float
        :db.type/long    :long
        :db.type/ref     :long
        :db.type/instant :instant
        :db.type/uuid    :uuid
        :db.type/keyword :keyword
        :db.type/symbol  :symbol} t t))

(defn- tuple-bytes
  [v t]
  (wrap-extrema
    v c/min-bytes c/max-bytes
    (let [t (into [] (map dl-type->raw) t)]
      (.clear tuple-bf)
      (if (= 1 (count t))
        (put-homo-tuple tuple-bf v (nth t 0))
        (put-hete-tuple tuple-bf v t))
      (Arrays/copyOfRange (.array tuple-bf) 0 (.position tuple-bf)))))

(defn- val-bytes
  "Turn datalog value into bytes according to :db/valueType"
  [v t]
  (case t
    (:db.type/boolean :db.type/long :db.type/double :db.type/float
                      :db.type/ref :db.type/instant :db.type/uuid) nil

    :db.type/keyword (keyword-bytes v)
    :db.type/symbol  (symbol-bytes v)
    :db.type/string  (string-bytes v)
    :db.type/bigint  (bigint-bytes v)
    :db.type/bigdec  (bigdec-bytes v)
    :db.type/bytes   (wrap-extrema v c/min-bytes c/max-bytes v)
    (if (vector? t) (tuple-bytes v t) (data-bytes v))))

(defn- val-header
  [v t]
  (case t
    :db.type/keyword c/type-keyword
    :db.type/symbol  c/type-symbol
    :db.type/string  c/type-string
    :db.type/boolean c/type-boolean
    :db.type/float   c/type-float
    :db.type/double  c/type-double
    :db.type/ref     c/type-ref
    :db.type/instant c/type-instant
    :db.type/uuid    c/type-uuid
    :db.type/bytes   c/type-bytes
    :db.type/bigint  c/type-bigint
    :db.type/bigdec  c/type-bigdec
    :db.type/long    (long-header v)
    (when (vector? t)
      (if (= 1 (count t)) c/type-homo-tuple c/type-hete-tuple))))

(defn- get-value*
  ([bf hdr] (get-value* bf 0 hdr))
  ([^ByteBuffer bf post-v hdr]
   (case (short hdr)
     -64 (get-long bf)
     -63 (get-long bf)
     -15 (get-bigint bf)
     -14 (get-bigdec bf)
     -13 (get-homo-tuple bf post-v)
     -12 (get-hete-tuple bf post-v)
     -11 (get-float bf)
     -10 (get-double bf)
     -9  (get-instant bf)
     -8  (get-long bf)
     -7  (get-uuid bf)
     -6  (get-string bf post-v)
     -5  (get-keyword bf post-v)
     -4  (get-symbol bf post-v)
     -3  (get-boolean bf)
     -2  (get-bytes-val bf post-v)
     (do (.reset bf)
         (get-data bf post-v)))))

(defn- get-value
  [^ByteBuffer bf post-v]
  (.mark bf)
  (get-value* bf post-v (get-byte bf)))

(defn- put-attr
  "NB. not going to do range query on attr names"
  [bf x]
  (let [^bytes a (-> x str (subs 1) string-bytes)
        al       (alength a)]
    (assert (<= al c/+max-key-size+)
            "Attribute cannot be longer than 511 bytes")
    (put-bytes bf a)))

(defn- get-attr
  [bf]
  (let [^bytes bs (get-bytes bf)]
    (keyword (String. bs StandardCharsets/UTF_8))))

(deftype Indexable [e a v f b h])

(defn pr-indexable [^Indexable i]
  [(.-e i) (.-a i) (.-v i) (.-f i) (hexify (.-b i)) (.-h i)])

(defmethod print-method Indexable
  [^Indexable i, ^Writer w]
  (.write w "#datalevin/Indexable ")
  (.write w (pr-str (pr-indexable i))))

(defn indexable
  "Turn datom parts into a form that is suitable for putting in indices,
  where aid is the integer id of an attribute, vt is its :db/valueType"
  [eid aid val vt]
  (let [hdr (val-header val vt)]
    (if-let [vb (val-bytes val vt)]
      (let [bl   (alength ^bytes vb)
            cut? (> bl c/+val-bytes-wo-hdr+)
            bas  (if cut?
                   (Arrays/copyOf ^bytes vb c/+val-bytes-trunc+)
                   vb)
            hsh  (when cut? (hash val))]
        (Indexable. eid aid val hdr bas hsh))
      (Indexable. eid aid val hdr nil nil))))

(defn giant? [^Indexable i] (.-h i))

(defn- put-eav
  [bf ^Indexable x]
  (put-long bf (.-e x))
  (put-int bf (.-a x))
  (when-let [hdr (.-f x)] (put-byte bf hdr))
  (if-let [bs (.-b x)]
    (do (put-bytes bf bs)
        (when (.-h x) (put-byte bf c/truncator)))
    (put-fixed bf (.-v x) (.-f x)))
  (put-byte bf c/separator)
  (when-let [h (.-h x)] (put-int bf h)))

(defn- put-ave
  [bf ^Indexable x]
  (put-int bf (.-a x))
  (when-let [hdr (.-f x)] (put-byte bf hdr))
  (if-let [bs (.-b x)]
    (do (put-bytes bf bs)
        (when (.-h x) (put-byte bf c/truncator)))
    (put-fixed bf (.-v x) (.-f x)))
  (put-byte bf c/separator)
  (put-long bf (.-e x))
  (when-let [h (.-h x)] (put-int bf h)))

(defn- put-vea
  [bf ^Indexable x]
  (put-fixed bf (.-v x) (.-f x))
  (put-long bf (.-e x))
  (put-int bf (.-a x)))

(deftype Retrieved [e a v])

(def ^:const overflown-key (Retrieved. c/e0 c/overflown c/overflown))

(defn- indexable->retrieved
  [^Indexable i]
  (Retrieved. (.-e i) (.-a i) (.-v i)))

(defn expected-return
  "Given what's put in, return the expected output from storage"
  [x x-type]
  (case x-type
    :eav  (indexable->retrieved x)
    :eavt (indexable->retrieved x)
    :ave  (indexable->retrieved x)
    :avet (indexable->retrieved x)
    :vea  (indexable->retrieved x)
    :veat (indexable->retrieved x)
    x))

(defn- get-eav
  [bf]
  (let [e (get-long bf)
        a (get-int bf)
        v (get-value bf 1)]
    (Retrieved. e a v)))

(defn- get-ave
  [bf]
  (let [a (get-int bf)
        v (get-value bf 9)
        _ (get-byte bf)
        e (get-long bf)]
    (Retrieved. e a v)))

(defn- get-vea
  [bf]
  (let [v (get-long bf)
        e (get-long bf)
        a (get-int bf)]
    (Retrieved. e a v)))

(defn put-buffer
  ([bf x]
   (put-buffer bf x :data))
  ([^ByteBuffer bf x x-type]
   (case x-type
     ;; user facing
     :string  (do (put-byte bf (raw-header x :string))
                  (put-bytes
                    bf (.getBytes ^String x StandardCharsets/UTF_8)))
     :bigint  (do (put-byte bf (raw-header x :bigint))
                  (put-bigint bf x))
     :bigdec  (do (put-byte bf (raw-header x :bigdec))
                  (put-bigdec bf x))
     :long    (do (put-byte bf (raw-header x :long))
                  (put-long bf x))
     :float   (do (put-byte bf (raw-header x :float))
                  (put-float bf x))
     :double  (do (put-byte bf (raw-header x :double))
                  (put-double bf x))
     :bytes   (do (put-byte bf (raw-header x :bytes))
                  (put-bytes bf x))
     :keyword (do (put-byte bf (raw-header x :keyword))
                  (put-bytes bf (key-sym-bytes x)))
     :symbol  (do (put-byte bf (raw-header x :symbol))
                  (put-bytes bf (key-sym-bytes x)))
     :boolean (do (put-byte bf (raw-header x :boolean))
                  (put-byte bf (if x c/true-value c/false-value)))
     :instant (do (put-byte bf (raw-header x :instant))
                  (put-instant bf x))
     :uuid    (do (put-byte bf (raw-header x :uuid))
                  (put-uuid bf x))

     ;; internal use
     :instant-pre-06 (do (put-byte bf (raw-header x :instant))
                         (.putLong bf (.getTime ^Date x)))
     :byte           (put-byte bf x)
     :short          (put-short bf x)
     :int            (put-int bf x)
     :id             (put-long bf x)
     :int-int        (let [[i1 i2] x]
                       (put-int bf i1)
                       (put-int bf i2))
     :ints           (sl/put-ints bf x)
     :bitmap         (put-bitmap bf x)
     :term-info      (let [[i1 i2 i3] x]
                       (put-int bf i1)
                       (.putFloat bf (float i2))
                       (sl/serialize i3 bf))
     :doc-info       (let [[i1 i2 i3] x]
                       (put-int bf i1)
                       (put-short bf i2)
                       (sl/put-ints bf i3))
     :pos-info       (let [[i1 i2] x]
                       (sl/put-sorted-ints bf i1)
                       (sl/put-sorted-ints bf i2))
     :attr           (put-attr bf x)
     :raw            (put-bytes bf x)
     (:eav :eavt)    (put-eav bf x)
     (:ave :avet)    (put-ave bf x)
     (:vea :veat)    (put-vea bf x)

     (if (vector? x-type)
       (if (= 1 (count x-type))
         (do (put-byte bf c/type-homo-tuple)
             (put-homo-tuple bf x (nth x-type 0)))
         (do (put-byte bf c/type-hete-tuple)
             (put-hete-tuple bf x x-type)))
       (put-data bf x)))))

(defn read-buffer
  ([bf]
   (read-buffer bf :data))
  ([^ByteBuffer bf v-type]
   (case v-type
     ;; user facing
     :string  (do (get-byte bf) (get-string bf))
     :bigint  (do (get-byte bf) (get-bigint bf))
     :bigdec  (do (get-byte bf) (get-bigdec bf))
     :long    (do (get-byte bf) (get-long bf))
     :float   (do (get-byte bf) (get-float bf))
     :double  (do (get-byte bf) (get-double bf))
     :bytes   (do (get-byte bf) (get-bytes bf))
     :keyword (do (get-byte bf) (get-keyword bf 0))
     :symbol  (do (get-byte bf) (get-symbol bf 0))
     :boolean (do (get-byte bf) (get-boolean bf))
     :instant (do (get-byte bf) (get-instant bf))
     :uuid    (do (get-byte bf) (get-uuid bf))

     ;; internal use
     :instant-pre-06 (do (get-byte bf) (Date. (.getLong bf)))
     :id             (get-long bf)
     :short          (get-short bf)
     :int            (get-int bf)
     :attr           (get-attr bf)
     (:eav :eavt)    (get-eav bf)
     (:ave :avet)    (get-ave bf)
     (:vea :veat)    (get-vea bf)
     :byte           (get-byte bf)
     :raw            (get-bytes bf)

     ;; range query are NOT supported on these
     :int-int   [(get-int bf) (get-int bf)]
     :ints      (sl/get-ints bf)
     :bitmap    (get-bitmap bf)
     :term-info [(get-int bf) (.getFloat bf) (get-sparse-list bf)]
     :doc-info  [(get-int bf) (get-short bf) (sl/get-ints bf)]
     :pos-info  [(sl/get-sorted-ints bf) (sl/get-sorted-ints bf)]

     (if (vector? v-type)
       (do (get-byte bf)
           (if (= 1 (count v-type))
             (get-homo-tuple bf)
             (get-hete-tuple bf)))
       (get-data bf)))))

(defn- valid-data*
  [x t]
  (if (or (c/datalog-value-types t) (c/kv-value-types t))
    (case t
      (:db.type/string :string)   (string? x)
      (:db.type/bigint :bigint)   (and (instance? java.math.BigInteger x)
                                       (<= c/min-bigint x c/max-bigint))
      (:db.type/bigdec :bigdec)   (and (instance? java.math.BigDecimal x)
                                       (<= c/min-bigdec x c/max-bigdec))
      (:db.type/long :db.type/ref
                     :long)       (int? x)
      (:db.type/float :float)     (and (float? x)
                                       (<= Float/MIN_VALUE x
                                           Float/MAX_VALUE))
      (:db.type/double :double)   (float? x)
      (:db.type/bytes :bytes)     (bytes? x)
      (:db.type/keyword :keyword) (keyword? x)
      (:db.type/symbol :symbol)   (symbol? x)
      (:db.type/boolean :boolean) (boolean? x)
      (:db.type/instant :instant) (inst? x)
      (:db.type/uuid :uuid)       (uuid? x)
      :db.type/tuple              (vector? x)
      true)
    false))

(defn valid-data?
  "validate data type"
  [x t]
  (if (vector? t)
    (and (vector? x)
         (not (some #(= % :data) t))
         (let [ct (count t)]
           (if (= 1 ct)
             (every? #(valid-data* % t) x)
             (and (= ct (count x))
                  (every? true? (map #(valid-data* %1 %2) x t))))))
    (valid-data* x t)))
