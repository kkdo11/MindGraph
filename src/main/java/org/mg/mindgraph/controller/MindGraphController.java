package org.mg.mindgraph.controller;

import lombok.RequiredArgsConstructor;
import org.mg.mindgraph.dto.SearchResult;
import org.mg.mindgraph.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graph")
@RequiredArgsConstructor
public class MindGraphController {

    private final SearchService searchService;
    private final MindGraphService mindGraphService; // 오케스트레이터 주입
    private final ExtractionPublisher extractionPublisher; // 비동기 추출 요청 발행자

    /**
     * 텍스트를 받아 비동기적으로 지식 그래프 추출 및 임베딩을 요청합니다.
     * @param text 원본 텍스트
     * @return 202 Accepted
     */
    @PostMapping("/extract")
    public ResponseEntity<Void> extractGraph(@RequestBody String text) {
        extractionPublisher.publishExtractionRequest(text);
        return ResponseEntity.accepted().build();
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