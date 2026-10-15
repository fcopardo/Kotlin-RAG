package rag.store

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/** The desktop default driver. */
class JdbcSqlDriver : SqlDriver {
    override val name = "jdbc"
    override fun open(path: String): SqlConnection = JdbcSqlConnection(DriverManager.getConnection("jdbc:sqlite:$path"))
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

