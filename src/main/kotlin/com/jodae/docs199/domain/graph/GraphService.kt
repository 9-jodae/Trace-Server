package com.jodae.docs199.domain.graph

import com.jodae.docs199.global.error.ApiException
import com.jodae.docs199.global.error.ErrorCode
import com.jodae.docs199.global.security.AccessPolicy
import com.jodae.docs199.global.security.UserPrincipal
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.util.UUID

@Service
class GraphService(
    private val repository: GraphRepository,
    private val policy: AccessPolicy,
    @Value("\${app.graph.topic-edges-enabled:false}") private val topicEdgesEnabled: Boolean,
    @Value("\${app.graph.max-topic-edges:1000}") private val maxTopicEdges: Int,
) {
    companion object {
        private const val MAX_DOCUMENTS_PER_TOPIC = 50
    }

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
        val topicEdges =
            if (topicEdgesEnabled) buildTopicEdges(repository.topicsForAccessibleCurrentDocuments(scope)) else emptyList()
        val selectedIds =
            if (documentId == null) {
                visibleIds
            } else {
                reachable(documentId, depth ?: 1, relations, topicEdges)
            }
        val selectedNodes = nodes.filter { it.id in selectedIds }.map { it.toNode() }
        val edges =
            relations.filter { it.source in selectedIds && it.target in selectedIds }.map { it.toEdge() } +
                topicEdges.filter { it.source in selectedIds && it.target in selectedIds }
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
        return RelationResponse(id, relation.source, relation.target, request.type, relation.detail)
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
                DeclaredRelationType.REFERENCES -> {
                    if (request.detail != null) throw ApiException(ErrorCode.INVALID_INPUT)
                    null
                }
                DeclaredRelationType.CONFLICTS -> request.detail?.also(::validateDetail) ?: throw ApiException(ErrorCode.INVALID_INPUT)
            }
        if (request.type == DeclaredRelationType.REFERENCES) {
            return GraphRelationRow(UUID.randomUUID(), request.sourceDocumentId, request.targetDocumentId, RelationType.REFERENCES, null)
        }
        return if (request.sourceDocumentId.toString() <= request.targetDocumentId.toString()) {
            GraphRelationRow(UUID.randomUUID(), request.sourceDocumentId, request.targetDocumentId, RelationType.CONFLICTS, detail)
        } else {
            GraphRelationRow(
                UUID.randomUUID(),
                request.targetDocumentId,
                request.sourceDocumentId,
                RelationType.CONFLICTS,
                detail!!.reversed(),
            )
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
        topicEdges: List<GraphEdge>,
    ): Set<UUID> {
        val adjacency = HashMap<UUID, MutableSet<UUID>>()
        (relations.map { it.source to it.target } + topicEdges.map { it.source to it.target }).forEach { (source, target) ->
            adjacency.getOrPut(source, ::mutableSetOf).add(target)
            adjacency.getOrPut(target, ::mutableSetOf).add(source)
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

    private fun buildTopicEdges(rows: List<GraphDocumentTopicRow>): List<GraphEdge> {
        val edgeLimit = maxTopicEdges.coerceAtLeast(0)
        if (edgeLimit == 0) return emptyList()

        val topicsByPair =
            sortedMapOf<Pair<UUID, UUID>, MutableSet<String>>(compareBy({ it.first.toString() }, { it.second.toString() }))
        rows.groupBy { it.topic }.toSortedMap().forEach { (topic, topicRows) ->
            val documents = topicRows.map { it.documentId }.distinct().sortedBy(UUID::toString)
            if (documents.size < 2 || documents.size > MAX_DOCUMENTS_PER_TOPIC) return@forEach

            for (sourceIndex in 0 until documents.lastIndex) {
                for (targetIndex in sourceIndex + 1 until documents.size) {
                    val pair = documents[sourceIndex] to documents[targetIndex]
                    val sharedTopics = topicsByPair[pair]
                    if (sharedTopics != null) {
                        sharedTopics += topic
                    } else if (topicsByPair.size < edgeLimit) {
                        topicsByPair[pair] = sortedSetOf(topic)
                    }
                }
            }
        }

        return topicsByPair.map { (pair, topics) ->
            val (source, target) = pair
            val orderedTopics = topics.toList()
            GraphEdge(
                id = UUID.nameUUIDFromBytes("docs199:topic:$source:$target".toByteArray(StandardCharsets.UTF_8)),
                source = source,
                target = target,
                type = RelationType.TOPIC,
                label = orderedTopics.joinToString(" · "),
                detail = TopicDetail(orderedTopics),
            )
        }
    }

    private fun GraphRelationRow.toEdge() =
        GraphEdge(
            id,
            source,
            target,
            type,
            if (type == RelationType.REFERENCES) "참조" else "충돌",
            detail,
        )
}
