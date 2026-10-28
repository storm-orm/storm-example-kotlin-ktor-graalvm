package st.orm.demo.imdb.service

import io.ktor.server.config.ApplicationConfig
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The deployment's object storage bucket, as far as the image cache needs
 * it: whether an object is there, storing one, and a link a browser loads
 * it from. The bucket speaks S3, addressed by path (endpoint/bucket/key);
 * every request is signed with AWS Signature Version 4 ([SigV4]), which is
 * all an S3 client adds over plain HTTP, so no SDK comes along.
 */
class ObjectBucket(
    private val endpoint: URI,
    private val bucket: String,
    private val region: String,
    private val accessKey: String,
    private val secretKey: String
) {
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val host: String = endpoint.host + if (endpoint.port == -1) "" else ":${endpoint.port}"

    /** The size of the object under [key], or null when there is none. */
    fun size(key: String): Long? {
        val response = send("HEAD", key, ByteArray(0), emptyMap(), HttpResponse.BodyHandlers.discarding())
        return when (response.statusCode()) {
            200 -> response.headers().firstValueAsLong("Content-Length").orElse(0)
            404 -> null
            else -> throw IOException("The bucket answered ${response.statusCode()} for HEAD $key")
        }
    }

    /** Stores [bytes] under [key], with the type and the caching the bucket answers it with. */
    fun put(key: String, bytes: ByteArray, contentType: String, cacheControl: String) {
        val response = send("PUT", key, bytes, mapOf("content-type" to contentType, "cache-control" to cacheControl), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("The bucket answered ${response.statusCode()} for PUT $key: ${response.body().take(200)}")
    }

    /**
     * A link a browser loads the object from for [validFor] from [signedAt]:
     * the same link for the same moment, so a browser that loaded it once
     * finds it in its cache.
     */
    fun link(key: String, signedAt: Instant, validFor: Duration): String =
        "${endpoint.scheme}://$host" + SigV4.presign(host, pathOf(key), region, accessKey, secretKey, signedAt, validFor)

    private fun pathOf(key: String) = "/$bucket/$key"

    private fun <T> send(method: String, key: String, body: ByteArray, headers: Map<String, String>, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val signed = SigV4.sign(method, host, pathOf(key), headers, body, region, accessKey, secretKey, Instant.now())
        val request = HttpRequest.newBuilder(URI.create("${endpoint.scheme}://$host${SigV4.encodePath(pathOf(key))}"))
            .timeout(Duration.ofSeconds(10))
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            .apply { signed.forEach { (name, value) -> header(name, value) } }
            .build()
        return httpClient.send(request, handler)
    }
}

/**
 * AWS Signature Version 4 for S3, the two forms the bucket needs: headers
 * that sign a request, and a link that carries its signature in its query
 * string. https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html
 */
object SigV4 {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    /**
     * The headers that sign a request, besides the ones it has: the date,
     * the payload's hash and the authorization. The host is signed too; the
     * HTTP client sends it from the URL.
     */
    fun sign(
        method: String,
        host: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
        region: String,
        accessKey: String,
        secretKey: String,
        at: Instant
    ): Map<String, String> {
        val dateTime = DATE_TIME.format(at)
        val payloadHash = hex(sha256(body))
        val all = (headers.mapKeys { it.key.lowercase() } + mapOf("host" to host, "x-amz-date" to dateTime, "x-amz-content-sha256" to payloadHash)).toSortedMap()
        val signedHeaders = all.keys.joinToString(";")
        val canonical = listOf(
            method,
            encodePath(path),
            "",
            all.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" },
            signedHeaders,
            payloadHash
        ).joinToString("\n")
        val scope = scopeOf(at, region)
        val signature = signatureOf(canonical, dateTime, scope, at, region, secretKey)
        return headers + mapOf(
            "x-amz-date" to dateTime,
            "x-amz-content-sha256" to payloadHash,
            "Authorization" to "$ALGORITHM Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        )
    }

    /** A GET link to the path, signed in its query string at [at], valid for [validFor]: the path and its query. */
    fun presign(host: String, path: String, region: String, accessKey: String, secretKey: String, at: Instant, validFor: Duration): String {
        val dateTime = DATE_TIME.format(at)
        val scope = scopeOf(at, region)
        val query = sortedMapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "$accessKey/$scope",
            "X-Amz-Date" to dateTime,
            "X-Amz-Expires" to validFor.seconds.toString(),
            "X-Amz-SignedHeaders" to "host"
        ).entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val canonical = listOf("GET", encodePath(path), query, "host:$host\n", "host", UNSIGNED_PAYLOAD).joinToString("\n")
        return "${encodePath(path)}?$query&X-Amz-Signature=${signatureOf(canonical, dateTime, scope, at, region, secretKey)}"
    }

    private fun scopeOf(at: Instant, region: String) = "${DATE.format(at)}/$region/s3/aws4_request"

    private fun signatureOf(canonical: String, dateTime: String, scope: String, at: Instant, region: String, secretKey: String): String {
        val toSign = listOf(ALGORITHM, dateTime, scope, hex(sha256(canonical.toByteArray()))).joinToString("\n")
        val key = listOf(DATE.format(at), region, "s3", "aws4_request")
            .fold("AWS4$secretKey".toByteArray()) { key, part -> hmac(key, part) }
        return hex(hmac(key, toSign))
    }

    /** A path with each segment encoded and its slashes kept. */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }

    /** Encodes as S3 signs: everything but the unreserved characters, in upper-case hex. */
    private fun encode(value: String): String = buildString {
        for (byte in value.toByteArray()) {
            val c = byte.toInt().toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.~") append(c) else append("%%%02X".format(byte.toInt() and 0xFF))
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * The bucket the platform gives the deployment, from the variables it sets
 * (images in application.conf); null where there is none, as on a laptop,
 * and then the images are not cached.
 */
fun ApplicationConfig.objectBucket(): ObjectBucket? {
    fun value(path: String) = propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() }
    return ObjectBucket(
        endpoint = URI.create(value("images.bucket.endpoint") ?: return null),
        bucket = value("images.bucket.name") ?: return null,
        region = value("images.bucket.region") ?: "us-east-1",
        accessKey = value("images.bucket.accessKey") ?: return null,
        secretKey = value("images.bucket.secretKey") ?: return null
    )
}
