package com.jodae.docs199

import com.jodae.docs199.global.storage.S3FileStorage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import java.net.URL
import java.time.Duration

class S3FileStorageTests {
    private val client = mock(S3Client::class.java)
    private val presigner = mock(S3Presigner::class.java)
    private val storage = S3FileStorage(client, presigner, "test-bucket", "/uploads/")

    @Test
    fun uploadAndDeleteUsePrefixedKey() {
        val content = "test".toByteArray()
        storage.upload("documents/file", content.inputStream(), content.size.toLong(), "text/plain")
        val put = ArgumentCaptor.forClass(PutObjectRequest::class.java)
        verify(client).putObject(put.capture(), org.mockito.ArgumentMatchers.any(software.amazon.awssdk.core.sync.RequestBody::class.java))
        assertEquals("test-bucket", put.value.bucket())
        assertEquals("uploads/documents/file", put.value.key())
        assertEquals("text/plain", put.value.contentType())

        storage.delete("documents/file")
        val delete = ArgumentCaptor.forClass(DeleteObjectRequest::class.java)
        verify(client).deleteObject(delete.capture())
        assertEquals("uploads/documents/file", delete.value.key())
    }

    @Test
    fun downloadAndPresignUsePrefixedKey() {
        val response = ResponseInputStream(GetObjectResponse.builder().build(), "stored".byteInputStream())
        `when`(client.getObject(org.mockito.ArgumentMatchers.any(GetObjectRequest::class.java))).thenReturn(response)
        assertEquals("stored", storage.download("documents/file").use { it.readAllBytes().decodeToString() })
        val get = ArgumentCaptor.forClass(GetObjectRequest::class.java)
        verify(client).getObject(get.capture())
        assertEquals("uploads/documents/file", get.value.key())

        val signed = mock(PresignedGetObjectRequest::class.java)
        `when`(signed.url()).thenReturn(URL("https://example.test/download"))
        `when`(presigner.presignGetObject(org.mockito.ArgumentMatchers.any(GetObjectPresignRequest::class.java))).thenReturn(signed)
        assertEquals("https://example.test/download", storage.presignedDownloadUrl("documents/file", Duration.ofMinutes(1)))
        val presign = ArgumentCaptor.forClass(GetObjectPresignRequest::class.java)
        verify(presigner).presignGetObject(presign.capture())
        assertEquals("uploads/documents/file", presign.value.getObjectRequest().key())
        assertEquals(Duration.ofMinutes(1), presign.value.signatureDuration())
    }
}
