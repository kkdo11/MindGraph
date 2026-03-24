package org.mg.mindgraph.service;

import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.dto.GraphData;
import org.mg.mindgraph.entity.Edge;
import org.mg.mindgraph.entity.Node;
import org.mg.mindgraph.repository.NodeRepository;
import org.mg.mindgraph.repository.EdgeRepository;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphService {

    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;
    private final Neo4jClient neo4jClient;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    @Value("${mindgraph.node.merge-threshold:0.95}")
    private double mergeThreshold;

    @Value("${mindgraph.node.candidate-threshold:0.80}")
    private double candidateThreshold;


    /**
     * 추출된 그래프 데이터를 DB에 저장합니다. (중복 방지 로직 포함)
     */
    @Transactional
    public void saveGraph(GraphData graphData) {
        // 1. 노드 저장 또는 조회 (이름 기반 캐싱을 위해 Map 사용)
        Map<String, Node> savedNodes = processNodes(graphData);

        // 2. 엣지 저장 (노드 참조 연결)
        processEdges(graphData, savedNodes);
        
        log.info("Graph data processing completed. Nodes processed: {}, Edges processed: {}",
                 graphData.nodes().size(), graphData.edges().size());

        // Neo4j 동기화 — 독립 try-catch: Neo4j 실패해도 PostgreSQL 커밋 보장
        try {
            syncToNeo4j(graphData);
        } catch (Exception e) {
            log.warn("Neo4j sync failed (PostgreSQL commit unaffected): {}", e.getMessage());
        }
    }

    private Map<String, Node> processNodes(GraphData graphData) {
        Map<String, Node> nodeMap = new HashMap<>();

        for (GraphData.NodeDTO nodeDTO : graphData.nodes()) {
            // [P0-3] name 기준으로 먼저 찾아 같은 이름의 노드가 type만 다르게 중복 생성되는 문제 방지
            // (기존: findByNameAndType → "Docker"/Technology 와 "Docker"/Database 가 별개 노드로 분열)
            Node node = nodeRepository.findFirstByName(nodeDTO.name())
                    .map(existing -> updateDescriptionIfRicher(existing, nodeDTO))
                    .orElseGet(() -> findOrCreateByEmbedding(nodeDTO));

            // 이름 임베딩 저장 — 독립 try-catch: 실패해도 JPA 트랜잭션 보장
            saveNameEmbedding(node.getId(), node.getName());

            // 엣지 연결을 위해 메모리에 임시 저장 (Key: 노드 이름)
            nodeMap.put(nodeDTO.name(), node);
        }
        return nodeMap;
    }

    /**
     * description이 더 풍부한 경우에만 기존 노드를 업데이트합니다.
     */
    private Node updateDescriptionIfRicher(Node existing, GraphData.NodeDTO nodeDTO) {
        boolean hasNewDesc = nodeDTO.description() != null && !nodeDTO.description().isBlank();
        boolean missingDesc = existing.getDescription() == null || existing.getDescription().isBlank();
        boolean newIsRicher = hasNewDesc && !missingDesc
                && nodeDTO.description().length() > existing.getDescription().length();
        if (hasNewDesc && (missingDesc || newIsRicher)) {
            existing.setDescription(nodeDTO.description());
            return nodeRepository.save(existing);
        }
        return existing;
    }

    /**
     * [F-3] exact name match 실패 시 임베딩 유사도로 기존 노드를 찾습니다.
     * - 0.95 이상: 자동 병합 (대소문자, 한/영 표기 차이)
     * - 0.80~0.95: 병합 후보로 로그 기록
     * - 0.80 미만 or 검색 실패: 새 노드 생성
     */
    private Node findOrCreateByEmbedding(GraphData.NodeDTO nodeDTO) {
        try {
            String vec = embeddingModel.embed(nodeDTO.name()).content().vectorAsList().toString();
            List<Object[]> similar = nodeRepository.findSimilarWithScore(vec, candidateThreshold, 1);

            if (!similar.isEmpty()) {
                Object[] row = similar.get(0);
                Long id = ((Number) row[0]).longValue();
                String existingName = (String) row[1];
                double similarity = ((Number) row[2]).doubleValue();

                if (similarity >= mergeThreshold) {
                    // 자동 병합 — 거의 같은 단어의 표기 차이
                    Node existing = nodeRepository.findById(id).orElse(null);
                    if (existing != null) {
                        log.info("Node '{}' merged into existing '{}' (similarity={})",
                                nodeDTO.name(), existingName, similarity);
                        return updateDescriptionIfRicher(existing, nodeDTO);
                    }
                } else {
                    // 후보 탐지 — 유사하지만 자동 병합하기엔 불확실
                    log.info("Merge candidate: '{}' ≈ '{}' (similarity={}, threshold={})",
                            nodeDTO.name(), existingName, similarity, mergeThreshold);
                }
            }
        } catch (Exception e) {
            log.warn("Embedding similarity search failed for '{}': {}", nodeDTO.name(), e.getMessage());
        }

        // 새 노드 생성
        return nodeRepository.save(
                Node.builder()
                        .name(nodeDTO.name())
                        .type(nodeDTO.type())
                        .description(nodeDTO.description())
                        .build()
        );
    }

    /**
     * 노드 이름을 임베딩하여 nodes.name_embedding에 저장합니다.
     * Neo4j 동기화와 동일한 독립 try-catch 패턴 — 임베딩 실패가 그래프 저장을 막지 않음.
     */
    void saveNameEmbedding(Long nodeId, String name) {
        try {
            String vec = embeddingModel.embed(name).content().vectorAsList().toString();
            jdbcTemplate.update(
                    "UPDATE nodes SET name_embedding = ?::vector WHERE id = ?", vec, nodeId);
            log.debug("Name embedding saved for node '{}' (id={})", name, nodeId);
        } catch (Exception e) {
            log.warn("Name embedding failed for node '{}': {}", name, e.getMessage());
        }
    }

    /**
     * [마이그레이션] name_embedding이 없는 노드를 일괄 임베딩합니다.
     * POST /api/graph/rebuild-node-embeddings 엔드포인트에서 호출.
     *
     * @return {"embedded": N, "skipped": M}
     */
    public Map<String, Integer> rebuildNodeEmbeddings() {
        List<Node> pending = nodeRepository.findNodesWithoutEmbedding();
        int embedded = 0;
        int skipped = 0;

        for (Node node : pending) {
            try {
                saveNameEmbedding(node.getId(), node.getName());
                embedded++;
            } catch (Exception e) {
                log.warn("Skipping node '{}' during rebuild: {}", node.getName(), e.getMessage());
                skipped++;
            }
        }

        log.info("Rebuild node embeddings complete. embedded={}, skipped={}", embedded, skipped);
        return Map.of("embedded", embedded, "skipped", skipped);
    }

    private void processEdges(GraphData graphData, Map<String, Node> savedNodes) {
        for (GraphData.EdgeDTO edgeDTO : graphData.edges()) {
            Node sourceNode = savedNodes.get(edgeDTO.sourceName());
            Node targetNode = savedNodes.get(edgeDTO.targetName());

            // 유효성 검사: 양쪽 노드가 모두 식별되었을 때만 엣지 생성
            if (sourceNode != null && targetNode != null) {
                // [P1-1] relation 정규화 — "USES" / "uses" / "Use" 를 동일 관계로 처리
                String relation = edgeDTO.relation().trim().toLowerCase();

                boolean exists = edgeRepository.existsBySourceIdAndTargetIdAndRelation(
                        sourceNode.getId(), targetNode.getId(), relation);

                if (!exists) {
                    edgeRepository.save(Edge.builder()
                            .source(sourceNode)
                            .target(targetNode)
                            .relation(relation)
                            .build());
                }
            } else {
                log.warn("Skipping edge creation: Node not found for relationship '{}' -> '{}'",
                         edgeDTO.sourceName(), edgeDTO.targetName());
            }
        }
    }
    /**
     * PostgreSQL 지식 그래프를 Neo4j에 동기화합니다.
     * MERGE 사용으로 중복 없이 생성/업데이트.
     */
    private void syncToNeo4j(GraphData graphData) {
        // 1. 노드 MERGE — [P0-2] description도 저장 (기존 값 있으면 유지)
        for (GraphData.NodeDTO node : graphData.nodes()) {
            neo4jClient.query("""
                    MERGE (n:KnowledgeNode {name: $name})
                    ON CREATE SET n.type = $type, n.description = $description
                    ON MATCH SET n.type = $type,
                                 n.description = CASE WHEN n.description IS NULL
                                                      THEN $description
                                                      ELSE n.description END
                    """)
                    .bind(node.name()).to("name")
                    .bind(node.type()).to("type")
                    .bind(node.description() != null ? node.description() : "").to("description")
                    .run();
        }

        // 2. 관계 MERGE
        for (GraphData.EdgeDTO edge : graphData.edges()) {
            neo4jClient.query("""
                    MATCH (s:KnowledgeNode {name: $sourceName})
                    MATCH (t:KnowledgeNode {name: $targetName})
                    MERGE (s)-[r:RELATED_TO {relation: $relation}]->(t)
                    """)
                    .bind(edge.sourceName()).to("sourceName")
                    .bind(edge.targetName()).to("targetName")
                    .bind(edge.relation()).to("relation")
                    .run();
        }

        log.info("Neo4j sync complete. Nodes: {}, Edges: {}",
                graphData.nodes().size(), graphData.edges().size());
    }

    /**
     * [P2-2] PostgreSQL 전체 노드·엣지를 Neo4j에 재동기화합니다.
     * Neo4j sync 실패가 누적된 경우 수동으로 호출하여 불일치를 복구합니다.
     *
     * @return 동기화된 노드·엣지 수
     */
    public Map<String, Integer> syncAllToNeo4j() {
        List<Node> nodes = nodeRepository.findAll();
        List<Edge> edges = edgeRepository.findAllWithNodes();

        int nodeCount = 0;
        int edgeCount = 0;

        for (Node node : nodes) {
            try {
                neo4jClient.query("""
                        MERGE (n:KnowledgeNode {name: $name})
                        ON CREATE SET n.type = $type, n.description = $description
                        ON MATCH SET n.type = $type,
                                     n.description = CASE WHEN n.description IS NULL
                                                          THEN $description
                                                          ELSE n.description END
                        """)
                        .bind(node.getName()).to("name")
                        .bind(node.getType()).to("type")
                        .bind(node.getDescription() != null ? node.getDescription() : "").to("description")
                        .run();
                nodeCount++;
            } catch (Exception e) {
                log.warn("Neo4j resync failed for node '{}': {}", node.getName(), e.getMessage());
            }
        }

        for (Edge edge : edges) {
            try {
                neo4jClient.query("""
                        MATCH (s:KnowledgeNode {name: $sourceName})
                        MATCH (t:KnowledgeNode {name: $targetName})
                        MERGE (s)-[r:RELATED_TO {relation: $relation}]->(t)
                        """)
                        .bind(edge.getSource().getName()).to("sourceName")
                        .bind(edge.getTarget().getName()).to("targetName")
                        .bind(edge.getRelation()).to("relation")
                        .run();
                edgeCount++;
            } catch (Exception e) {
                log.warn("Neo4j resync failed for edge '{}->{}: {}",
                         edge.getSource().getName(), edge.getTarget().getName(), e.getMessage());
            }
        }

        log.info("Neo4j full resync complete. Nodes: {}/{}, Edges: {}/{}",
                nodeCount, nodes.size(), edgeCount, edges.size());
        return Map.of("nodes", nodeCount, "edges", edgeCount);
    }

    @Transactional(readOnly = true)
    public String getRelatedKnowledge(String keyword) {
        List<Edge> edges = edgeRepository.findEdgesByNodeName(keyword);

        if (edges.isEmpty()) {
            return "";
        }

        StringBuilder knowledge = new StringBuilder();
        knowledge.append(String.format("### '%s' 관련 지식 그래프:\n", keyword));

        for (Edge edge : edges) {
            knowledge.append(String.format("- %s(%s)은(는) %s(%s)와(과) '%s' 관계가 있습니다.\n",
                    edge.getSource().getName(), edge.getSource().getType(),
                    edge.getTarget().getName(), edge.getTarget().getType(),
                    edge.getRelation()));
        }

        return knowledge.toString();
    }
}