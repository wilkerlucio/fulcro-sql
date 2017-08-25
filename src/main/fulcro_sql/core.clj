(ns fulcro-sql.core
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.java.jdbc :as jdbc]
            [taoensso.timbre :as timbre]
            [clojure.spec.alpha :as s]
            [com.stuartsierra.component :as component]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (org.flywaydb.core Flyway)
           (com.zaxxer.hikari HikariConfig HikariDataSource)
           (java.util Properties)
           (org.slf4j LoggerFactory)))

(s/def ::driver keyword?)
(s/def ::joins (s/and map?
                 #(every? keyword? (keys %))
                 #(every? vector? (vals %))))
(s/def ::pks (s/and map?
               #(every? keyword? (keys %))
               #(every? keyword? (vals %))))
(s/def ::om->sql (s/and map?
                   #(every? keyword? (keys %))
                   #(every? keyword? (vals %))))

(s/def ::schema (s/keys
                  :req [::pks ::om->sql ::joins]
                  :opt [::driver]))

(defmulti sqlize* (fn sqlize-dispatch [schema kw] (get schema :driver :default)))

(defmethod sqlize* :default [schema kw]
  (let [nspc (some-> kw namespace (str/replace "-" "_"))
        nm   (some-> kw name (str/replace "-" "_"))]
    (if nspc
      (keyword nspc nm)
      (keyword nm))))

(defn sqlize
  "Convert a keyword in clojure-form to sql-form. E.g. :account-id to :account_id"
  [schema kw]
  (sqlize* schema kw))

(defn omprop->sqlprop
  "Derive an sqlprop from an om query element (prop or join)"
  [{:keys [::om->sql] :as schema} p]
  (sqlize schema
    (if (map? p)
      (get om->sql (ffirst p) (ffirst p))
      (get om->sql p p))))

(defmulti table-for* (fn [schema query] (get schema :driver :default)))

(defmethod table-for* :default
  [schema query]
  (let [nses (reduce (fn
                       ([] #{})
                       ([s p]
                        (let [sql-prop (omprop->sqlprop schema p)
                              table-kw (some-> sql-prop namespace keyword)]
                          (cond
                            (= :id sql-prop) s
                            (= :db/id sql-prop) s
                            table-kw (conj s table-kw)
                            :else s)))) #{} query)]
    (assert (= 1 (count nses)) (str "Could not determine a single table from the subquery " query))
    (sqlize schema (first nses))))

(defn table-for
  "Scans the given Om query and tries to determine which table is to be used for the props within it."
  [schema query]
  (assert (s/valid? ::schema schema) "Schema is valid")
  (table-for* schema query))

(defn id-columns
  "Returns a set of table-namespaced keywords that are the ID columns for all tables."
  [{:keys [::pks] :as schema}]
  (reduce (fn [cset [table pk]]
            (conj cset (keyword (name table) (name pk))))
    #{} pks))

(defmulti column-spec
  "Get the database-specific column query specification for a given SQL prop."
  (fn [schema sqlprop] (get schema ::driver :default)))

(defmethod column-spec :default
  [schema sqlprop]
  (let [table   (namespace sqlprop)
        col     (name sqlprop)
        as-name (str (namespace sqlprop) "/" (name sqlprop))]
    (str table "." col " AS \"" as-name "\"")))

(s/def ::migrations (s/and vector? #(every? string? %)))
(s/def ::hikaricp-config string?)

(s/def ::db (s/keys
              :req-un [::hikaricp-config ::migrations]
              :opt-un [::create-drop? ::auto-migrate?]))
(s/def ::sqldbm (s/and map?
                  #(every? keyword? (keys %))
                  #(every? (fn [db] (s/valid? ::db db)) (vals %))))

(defprotocol SQLDatabaseManager
  (start-databases [this] "Create the connection pools and (optionally) run the migrations.")
  (get-dbspec [this database-kw] "Get a clojure jdbc dbspec for the given database-kw."))

(defn create-pool
  "Create a HikariDataSource for connection pooling from a properties filename."
  [^String properties-file]
  (try
    (let [source            (if (str/starts-with? properties-file "/")
                              properties-file
                              (io/resource properties-file))
          reader            (io/reader source)
          ^Properties props (Properties.)]
      (with-open [reader reader]
        (.load props reader))
      (let [^HikariConfig config (HikariConfig. props)]
        (HikariDataSource. config)))
    (catch Exception e
      (timbre/error "Unable to create Hikari Datasource: " (.getMessage e)))))

(defrecord PostgreSQLDatabaseManager [config connection-pools]
  component/Lifecycle
  (start [this]
    (timbre/debug "Ensuring PostgreSQL JDBC driver is loaded.")
    (Class/forName "org.postgresql.Driver")
    (let [databases (-> config :value :sqldbm)
          ok?       (s/valid? ::sqldbm databases)
          pools     (and ok?
                      (reduce (fn [pools [dbkey dbconfig]]
                                (timbre/info (str "Creating connection pool for " dbkey))
                                (assoc pools dbkey (create-pool (:hikaricp-config dbconfig)))) {} databases))
          result    (assoc this :connection-pools pools)]
      (if ok?
        (start-databases result)
        (timbre/error "Unable to start SQL Databases. Configuration is invalid: " (s/explain ::sqldbm databases)))
      result))
  (stop [this]
    (doseq [[k ^HikariDataSource p] connection-pools]
      (timbre/info "Shutting down pool " k)
      (.close p))
    (assoc this :connection-pools []))
  SQLDatabaseManager
  (start-databases [this]
    (let [database-map (some-> config :value :sqldbm)]
      (doseq [[dbkey dbconfig] database-map]
        (let [{:keys [create-drop? auto-migrate? migrations]} dbconfig
              ^HikariDataSource pool (get connection-pools dbkey)
              db                     {:datasource pool}]
          (if pool
            (do
              (timbre/info (str "Processing migrations for " dbkey))

              (when-let [^Flyway flyway (when auto-migrate? (Flyway.))]
                (when create-drop?
                  (timbre/info "Create-drop was set. Cleaning everything out of the database.")
                  (jdbc/execute! db ["DROP SCHEMA PUBLIC CASCADE"])
                  (jdbc/execute! db ["CREATE SCHEMA PUBLIC"]))
                (timbre/info "Migration location is set to: " migrations)
                (.setLocations flyway (into-array String migrations))
                (.setDataSource flyway pool)
                (.migrate flyway)))
            (timbre/error (str "No pool for " dbkey ". Skipping migrations.")))))))
  (get-dbspec [this kw] (some->> connection-pools kw (assoc {} :datasource))))

(defmulti next-id*
  (fn next-id-dispatch [db schema table] (get schema :driver :default)))

(defmethod next-id* :default
  [db schema table]
  (assert (s/valid? ::schema schema) "Next-id requires a valid schema.")
  (let [pk      (get-in schema [::pks table] :id)
        seqname (str (name table) "_" (name pk) "_seq")]
    (jdbc/query db [(str "SELECT nextval('" seqname "') AS \"id\"")]
      {:result-set-fn first
       :row-fn        :id})))

(defn next-id
  "Get the next generated ID for the given table.

  NOTE: IF you specify the Java System Property `dev`, then this function will assume you are writing tests and will
  allocate extra IDs in order to prevent assertions on your generated IDs across
  tables from giving false positives (since all tables will start from ID 1). It does this by throwing away a
  random number of IDs, so that IDs across tables are less likely to be identical when an equal number of rows
  are inserted."
  [db schema table-kw]
  (let [n (rand-int 20)]
    (when (System/getProperty "dev")
      (doseq [r (range n)]
        (next-id* db schema table-kw)))
    (next-id* db schema table-kw)))

(defn seed-row
  "Generate an instruction to insert a seed row for a table, which can contain keyword placeholders for IDs. It is
   recommended you namespace your generated IDs into `id` so that substitution during seeding doesn't cause surprises.
   For example:

  ```
  (seed-row :account {:id :id/joe ...})
  ```

  If the generated IDs appear in a PK location, they will be generated (must be unique per seed set). If they
  are in a value column, then the current generated value (which must have already been seeded) will be used.

  See also `seed-update` for resolving circular references.
  "
  [table value]
  (with-meta value {:table table}))

(defn seed-update
  "Generates an instruction to update a seed row (in the same seed set) that already appeared. This may be necessary if your database has
  referential loops.

  ```
  (seed-row :account {:id :id/joe ...})
  (seed-row :account {:id :id/sam ...})
  (seed-update :account :id/joe {:last_edited_by :id/sam })
  ```

  `table` should be a keyword form of the table in your database.
  `id` can be a real ID or a generated ID placeholder keyword (recommended: namespace it with `id`).
  `value` is a map of col/value pairs to update on the row.
  "
  [table id value]
  (with-meta value {:update id :table table}))

(defn pk-column
  "Returns the SQL column for a given table's primary key"
  [schema table]
  (get-in schema [::pks table] :id))

(defn id-prop
  "Returns the SQL-centric property for the PK in a result set map (before conversion back to Om)"
  [schema table]
  (keyword (name table) (name (pk-column schema table))))

(defn seed!
  "Seed the given seed-row and seed-update items into the given database. Returns a map whose values will be the
  keyword placeholders for generated PK ids, and whose values are the real numeric generated ID:

  ```
  (let [{:keys [id/sam id/joe]} (seed! db schema [(seed-row :account {:id :id/joe ...})
                                                  (seed-row :account {:id :id/sam ...})]
    ...)
  ```
  "
  [db schema rows]
  (assert (s/valid? ::schema schema) "Schema is not valid")
  (let [tempid-map (reduce (fn [kws r]
                             (let [{:keys [update table]} (meta r)
                                   id (get r (pk-column schema table))]
                               (assert (or update id) "Expected an update or the row to contain the primary key")
                               (cond
                                 update kws
                                 (keyword? id) (assoc kws id (next-id db schema table))
                                 :else kws
                                 ))) {} rows)
        remap-row  (fn [row] (clojure.walk/postwalk (fn [e] (if (keyword? e) (get tempid-map e e) e)) row))]
    (doseq [row rows]
      (let [{:keys [update table]} (meta row)
            real-row (remap-row row)
            pk       (pk-column schema table)
            pk-val   (if update
                       (get tempid-map update update)
                       (get row pk))]
        (if update
          (do
            (timbre/debug "updating " row "at" pk pk-val)
            (jdbc/update! db table real-row [(str (name pk) " = ?") pk-val]))
          (do
            (timbre/debug "inserting " real-row)
            (jdbc/insert! db table real-row)))))
    tempid-map))

(defn query-element->sqlprop
  [{:keys [::joins] :as schema} element]
  (let [omprop                 (if (map? element) (ffirst element) element)
        id-columns             (id-columns schema)
        sql-prop               (omprop->sqlprop schema omprop)
        join                   (get joins sql-prop)
        join-source-is-row-id? (some->> join first (contains? id-columns) boolean)
        join-col               (first join)
        join-prop              (when join-col (omprop->sqlprop schema join-col))]
    (cond
      (= "id" (name sql-prop)) nil
      join-prop join-prop
      :else sql-prop)))

(defn columns-for
  "Returns an SQL-centric set of properties at the top level of the given graph query. It does not follow joins, but
  does include any columns that would be necessary to process the given joins. It will always include the row ID."
  [schema graph-query]
  (assert (s/valid? ::schema schema) "Schema is valid")
  (let [table  (table-for schema graph-query)
        pk     (get-in schema [::pks table] :id)
        id-col (keyword (name table) (name pk))]
    (reduce
      (fn [rv ele]
        (if-let [prop (query-element->sqlprop schema ele)]
          (conj rv prop)
          rv)) #{id-col} graph-query)))

(defn str-idcol
  "Returns the SQL string for the ID column of the given (keyword) table. E.g. :account -> account.id"
  [schema table]
  (str (name table) "." (name (pk-column schema table))))

(defn str-col
  "Returns the SQL string for the given sqlprop. E.g. :a/b -> a.b"
  [prop]
  (str (namespace prop) "." (name prop)))

(defn is-join-on-table? [{:keys [::joins] :as schema} table prop]
  (= (name table) (some-> prop joins first namespace)))

#_(defn query-for
  "Returns an SQL query to get the true data columns that exist for the om-query. Joins will contribute to this
  query iff there is a column on the target table that is needed in order to process the join."
  ([schema om-query id-set]
   (let [table                (table-for schema om-query)
         local-join-prop      (fn ljp
                                [ele]
                                (cond
                                  (keyword? ele) nil
                                  (and (map? ele) (is-join-on-table? schema table (->> ele first (omprop->sqlprop schema)))
                                    (->> ele first (omprop->sqlprop schema)))
                                  :else nil))
         join-cols-to-include (keep local-join-prop om-query)
         columns              (concat (columns-for schema om-query) join-cols-to-include)
         column-selectors     (map #(column-spec schema %) columns)
         selectors            (str/join "," column-selectors)
         table-name           (name table)
         ids                  (str/join "," (map str id-set))
         id-col               (if join-col
                                (str-col join-col)
                                (str-idcol schema table))
         sql                  (str "SELECT " selectors " FROM " table-name " WHERE " id-col " IN (" ids ")")]
     sql)))

(defn target-table-for-join
  "Given an SQL prop that is being used in a join, returns the target table for that join that contains the desired data"
  [{:keys [::joins] :as schema} sqlprop]
  (some-> sqlprop joins last namespace keyword))

#_(defn query-for-join
  "Given an om join and the rows retrieved for the level at the join, return a query that can obtain
  the rows for the specified join."
  [{:keys [::pks ::joins] :as schema} omjoin rows]
  (let [sqlprop           (omprop->sqlprop schema (ffirst omjoin))
        om-query          (-> omjoin vals first)
        source-table      (keyword (namespace sqlprop))
        join-sequence     (get joins sqlprop)
        source-pk         (keyword (name source-table) (name (get pks source-table :id)))
        pk-set            (into #{} (map #(get % source-pk) rows))
        join-start        (first join-sequence)
        join-start-table  (keyword (namespace join-start))
        join-target       (last join-sequence)
        std-one-to-many?  (and (= 2 (count join-sequence)) (= join-start source-pk))
        std-one-to-one?   (and (= 2 (count join-sequence))
                            (not= join-start source-pk)
                            (= join-start-table source-table))
        std-many-to-many? (and (= 4 (count join-sequence)))]
    (assert (contains? joins sqlprop) "Join is described")
    (cond
      std-one-to-many? [(query-for schema nil join-target om-query pk-set) join-target]
      std-one-to-one? (let [id-set (into #{} (map join-start rows))]
                        [(query-for schema nil join-target om-query id-set) join-target])
      std-many-to-many? (let [left-table       (-> join-sequence second namespace)
                              right-table      (-> join-sequence last namespace)
                              col-left         (-> join-sequence (nth 2) str-col)
                              col-right        (-> join-sequence (nth 3) str-col)
                              filter-col       (-> join-sequence second str-col)
                              columns          (columns-for schema om-query)
                              column-selectors (map #(column-spec schema %) columns)
                              column-selectors (conj column-selectors (column-spec schema (second join-sequence)))
                              selectors        (str/join "," column-selectors)
                              from-clause      (str "FROM " left-table " INNER JOIN " right-table " ON "
                                                 col-left " = " col-right)
                              ids              (str/join "," pk-set)
                              sql              (str "SELECT " selectors " " from-clause " WHERE " filter-col " IN (" ids ")")]
                          [sql filter-col]))))

(defn to-one [join-seq]
  (assert (and (vector? join-seq) (every? keyword? join-seq)) "join sequence is a vector of keywords")
  (vary-meta join-seq assoc :arity :to-one))

(defn to-many [join-seq]
  (assert (and (vector? join-seq) (every? keyword? join-seq)) "join sequence is a vector of keywords")
  (vary-meta join-seq assoc :arity :to-many))

(defn to-one?
  "Is the give join to-one? Returns true iff the join is marked to-one."
  [join]
  (some-> join meta :to-one))

(defn to-many?
  "Is the given join to-many? Returns true if the join is marked to many, or if the join is unmarked (e.g. default)"
  [join]
  (or (some-> join meta :to-many) (not (to-one? join))))

#_(defn run-query
  "Run an om query against the given database.

  db - The database
  schema - The database schema
  join-or-id-column - The column (which must be defined in schema) that represents the PK column of the table you are
  querying, or the database join (edge) your following from the root set.
  root-set - A set of IDs that identify the source rows for the query.
  om-query - The Om query to be run against the root-set (once per ID in root set)
  "
  [db {:keys [::joins] :as schema} join-id-or-column root-set om-query]
  (assert (s/valid? ::schema schema) "schema is valid")
  (let [is-join?    (contains? joins join-id-or-column)
        row-query   (if is-join?
                      (query-for-join schema {join-id-or-column om-query} root-set)
                      (query-for schema table om-query root-set))
        prop-rows   (jdbc/query db [row-query])
        joins       (filter map? om-query)
        is-one?     (and (= arity :to-one) (<= 1 (count prop-rows)))
        result-rows (for [row prop-rows]
                      (reduce
                        (fn [r j]
                          (let [[join-key join-query] j
                                row-id (get r (id-prop schema table))]
                            (assoc r join-key (run-query db schema :to-many (table-for schema join-query) #{row-id} join-query)))
                          ) row joins))
        ]
    (if is-one?
      (first result-rows)
      (vec result-rows))))

(comment
  (let [account-rows #{{:account/id 1 :account/name "Joe"} {:account/id 2 :account/name "Sam"}}
        invoices     #{{:invoice/account_id 1 :invoice/id 1}
                       {:invoice/account_id 1 :invoice/id 2}
                       {:invoice/account_id 1 :invoice/id 3}
                       {:invoice/account_id 2 :invoice/id 4}}]
    (set/join account-rows invoices {:account/id :invoice/account_id})
    ))
