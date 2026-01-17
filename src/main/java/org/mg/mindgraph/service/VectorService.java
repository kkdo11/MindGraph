package org.mg.mindgraph.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorService {

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 텍스트를 임베딩하여 벡터 스토어에 저장합니다.
     */
    @Transactional
    public void embedAndSave(String text) {
        // 1. 임베딩 생성 (Ollama 호출)
        Embedding embedding = embeddingModel.embed(text).content();
        
        // 2. 벡터 데이터를 문자열 형식([0.1, 0.2, ...])으로 변환
        String vectorString = embedding.vectorAsList().toString();

        // 3. Native SQL 실행 (pgvector 문법 사용)
        // metadata는 현재 비어있는 JSON '{}'으로 저장
        String sql = """
            INSERT INTO vector_store (id, content, metadata, embedding)
            VALUES (?, ?, ?::jsonb, ?::vector)
        """;

        jdbcTemplate.update(sql, 
                UUID.randomUUID(), 
                text, 
                "{}", 
                vectorString
        );
        
        log.info("Vector saved. Text length: {}, Vector dimension: {}", 
                 text.length(), embedding.dimension());
    }
}