package com.docs199.server.global.storage

import com.docs199.server.global.error.ApiException
import com.docs199.server.global.error.ErrorCode
import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.Http.Method
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.InputStream
import java.time.Duration

interface FileStorage {
    fun upload(
        key: String,
        input: InputStream,
        size: Long,
        contentType: String,
    )

    fun download(key: String): InputStream

    fun delete(key: String)

    fun presignedDownloadUrl(
        key: String,
        expires: Duration,
    ): String
}

@Component
class MinioFileStorage(
    @Value("\${app.storage.endpoint}") endpoint: String,
    @Value("\${app.storage.access-key}") accessKey: String,
    @Value("\${app.storage.secret-key}") secretKey: String,
    @Value("\${app.storage.bucket}") private val bucket: String,
) : FileStorage {
    private val client =
        MinioClient
            .builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build()

    private fun ready() {
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
        }
    }

    override fun upload(
        key: String,
        input: InputStream,
        size: Long,
        contentType: String,
    ) = safe {
        ready()
        client.putObject(
            PutObjectArgs
                .builder()
                .bucket(bucket)
                .`object`(key)
                .stream(input, size, -1)
                .contentType(contentType)
                .build(),
        )
        Unit
    }

    override fun download(key: String): InputStream =
        safe {
            ready()
            client.getObject(
                GetObjectArgs
                    .builder()
                    .bucket(bucket)
                    .`object`(key)
                    .build(),
            )
        }

    override fun delete(key: String) =
        safe {
            ready()
            client.removeObject(
                RemoveObjectArgs
                    .builder()
                    .bucket(bucket)
                    .`object`(key)
                    .build(),
            )
            Unit
        }

    override fun presignedDownloadUrl(
        key: String,
        expires: Duration,
    ): String =
        safe {
            ready()
            client.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs
                    .builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .`object`(key)
                    .expiry(expires.seconds.toInt())
                    .build(),
            )
        }

    private fun <T> safe(action: () -> T): T =
        try {
            action()
        } catch (ex: Exception) {
            throw ApiException(ErrorCode.STORAGE_ERROR)
        }
}
