package com.jodae.docs199.domain.document

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class VersionCreated(
    val versionId: UUID,
)

data class IngestRequest(
    val jobId: UUID,
    val versionId: UUID,
    val storageKey: String,
    val extension: String,
)

interface IngestionClient {
    fun submit(request: IngestRequest)
}

@Component
@ConditionalOnProperty(name = ["app.ai.mode"], havingValue = "mock")
class MockIngestionClient(
    private val jobs: JobUpdater,
) : IngestionClient {
    override fun submit(request: IngestRequest) {
        CompletableFuture
            .runAsync(
                {
                    jobs.processing(request.jobId)
                },
                CompletableFuture.delayedExecutor(100, java.util.concurrent.TimeUnit.MILLISECONDS),
            ).thenRunAsync(
                { jobs.completeMock(request.jobId, request.extension) },
                CompletableFuture.delayedExecutor(150, java.util.concurrent.TimeUnit.MILLISECONDS),
            )
    }
}

@Component
@ConditionalOnProperty(name = ["app.ai.mode"], havingValue = "http")
class HttpIngestionClient(
    @Value("\${app.ai.base-url}") baseUrl: String,
    @Value("\${app.ai.timeout-seconds}") timeoutSeconds: Long,
    @Value("\${app.internal-secret}") private val secret: String,
) : IngestionClient {
    private val client =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(
                org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofSeconds(timeoutSeconds))
                    setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                },
            ).build()

    override fun submit(request: IngestRequest) {
        client
            .post()
            .uri("/ingest")
            .header("X-Internal-Secret", secret)
            .body(request)
            .retrieve()
            .toBodilessEntity()
    }
}

@Service
class JobUpdater(
    private val repository: DocumentRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun create(versionId: UUID): UUID {
        val jobId = UUID.randomUUID()
        repository.createJob(jobId, versionId)
        return jobId
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun processing(jobId: UUID) {
        repository.updateJob(jobId, JobStatus.PROCESSING, null)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeMock(
        jobId: UUID,
        extension: String,
    ) {
        val job = repository.job(jobId) ?: return
        val version = repository.version(job.versionId) ?: return
        repository.updateType(version.documentId, if (extension == "md") DocumentType.HANDOVER else DocumentType.ETC)
        repository.updateJob(jobId, JobStatus.DONE, null)
    }

    @Transactional
    fun result(
        jobId: UUID,
        status: JobStatus,
        error: String?,
        type: DocumentType?,
    ) {
        val job = repository.job(jobId) ?: throw ApiException(ErrorCode.NOT_FOUND)
        if (status == JobStatus.PENDING) throw ApiException(ErrorCode.INVALID_INPUT)
        repository.updateJob(jobId, status, error)
        if (type != null && status == JobStatus.DONE) {
            val version = repository.version(job.versionId) ?: throw ApiException(ErrorCode.NOT_FOUND)
            repository.updateType(version.documentId, type)
        }
    }
}

@Component
class IngestionAfterCommit(
    private val repository: DocumentRepository,
    private val jobs: JobUpdater,
    private val client: IngestionClient,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun created(event: VersionCreated) {
        val version = repository.version(event.versionId) ?: return
        val jobId = jobs.create(version.id)
        try {
            client.submit(IngestRequest(jobId, version.id, version.storageKey, repository.document(version.documentId)?.extension ?: ""))
        } catch (ex: Exception) {
            jobs.result(jobId, JobStatus.FAILED, "AI 서버 처리 요청 실패", null)
        }
    }
}
