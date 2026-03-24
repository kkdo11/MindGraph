package org.mg.mindgraph.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.dto.NodeInsight;
import org.mg.mindgraph.dto.SearchResult;
import org.mg.mindgraph.entity.Edge;
import org.mg.mindgraph.entity.Node;
import org.mg.mindgraph.repository.EdgeRepository;
import org.mg.mindgraph.repository.NodeRepository;
import org.mg.mindgraph.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;



@Slf4j
@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class MindGraphController {

    private final SearchService searchService;
    private final MindGraphService mindGraphService;
    private final ExtractionPublisher extractionPublisher;
    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;

    private static final int MIN_TEXT_LENGTH = 10;
    private static final int MAX_TEXT_LENGTH = 50_000;

    /**
     * 텍스트를 받아 비동기적으로 지식 그래프 추출 및 임베딩을 요청합니다.
     * @param text 원본 텍스트 (10자 이상, 50,000자 이하)
     * @return 202 Accepted
     */
    @PostMapping("/extract")
    public ResponseEntity<Map<String, String>> extractGraph(@RequestBody String text) {
        // [P2-3] 입력 검증 — 유효하지 않은 텍스트는 LLM 파이프라인 진입 전 차단
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "텍스트를 입력해주세요."));
        }
        if (text.trim().length() < MIN_TEXT_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "텍스트가 너무 짧습니다. (최소 " + MIN_TEXT_LENGTH + "자)"));
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "텍스트가 너무 깁니다. (최대 " + MAX_TEXT_LENGTH + "자)"));
        }
        extractionPublisher.publishExtractionRequest(text);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/search")
    public List<SearchResult> search(@RequestParam String query) {
        return searchService.search(query);
    }

    // 최종 질문 엔드포인트
    @PostMapping("/ask")
    public ResponseEntity<Map<String, String>> ask(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question은 필수 항목입니다."));
        }
        String answer = mindGraphService.ask(question);
        return ResponseEntity.ok(Map.of("answer", answer));
    }

    @PatchMapping("/nodes/{id}")
    public ResponseEntity<Void> updateNode(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        return nodeRepository.findById(id).map(node -> {
            if (body.containsKey("type")) node.setType(body.get("type"));
            if (body.containsKey("description")) node.setDescription(body.get("description"));
            nodeRepository.save(node);
            return ResponseEntity.ok().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * 지식 그래프의 중심성 분석 결과를 반환합니다.
     * Neo4j Degree Centrality 기준 상위 노드 목록을 제공합니다.
     *
     * @param limit 반환할 노드 수 (1~100, 기본값 10)
     */
    @GetMapping("/insights")
    public ResponseEntity<Map<String, Object>> getInsights(
            @RequestParam(defaultValue = "10") int limit) {
        if (limit < 1 || limit > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "limit은 1~100 사이여야 합니다."));
        }
        List<NodeInsight> insights = mindGraphService.getInsights(limit);
        return ResponseEntity.ok(Map.of("insights", insights, "count", insights.size()));
    }

    /**
     * [D-1] 기존 노드의 name_embedding을 일괄 생성합니다.
     * 새 임베딩 기반 그래프 검색 도입 후 1회 실행 필요.
     * name_embedding이 이미 있는 노드는 skipped로 처리.
     */
    @PostMapping("/rebuild-node-embeddings")
    public ResponseEntity<Map<String, Object>> rebuildNodeEmbeddings() {
        Map<String, Integer> result = mindGraphService.rebuildNodeEmbeddings();
        return ResponseEntity.ok(Map.of(
                "message", "노드 이름 임베딩 재생성 완료",
                "embedded", result.get("embedded"),
                "skipped", result.get("skipped")
        ));
    }

    /**
     * [P2-2] PostgreSQL → Neo4j 전체 재동기화.
     * Neo4j sync 실패가 누적된 경우 수동으로 호출하여 불일치를 복구합니다.
     */
    @PostMapping("/sync-neo4j")
    public ResponseEntity<Map<String, Object>> syncNeo4j() {
        Map<String, Integer> result = mindGraphService.syncAllToNeo4j();
        return ResponseEntity.ok(Map.of(
                "message", "Neo4j 재동기화 완료",
                "nodes", result.get("nodes"),
                "edges", result.get("edges")
        ));
    }

    @DeleteMapping("/nodes/{id}")
    public ResponseEntity<Void> deleteNode(@PathVariable Long id) {
        if (!nodeRepository.existsById(id)) return ResponseEntity.notFound().build();
        edgeRepository.deleteBySourceIdOrTargetId(id, id);
        nodeRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // UI 그래프 시각화용 — 전체 노드·엣지 반환
    @GetMapping("/nodes")
    public Map<String, Object> getGraphData() {
        List<Node> nodes = nodeRepository.findAll();
        List<Edge> edges = edgeRepository.findAllWithNodes();

        List<Map<String, Object>> nodeList = nodes.stream()
                .map(n -> Map.<String, Object>of(
                        "id", n.getId(),
                        "name", n.getName(),
                        "type", n.getType(),
                        "description", n.getDescription() != null ? n.getDescription() : ""))
                .collect(Collectors.toList());

        List<Map<String, Object>> edgeList = edges.stream()
                .map(e -> Map.<String, Object>of(
                        "id", e.getId(),
                        "sourceName", e.getSource().getName(),
                        "targetName", e.getTarget().getName(),
                        "relation", e.getRelation()))
                .collect(Collectors.toList());

        return Map.of("nodes", nodeList, "edges", edgeList);
    }
}