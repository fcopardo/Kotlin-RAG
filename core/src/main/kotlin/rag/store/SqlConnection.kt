package rag.store

import java.util.ServiceLoader

/** A row of a query result. Column positions are 1-based; names are the result's column names. */
interface SqlRow {
    fun long(column: Int): Long
    fun long(column: String): Long
    fun int(column: Int): Int = long(column).toInt()
    fun int(column: String): Int = long(column).toInt()
    fun float(column: Int): Float
    fun string(column: Int): String?
    fun string(column: String): String?
    fun bytes(column: Int): ByteArray
    fun isNull(column: String): Boolean
    fun longOrNull(column: String): Long? = if (isNull(column)) null else long(column)
    fun intOrNull(column: String): Int? = if (isNull(column)) null else int(column)
}

/**
 * The few operations the store needs from SQLite (SPEC §15.2). Two implementations run the same
 * SQL: JDBC (module store-jdbc: desktop, every platform) and androidx bundled (module
 * store-bundled: Android and some desktops). Arguments may be Long, Int, Double, Float, String, ByteArray, Boolean or null.
 */
interface SqlConnection : AutoCloseable {
    fun <T> query(sql: String, args: List<Any?> = emptyList(), mapper: (SqlRow) -> T): List<T>

    /** Runs a statement (stepping through any rows it returns); the number of changed rows. */
    fun execute(sql: String, args: List<Any?> = emptyList()): Int

    /** Runs an INSERT; the new row's id. */
    fun insert(sql: String, args: List<Any?>): Long

    /** Runs one statement once per argument list, reusing the prepared statement. */
    fun batch(sql: String, rows: List<List<Any?>>)

    fun begin()
    fun commit()
    fun rollback()
}

/**
 * Opens connections to a database file. Driver modules register theirs in
 * `META-INF/services/rag.store.SqlDriver`.
 */
interface SqlDriver {
    /** "jdbc" or "bundled". */
    val name: String

    fun open(path: String): SqlConnection

    companion object {
        /**
         * The installed driver to use: the one named by the system property `rag.sql.driver`, else
         * JDBC when present (every desktop platform), else the only one installed (Android: bundled).
         */
        fun default(): SqlDriver {
            val installed = ServiceLoader.load(SqlDriver::class.java).toList()
            val wanted = System.getProperty("rag.sql.driver")
            return installed.firstOrNull { it.name == (wanted ?: "jdbc") }
                ?: installed.singleOrNull()?.takeIf { wanted == null }
                ?: throw IllegalStateException("No SQL driver${wanted?.let { " named '$it'" } ?: ""} is installed (found: ${installed.map { it.name }})")
        }
    }
}
