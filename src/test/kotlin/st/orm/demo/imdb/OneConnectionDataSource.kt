package st.orm.demo.imdb

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * A data source that hands out one connection at a time, as a pool does
 * when every other connection is in use: asking for a second while the
 * first is open fails at once, where the pool would wait for its timeout.
 * Code that passes on it never holds two connections in one request, so a
 * burst of requests cannot take the whole pool waiting for each other.
 */
class OneConnectionDataSource(private val delegate: DataSource) : DataSource by delegate {
    private val open = AtomicInteger()

    override fun getConnection(): Connection {
        if (open.incrementAndGet() > 1) {
            open.decrementAndGet()
            throw SQLException("A second connection was asked for while one is open.")
        }
        val connection = delegate.connection
        val closed = AtomicBoolean()
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "close" && closed.compareAndSet(false, true)) open.decrementAndGet()
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
    }
}
