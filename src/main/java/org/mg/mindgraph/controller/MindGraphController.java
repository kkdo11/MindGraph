package org.mg.mindgraph.controller;

import lombok.RequiredArgsConstructor;
import org.mg.mindgraph.ai.KnowledgeExtractor;
import org.mg.mindgraph.dto.GraphData;
import org.mg.mindgraph.dto.SearchResult;
import org.mg.mindgraph.service.GraphService;
import org.mg.mindgraph.service.MindGraphService; // 추가
import org.mg.mindgraph.service.SearchService;
import org.mg.mindgraph.service.VectorService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class MindGraphController {

    private final KnowledgeExtractor knowledgeExtractor;
    private final GraphService graphService;
    private final VectorService vectorService;
    private final SearchService searchService;
    private final MindGraphService mindGraphService; // 오케스트레이터 주입

    // ... 기존 메서드들 (extractGraph, search) ...

    @PostMapping("/extract")
    public GraphData extractGraph(@RequestBody String text) {
        GraphData graphData = knowledgeExtractor.extract(text);
        graphService.saveGraph(graphData);
        vectorService.embedAndSave(text);
        return graphData;
    }

    @GetMapping("/search")
    public List<SearchResult> search(@RequestParam String query) {
        return searchService.search(query);
    }

    // 최종 질문 엔드포인트
    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        String answer = mindGraphService.ask(question);
        return Map.of("answer", answer);
    }
}