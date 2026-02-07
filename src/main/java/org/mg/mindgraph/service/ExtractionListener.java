package org.mg.mindgraph.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.ai.KnowledgeExtractor;
import org.mg.mindgraph.config.rabbitmq.RabbitMQConfig;
import org.mg.mindgraph.dto.GraphData;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExtractionListener {

    private final KnowledgeExtractor knowledgeExtractor;
    private final GraphService graphService;
    private final VectorService vectorService;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NAME)
    public void onExtractionRequest(String text) {
        log.info("Processing extraction request for text starting with: '{}...'", text.substring(0, Math.min(text.length(), 50)));

        // 1. LLM을 통해 지식 그래프 추출
        // 2. 추출된 그래프 데이터 저장 (Transactional)
        // 3. 원본 텍스트를 임베딩하여 벡터 DB에 저장 (Transactional)
        // -> 이 과정에서 발생하는 예외는 이제 Spring AMQP가 처리하여, 재시도 후 DLQ로 보냅니다.
        GraphData graphData = knowledgeExtractor.extract(text);
        log.info("Extracted {} nodes and {} edges.", graphData.nodes().size(), graphData.edges().size());

        graphService.saveGraph(graphData);

        vectorService.embedAndSave(text);

        log.info("Successfully processed and saved graph and vector for text.");
    }
}
