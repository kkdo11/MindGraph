package org.mg.mindgraph.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.dto.SearchResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public List<SearchResult> search(String query) {
        // 1. 질문(Query)을 벡터로 변환
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        String vectorString = queryEmbedding.vectorAsList().toString();

        // 2. 벡터 유사도 검색 (Cosine Distance)
        // <=> 연산자는 코사인 '거리'를 반환하므로, '유사도'는 (1 - 거리)로 계산합니다.
        String sql = """
            SELECT content, 1 - (embedding <=> ?::vector) as score
            FROM vector_store
            ORDER BY score DESC
            LIMIT 5
        """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> new SearchResult(
                rs.getString("content"),
                rs.getDouble("score")
        ), vectorString);
    }
}