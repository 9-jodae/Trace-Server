package com.jodae.docs199.domain.document

import com.jodae.docs199.global.storage.FileStorage
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class StorageCleanupQueue(
    private val repository: DocumentRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun enqueue(key: String) {
        repository.enqueueCleanup(key)
    }
}

@Service
class StorageCleanupWorker(
    private val repository: DocumentRepository,
    private val storage: FileStorage,
) {
    @Scheduled(
        fixedDelayString = "\${app.ingestion.recovery-interval-ms:60000}",
        initialDelayString = "\${app.ingestion.recovery-interval-ms:60000}",
    )
    fun retry() {
        repository.pendingCleanup().forEach { (id, key) ->
            try {
                storage.delete(key)
                repository.finishCleanup(id)
            } catch (ex: Exception) {
                repository.failedCleanup(id)
            }
        }
    }
}
