package com.jodae.docs199.global.storage

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.io.InputStream
import java.time.Duration

@Configuration
@ConditionalOnProperty(name = ["app.storage.type"], havingValue = "s3")
class S3StorageConfig {
    @Bean
    fun s3Client(): S3Client = S3Client.create()

    @Bean
    fun s3Presigner(): S3Presigner = S3Presigner.create()
}

@Component
@ConditionalOnProperty(name = ["app.storage.type"], havingValue = "s3")
class S3FileStorage(
    private val client: S3Client,
    private val presigner: S3Presigner,
    @Value("\${app.storage.s3.bucket}") private val bucket: String,
    @Value("\${app.storage.s3.prefix:uploads/}") prefix: String,
) : FileStorage {
    private val prefix = prefix.trim('/').let { if (it.isEmpty()) "" else "$it/" }

    override fun upload(
        key: String,
        input: InputStream,
        size: Long,
        contentType: String,
    ) = safe {
        client.putObject(
            PutObjectRequest
                .builder()
                .bucket(bucket)
                .key(objectKey(key))
                .contentType(contentType)
                .build(),
            RequestBody.fromInputStream(input, size),
        )
        Unit
    }

    override fun download(key: String): InputStream =
        safe {
            client.getObject(
                GetObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(objectKey(key))
                    .build(),
            )
        }

    override fun delete(key: String) =
        safe {
            client.deleteObject(
                DeleteObjectRequest
                    .builder()
                    .bucket(bucket)
                    .key(objectKey(key))
                    .build(),
            )
            Unit
        }

    override fun presignedDownloadUrl(
        key: String,
        expires: Duration,
    ): String =
        safe {
            presigner
                .presignGetObject(
                    GetObjectPresignRequest
                        .builder()
                        .signatureDuration(expires)
                        .getObjectRequest(
                            GetObjectRequest
                                .builder()
                                .bucket(bucket)
                                .key(objectKey(key))
                                .build(),
                        ).build(),
                ).url()
                .toString()
        }

    private fun objectKey(key: String) = prefix + key.trimStart('/')

    private fun <T> safe(action: () -> T): T =
        try {
            action()
        } catch (ex: Exception) {
            throw ApiException(ErrorCode.STORAGE_ERROR)
        }
}
