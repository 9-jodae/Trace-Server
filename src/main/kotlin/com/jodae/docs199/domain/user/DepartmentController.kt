package com.jodae.docs199.domain.user

import com.jodae.docs199.global.response.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class DepartmentResponse(
    val id: UUID,
    val name: String,
)

@RestController
@RequestMapping("/api/departments")
class DepartmentController(
    private val departments: DepartmentRepository,
) {
    @GetMapping
    fun list() = ApiResponse.ok(departments.findAll().sortedBy { it.name }.map { DepartmentResponse(it.id, it.name) })
}
