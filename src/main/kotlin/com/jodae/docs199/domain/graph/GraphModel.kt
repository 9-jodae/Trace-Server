package com.jodae.docs199.domain.graph

import io.swagger.v3.oas.annotations.media.Schema
import tools.jackson.databind.ObjectMapper
import java.util.UUID

enum class RelationType { REFERENCES, CONFLICTS, TOPIC }

enum class DeclaredRelationType { REFERENCES, CONFLICTS }

data class ConflictDetail(
    val item: String,
    val thisValue: String,
    val otherValue: String,
) {
    fun reversed() = ConflictDetail(item, otherValue, thisValue)

    fun toJson(): String = mapper.writeValueAsString(mapOf("item" to item, "thisValue" to thisValue, "otherValue" to otherValue))

    companion object {
        private val mapper = ObjectMapper()

        fun fromJson(json: String): ConflictDetail {
            val node = mapper.readTree(json)
            return ConflictDetail(node.path("item").asString(), node.path("thisValue").asString(), node.path("otherValue").asString())
        }
    }
}

data class GraphOwnerDepartment(
    val id: UUID,
    val name: String,
)

data class GraphNode(
    val id: UUID,
    val title: String,
    val documentType: String?,
    val ownerDepartment: GraphOwnerDepartment?,
    val clearance: String,
    val currentVersion: Int,
    val versionCount: Int,
)

data class GraphEdge(
    val id: UUID,
    val source: UUID,
    val target: UUID,
    val type: RelationType,
    val label: String,
    @field:Schema(oneOf = [ConflictDetail::class, TopicDetail::class], nullable = true)
    val detail: Any?,
)

data class TopicDetail(
    val topics: List<String>,
)

data class GraphResponse(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
)

data class RelationRequest(
    val sourceDocumentId: UUID,
    val targetDocumentId: UUID,
    val type: DeclaredRelationType,
    val detail: ConflictDetail? = null,
)

data class RelationResponse(
    val id: UUID,
    val source: UUID,
    val target: UUID,
    val type: DeclaredRelationType,
    val detail: ConflictDetail?,
)
