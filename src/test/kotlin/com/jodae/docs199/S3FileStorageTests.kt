package com.jodae.docs199

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.storage.S3FileStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.time.Duration

class S3FileStorageTests {
    private val client = Mockito.mock(S3Client::class.java)
    private val presigner = Mockito.mock(S3Presigner::class.java)
    private val storage = S3FileStorage(client, presigner, "test-bucket", "/uploads/")

    @Test
    fun uploadAndDeleteUsePrefixedKey() {
        val content = "test".toByteArray()
        storage.upload("documents/file", content.inputStream(), content.size.toLong(), "text/plain")
        val put = ArgumentCaptor.forClass(PutObjectRequest::class.java)
        Mockito.verify(client).putObject(put.capture(), any(RequestBody::class.java))
        assertEquals("test-bucket", put.value.bucket())
        assertEquals("uploads/documents/file", put.value.key())
        assertEquals("text/plain", put.value.contentType())

        storage.delete("documents/file")
        val delete = ArgumentCaptor.forClass(DeleteObjectRequest::class.java)
        Mockito.verify(client).deleteObject(delete.capture())
        assertEquals("uploads/documents/file", delete.value.key())
    }

    @Test
    fun downloadAndPresignUsePrefixedKey() {
        val response = ResponseInputStream(GetObjectResponse.builder().build(), "stored".byteInputStream())
        Mockito.`when`(client.getObject(org.mockito.ArgumentMatchers.any(GetObjectRequest::class.java))).thenReturn(response)
        assertEquals("stored", storage.download("documents/file").use { it.readAllBytes().decodeToString() })
        val get = ArgumentCaptor.forClass(GetObjectRequest::class.java)
        Mockito.verify(client).getObject(get.capture())
        assertEquals("uploads/documents/file", get.value.key())

        val signed = Mockito.mock(PresignedGetObjectRequest::class.java)
        Mockito.`when`(signed.url()).thenReturn(URL("https://example.test/download"))
        Mockito.`when`(presigner.presignGetObject(org.mockito.ArgumentMatchers.any(GetObjectPresignRequest::class.java))).thenReturn(signed)
        assertEquals("https://example.test/download", storage.presignedDownloadUrl("documents/file", Duration.ofMinutes(1)))
        val presign = ArgumentCaptor.forClass(GetObjectPresignRequest::class.java)
        Mockito.verify(presigner).presignGetObject(presign.capture())
        assertEquals("uploads/documents/file", presign.value.getObjectRequest().key())
        assertEquals(Duration.ofMinutes(1), presign.value.signatureDuration())
    }

    @Test
    fun downloadConvertsErrorsDuringStreamRead() {
        val failing =
            object : InputStream() {
                override fun read(): Int = throw IOException("stream failed")
            }
        val response = ResponseInputStream(GetObjectResponse.builder().build(), failing)
        Mockito.`when`(client.getObject(org.mockito.ArgumentMatchers.any(GetObjectRequest::class.java))).thenReturn(response)

        val error = assertThrows(ApiException::class.java) { storage.download("documents/file").use { it.read() } }
        assertEquals(ErrorCode.STORAGE_ERROR, error.code)
    }
}
