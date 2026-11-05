package com.jodae.docs199.domain.graph

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.UserPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class GraphService(
    private val repository: GraphRepository,
    private val policy: AccessPolicy,
) {
    @Transactional(readOnly = true)
    fun graph(
        principal: UserPrincipal,
        documentId: UUID?,
        depth: Int?,
    ): GraphResponse {
        if (documentId == null && depth != null) throw ApiException(ErrorCode.INVALID_INPUT)
        if (documentId != null && depth != null && depth !in 1..2) throw ApiException(ErrorCode.INVALID_INPUT)

        val scope = policy.scope(principal)
        val nodes = repository.accessibleNodes(scope)
        val visibleIds = nodes.mapTo(HashSet()) { it.id }
        if (documentId != null && documentId !in visibleIds) throw ApiException(ErrorCode.NOT_FOUND)

        val relations = repository.relationsBetweenAccessibleDocuments(scope)
        val selectedIds =
            if (documentId == null) {
                visibleIds
            } else {
                reachable(documentId, depth ?: 1, relations)
            }
        val selectedNodes = nodes.filter { it.id in selectedIds }.map { it.toNode() }
        val edges =
            relations
                .filter { it.source in selectedIds && it.target in selectedIds }
                .map { it.toEdge() }
        return GraphResponse(selectedNodes, edges)
    }

    @Transactional
    fun register(
        principal: UserPrincipal,
        request: RelationRequest,
    ): RelationResponse {
        val scope = policy.scope(principal)
        if (!repository.hasAccessibleDocument(scope, request.sourceDocumentId) ||
            !repository.hasAccessibleDocument(scope, request.targetDocumentId)
        ) {
            throw ApiException(ErrorCode.NOT_FOUND)
        }
        if (request.sourceDocumentId == request.targetDocumentId) throw ApiException(ErrorCode.INVALID_INPUT)

        val relation = normalize(request)
        val id = repository.upsert(relation)
        return RelationResponse(id, relation.source, relation.target, relation.type, relation.detail)
    }

    @Transactional
    fun delete(
        principal: UserPrincipal,
        relationId: UUID,
    ) {
        val scope = policy.scope(principal)
        val relation = repository.accessibleRelation(scope, relationId) ?: throw ApiException(ErrorCode.NOT_FOUND)
        repository.delete(relation.id)
    }

    private fun normalize(request: RelationRequest): GraphRelationRow {
        val detail =
            when (request.type) {
                RelationType.REFERENCES -> {
                    if (request.detail != null) throw ApiException(ErrorCode.INVALID_INPUT)
                    null
                }
                RelationType.CONFLICTS -> request.detail?.also(::validateDetail) ?: throw ApiException(ErrorCode.INVALID_INPUT)
            }
        if (request.type != RelationType.CONFLICTS) {
            return GraphRelationRow(UUID.randomUUID(), request.sourceDocumentId, request.targetDocumentId, request.type, null)
        }
        return if (request.sourceDocumentId.toString() <= request.targetDocumentId.toString()) {
            GraphRelationRow(UUID.randomUUID(), request.sourceDocumentId, request.targetDocumentId, request.type, detail)
        } else {
            GraphRelationRow(UUID.randomUUID(), request.targetDocumentId, request.sourceDocumentId, request.type, detail!!.reversed())
        }
    }

    private fun validateDetail(detail: ConflictDetail) {
        if (listOf(detail.item, detail.thisValue, detail.otherValue).any(String::isBlank)) {
            throw ApiException(ErrorCode.INVALID_INPUT)
        }
    }

    private fun reachable(
        center: UUID,
        depth: Int,
        relations: List<GraphRelationRow>,
    ): Set<UUID> {
        val adjacency = HashMap<UUID, MutableSet<UUID>>()
        relations.forEach { relation ->
            adjacency.getOrPut(relation.source, ::mutableSetOf).add(relation.target)
            adjacency.getOrPut(relation.target, ::mutableSetOf).add(relation.source)
        }
        val visited = mutableSetOf(center)
        var frontier = setOf(center)
        repeat(depth) {
            frontier = frontier.flatMap { adjacency[it].orEmpty() }.filterNot(visited::contains).toSet()
            visited.addAll(frontier)
        }
        return visited
    }

    private fun GraphNodeRow.toNode() =
        GraphNode(
            id,
            title,
            documentType,
            if (ownerDepartmentId != null &&
                ownerDepartmentName != null
            ) {
                GraphOwnerDepartment(ownerDepartmentId, ownerDepartmentName)
            } else {
                null
            },
            clearance.name,
            currentVersion,
            versionCount,
        )

    private fun GraphRelationRow.toEdge() = GraphEdge(id, source, target, type, if (type == RelationType.REFERENCES) "참조" else "충돌", detail)
}
