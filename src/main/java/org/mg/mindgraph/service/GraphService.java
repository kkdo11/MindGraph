package org.mg.mindgraph.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.dto.GraphData;
import org.mg.mindgraph.entity.Edge;
import org.mg.mindgraph.entity.Node;
import org.mg.mindgraph.repository.NodeRepository;
import org.mg.mindgraph.repository.EdgeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GraphService {

    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;


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
    }

    private Map<String, Node> processNodes(GraphData graphData) {
        Map<String, Node> nodeMap = new HashMap<>();

        for (GraphData.NodeDTO nodeDTO : graphData.nodes()) {
            // DB에 이미 존재하는지 확인 (Find or Create)
            Node node = nodeRepository.findByNameAndType(nodeDTO.name(), nodeDTO.type())
                    .orElseGet(() -> nodeRepository.save(
                            Node.builder()
                                    .name(nodeDTO.name())
                                    .type(nodeDTO.type())
                                    .description(nodeDTO.description())
                                    .build()
                    ));
            
            // 엣지 연결을 위해 메모리에 임시 저장 (Key: 노드 이름)
            nodeMap.put(nodeDTO.name(), node);
        }
        return nodeMap;
    }

    private void processEdges(GraphData graphData, Map<String, Node> savedNodes) {
        for (GraphData.EdgeDTO edgeDTO : graphData.edges()) {
            Node sourceNode = savedNodes.get(edgeDTO.sourceName());
            Node targetNode = savedNodes.get(edgeDTO.targetName());

            // 유효성 검사: 양쪽 노드가 모두 식별되었을 때만 엣지 생성
            if (sourceNode != null && targetNode != null) {
                // 관계 중복 확인
                boolean exists = edgeRepository.existsBySourceIdAndTargetIdAndRelation(
                        sourceNode.getId(), targetNode.getId(), edgeDTO.relation());

                if (!exists) {
                    edgeRepository.save(Edge.builder()
                            .source(sourceNode)
                            .target(targetNode)
                            .relation(edgeDTO.relation())
                            .build());
                }
            } else {
                log.warn("Skipping edge creation: Node not found for relationship '{}' -> '{}'", 
                         edgeDTO.sourceName(), edgeDTO.targetName());
            }
        }
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