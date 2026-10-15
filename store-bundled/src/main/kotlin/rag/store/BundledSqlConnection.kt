package rag.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** androidx's bundled SQLite: the Android driver (SPEC §15.2). */
class BundledSqlDriver : SqlDriver {
    override val name = "bundled"
    override fun open(path: String): SqlConnection = BundledSqlConnection(BundledSQLiteDriver().open(path))
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
