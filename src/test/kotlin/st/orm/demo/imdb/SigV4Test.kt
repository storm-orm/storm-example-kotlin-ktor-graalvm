package st.orm.demo.imdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import st.orm.demo.imdb.service.SigV4
import java.time.Duration
import java.time.Instant

/** The signatures of the examples in AWS's documentation of Signature Version 4 for S3. */
class SigV4Test {

    private val accessKey = "AKIAIOSFODNN7EXAMPLE"
    private val secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val at = Instant.parse("2013-05-24T00:00:00Z")

    @Test
    fun `a request is signed in its headers as the GET Object example`() {
        val headers = SigV4.sign("GET", "examplebucket.s3.amazonaws.com", "/test.txt", mapOf("range" to "bytes=0-9"), ByteArray(0), "us-east-1", accessKey, secretKey, at)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            headers["Authorization"]
        )
        assertEquals("20130524T000000Z", headers["x-amz-date"])
    }

    @Test
    fun `a link is signed in its query as the presigned URL example`() {
        val link = SigV4.presign("examplebucket.s3.amazonaws.com", "/test.txt", "us-east-1", accessKey, secretKey, at, Duration.ofDays(1))
        assertTrue(link.startsWith("/test.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"))
        assertTrue(link.endsWith("&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"), link)
    }
}
