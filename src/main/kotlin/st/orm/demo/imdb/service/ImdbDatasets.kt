package st.orm.demo.imdb.service

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The gzipped TSV files of the public IMDB dataset. */
fun interface ImdbDatasets {

    /** Returns a local copy of the named dataset file that is as recent as the published one. */
    fun fetch(fileName: String): Path
}

/**
 * Keeps the dataset files in a local cache directory. IMDB republishes them daily, so each
 * fetch asks the server whether the cached copy is still current, with a conditional request
 * on the copy's modification time. An unchanged file costs one round trip; a newer one is
 * downloaded in full and replaces the cached copy.
 */
class CachedImdbDatasets(
    private val cacheDirectory: Path,
    private val baseUrl: String
) : ImdbDatasets {

    private val logger = LoggerFactory.getLogger(CachedImdbDatasets::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun fetch(fileName: String): Path {
        Files.createDirectories(cacheDirectory)
        val file = cacheDirectory.resolve(fileName)
        val uri = URI.create("$baseUrl/$fileName")
        val request = HttpRequest.newBuilder(uri).apply {
            if (Files.exists(file)) header("If-Modified-Since", httpDate(Files.getLastModifiedTime(file)))
        }.build()
        val temporaryFile = Files.createTempFile(cacheDirectory, fileName, ".download")
        try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(temporaryFile))
            when (response.statusCode()) {
                HTTP_NOT_MODIFIED -> logger.debug("{} is up to date.", fileName)
                HTTP_OK -> {
                    Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING)
                    // The server's modification time, so the next request compares like with like.
                    response.headers().firstValue("Last-Modified").ifPresent {
                        Files.setLastModifiedTime(file, fileTime(it))
                    }
                    logger.info("Downloaded {} ({} MB).", uri, Files.size(file) / (1024 * 1024))
                }
                else -> error("Download of $uri failed with status ${response.statusCode()}")
            }
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
        return file
    }

    private fun httpDate(time: FileTime): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(time.toInstant().atZone(ZoneOffset.UTC))

    private fun fileTime(httpDate: String): FileTime =
        FileTime.from(ZonedDateTime.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_NOT_MODIFIED = 304
    }
}
