package org.mg.mindgraph.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.ai.KnowledgeExtractor;
import org.mg.mindgraph.config.rabbitmq.RabbitMQConfig;
import org.mg.mindgraph.dto.GraphData;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionListener {

    private final KnowledgeExtractor knowledgeExtractor;
    private final GraphService graphService;
    private final VectorService vectorService;

    // [P1-3] 긴 텍스트 청킹 기준 — VectorService와 동일 단위 사용
    private static final int EXTRACTION_CHUNK_SIZE = 2000;
    private static final int EXTRACTION_CHUNK_OVERLAP = 200;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void onExtractionRequest(String text) {
        log.info("Processing extraction request ({}자)...", text.length());

        // [P1-2] Step 1: 그래프 추출 + 저장 — 독립 try-catch
        // 실패해도 Step 2(원문 벡터 저장)는 항상 실행되어 원문 유실을 방지한다.
        try {
            GraphData graphData = extractWithChunking(text);
            log.info("Extracted {} nodes, {} edges.", graphData.nodes().size(), graphData.edges().size());
            graphService.saveGraph(graphData);
        } catch (Exception e) {
            log.warn("Graph extraction/save failed — proceeding to embed original text: {}", e.getMessage());
        }

        // [P1-2] Step 2: 원문 임베딩 저장 — 항상 실행
        vectorService.embedAndSave(text);
        log.info("Successfully processed text ({}자).", text.length());
    }

    /**
     * [P1-3] 텍스트 길이에 따라 LLM 추출 방식을 결정합니다.
     * - EXTRACTION_CHUNK_SIZE 이하: 단일 추출
     * - 초과: 슬라이딩 윈도우로 청킹 후 각 청크 추출 → 결과 병합 (노드/엣지 중복 제거)
     */
    private GraphData extractWithChunking(String text) {
        if (text.length() <= EXTRACTION_CHUNK_SIZE) {
            return knowledgeExtractor.extract(text);
        }

        log.info("Long text ({}자 > {}자), chunking for extraction.", text.length(), EXTRACTION_CHUNK_SIZE);
        List<String> chunks = chunkText(text);

        List<GraphData.NodeDTO> mergedNodes = new ArrayList<>();
        List<GraphData.EdgeDTO> mergedEdges = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        Set<String> seenEdges = new HashSet<>(); // "source|target|relation"

        for (int i = 0; i < chunks.size(); i++) {
            try {
                GraphData chunkData = knowledgeExtractor.extract(chunks.get(i));

                for (GraphData.NodeDTO node : chunkData.nodes()) {
                    if (seenNames.add(node.name())) {
                        mergedNodes.add(node);
                    }
                }
                for (GraphData.EdgeDTO edge : chunkData.edges()) {
                    String key = edge.sourceName() + "|" + edge.targetName() + "|"
                            + edge.relation().trim().toLowerCase();
                    if (seenEdges.add(key)) {
                        mergedEdges.add(edge);
                    }
                }
                log.debug("Chunk {}/{}: {} nodes, {} edges extracted.",
                        i + 1, chunks.size(), chunkData.nodes().size(), chunkData.edges().size());
            } catch (Exception e) {
                log.warn("Chunk {}/{} extraction failed, skipping: {}", i + 1, chunks.size(), e.getMessage());
            }
        }

        log.info("Chunked extraction complete. Merged: {} nodes, {} edges.", mergedNodes.size(), mergedEdges.size());
        return new GraphData(mergedNodes, mergedEdges);
    }

    /**
     * 슬라이딩 윈도우 방식으로 텍스트를 청킹합니다.
     */
    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + EXTRACTION_CHUNK_SIZE, text.length());
            chunks.add(text.substring(start, end));
            if (end >= text.length()) break;
            start = end - EXTRACTION_CHUNK_OVERLAP;
        }

        return chunks;
    }
}
