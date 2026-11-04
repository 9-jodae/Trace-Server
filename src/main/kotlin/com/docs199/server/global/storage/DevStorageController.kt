package com.docs199.server.global.storage

import com.docs199.server.global.response.ApiResponse
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Duration
import java.util.UUID

@Profile("local")
@RestController
@RequestMapping("/api/dev/storage")
class DevStorageController(
    private val storage: FileStorage,
) {
    @PostMapping("/upload")
    fun upload(
        @RequestPart file: MultipartFile,
    ): ApiResponse<Map<String, String>> {
        val key = "dev/${UUID.randomUUID()}"
        storage.upload(key, file.inputStream, file.size, file.contentType ?: "application/octet-stream")
        return ApiResponse.ok(mapOf("key" to key, "downloadUrl" to storage.presignedDownloadUrl(key, Duration.ofMinutes(15))))
    }
}
