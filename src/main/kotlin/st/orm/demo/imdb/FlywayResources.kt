package st.orm.demo.imdb

import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.resource.LoadableResource
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Hands Flyway its migration resources explicitly instead of relying on classpath scanning,
 * which GraalVM native images do not support (Spring Boot ships the same mechanism for its
 * Flyway integration). Works identically on the JVM, so the application needs no
 * native-specific code path.
 */
class FlywayResources(private val resourceNames: List<String>) : ResourceProvider {

    override fun getResource(name: String): LoadableResource? =
        if (name in resourceNames) resource(name) else null

    override fun getResources(prefix: String, suffixes: Array<String>): Collection<LoadableResource> =
        resourceNames
            .filter { name ->
                val fileName = name.substringAfterLast('/')
                fileName.startsWith(prefix) && suffixes.any { suffix -> fileName.endsWith(suffix) }
            }
            .map { resource(it) }

    private fun resource(name: String): LoadableResource = object : LoadableResource() {
        override fun read(): Reader =
            InputStreamReader(checkNotNull(javaClass.classLoader.getResourceAsStream(name)), UTF_8)

        override fun getAbsolutePath(): String = name
        override fun getAbsolutePathOnDisk(): String = name
        override fun getFilename(): String = name.substringAfterLast('/')
        override fun getRelativePath(): String = name
    }
}
