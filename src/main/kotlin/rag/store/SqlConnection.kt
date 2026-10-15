package rag.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

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
 * SQL: [JdbcSqlConnection] (desktop, every platform) and [BundledSqlConnection] (androidx, Android
 * and some desktops). Arguments may be Long, Int, Double, Float, String, ByteArray, Boolean or null.
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

/** Opens connections to a database file. */
fun interface SqlDriver {
    fun open(path: String): SqlConnection

    companion object {
        val JDBC = SqlDriver { JdbcSqlConnection(DriverManager.getConnection("jdbc:sqlite:$it")) }
        val BUNDLED = SqlDriver { BundledSqlConnection(BundledSQLiteDriver().open(it)) }

        /**
         * Desktop default: JDBC, whose native library covers every desktop platform. The system
         * property `rag.sql.driver=bundled` selects the androidx driver (tests run both).
         */
        fun default(): SqlDriver = if (System.getProperty("rag.sql.driver") == "bundled") BUNDLED else JDBC
    }
}

/** sqlite-jdbc: macOS (arm64 and x86_64), Linux, Windows (x64 and arm64). */
class JdbcSqlConnection(private val connection: Connection) : SqlConnection {

    override fun <T> query(sql: String, args: List<Any?>, mapper: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { s ->
            bind(s, args)
            s.executeQuery().use { rs ->
                val row = Row(rs)
                buildList { while (rs.next()) add(mapper(row)) }
            }
        }

    override fun execute(sql: String, args: List<Any?>): Int = connection.prepareStatement(sql).use { s ->
        bind(s, args)
        if (s.execute()) {
            s.resultSet.use { rs -> while (rs.next()) Unit }
            0
        } else s.updateCount
    }

    override fun insert(sql: String, args: List<Any?>): Long =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { s ->
            bind(s, args)
            s.executeUpdate()
            s.generatedKeys.use { it.next(); it.getLong(1) }
        }

    override fun batch(sql: String, rows: List<List<Any?>>) {
        if (rows.isEmpty()) return
        connection.prepareStatement(sql).use { s ->
            rows.forEach { bind(s, it); s.addBatch() }
            s.executeBatch()
        }
    }

    override fun begin() {
        connection.autoCommit = false
    }

    override fun commit() {
        connection.commit()
        connection.autoCommit = true
    }

    override fun rollback() {
        connection.rollback()
        connection.autoCommit = true
    }

    override fun close() = connection.close()

    private fun bind(s: PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a -> s.setObject(i + 1, if (a is Boolean) (if (a) 1 else 0) else a) }
    }

    private class Row(private val rs: ResultSet) : SqlRow {
        override fun long(column: Int) = rs.getLong(column)
        override fun long(column: String) = rs.getLong(column)
        override fun float(column: Int) = rs.getFloat(column)
        override fun string(column: Int): String? = rs.getString(column)
        override fun string(column: String): String? = rs.getString(column)
        override fun bytes(column: Int): ByteArray = rs.getBytes(column)
        override fun isNull(column: String) = rs.getObject(column) == null
    }
}

/** androidx.sqlite with its bundled SQLite (FTS5, 3.43+ everywhere). Not thread-safe, like the store. */
class BundledSqlConnection(private val connection: SQLiteConnection) : SqlConnection {

    override fun <T> query(sql: String, args: List<Any?>, mapper: (SqlRow) -> T): List<T> =
        connection.prepare(sql).use { s ->
            bind(s, args)
            val row = Row(s)
            buildList { while (s.step()) add(mapper(row)) }
        }

    override fun execute(sql: String, args: List<Any?>): Int {
        connection.prepare(sql).use { s ->
            bind(s, args)
            while (s.step()) Unit
        }
        return changes()
    }

    override fun insert(sql: String, args: List<Any?>): Long {
        execute(sql, args)
        return connection.prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }
    }

    override fun batch(sql: String, rows: List<List<Any?>>) {
        if (rows.isEmpty()) return
        connection.prepare(sql).use { s ->
            rows.forEach { args ->
                s.reset()
                s.clearBindings()
                bind(s, args)
                while (s.step()) Unit
            }
        }
    }

    override fun begin() {
        execute("BEGIN")
    }

    override fun commit() {
        execute("COMMIT")
    }

    override fun rollback() {
        execute("ROLLBACK")
    }

    override fun close() = connection.close()

    private fun changes(): Int = connection.prepare("SELECT changes()").use { it.step(); it.getLong(0).toInt() }

    private fun bind(s: SQLiteStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            val at = i + 1
            when (a) {
                null -> s.bindNull(at)
                is Long -> s.bindLong(at, a)
                is Int -> s.bindLong(at, a.toLong())
                is Boolean -> s.bindLong(at, if (a) 1 else 0)
                is Double -> s.bindDouble(at, a)
                is Float -> s.bindDouble(at, a.toDouble())
                is String -> s.bindText(at, a)
                is ByteArray -> s.bindBlob(at, a)
                else -> throw IllegalArgumentException("can't bind ${a::class.simpleName}")
            }
        }
    }

    /** Positions are 1-based like JDBC; androidx's are 0-based. */
    private class Row(private val s: SQLiteStatement) : SqlRow {
        private val names: Map<String, Int> by lazy { (0 until s.getColumnCount()).associateBy { s.getColumnName(it) } }
        private fun at(column: String) = names[column] ?: throw IllegalArgumentException("no column $column")

        override fun long(column: Int) = s.getLong(column - 1)
        override fun long(column: String) = s.getLong(at(column))
        override fun float(column: Int) = s.getDouble(column - 1).toFloat()
        override fun string(column: Int): String? = if (s.isNull(column - 1)) null else s.getText(column - 1)
        override fun string(column: String): String? = if (s.isNull(at(column))) null else s.getText(at(column))
        override fun bytes(column: Int): ByteArray = s.getBlob(column - 1)
        override fun isNull(column: String) = s.isNull(at(column))
    }
}
