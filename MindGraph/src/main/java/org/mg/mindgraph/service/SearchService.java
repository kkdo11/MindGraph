package org.mg.mindgraph.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.dto.SearchResult;
import org.springframework.beans.factory.annotation.Value;
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

    // application.properties에서 주입 — 기본값은 코드 내 fallback으로 명시
    @Value("${mindgraph.search.vector-limit:5}")
    private int vectorLimit;

    @Value("${mindgraph.search.vector-threshold:0.3}")
    private double vectorThreshold;

    /**
     * 기본 설정값을 사용하는 벡터 유사도 검색.
     * application.properties의 mindgraph.search.* 값을 따름.
     */
    @Transactional(readOnly = true)
    public List<SearchResult> search(String query) {
        return search(query, vectorLimit, vectorThreshold);
    }

    /**
     * limit과 threshold를 직접 지정하는 벡터 유사도 검색.
     *
     * @param query     검색 질의
     * @param limit     반환할 최대 결과 수
     * @param threshold 최소 유사도 임계값 (0~1, 이하 결과 제외)
     */
    @Transactional(readOnly = true)
    public List<SearchResult> search(String query, int limit, double threshold) {
        // 1. 질문(Query)을 벡터로 변환
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        String vectorString = queryEmbedding.vectorAsList().toString();

        // 2. 유사도 필터링 + 상위 N개 반환
        // threshold, limit은 설정값이므로 String.format으로 SQL에 직접 삽입 (SQL 인젝션 위험 없음)
        String sql = String.format("""
            SELECT content, score FROM (
                SELECT content, 1 - (embedding <=> ?::vector) as score
                FROM vector_store
            ) sub
            WHERE score >= %.4f
            ORDER BY score DESC
            LIMIT %d
        """, threshold, limit);

        log.debug("Vector search: limit={}, threshold={}", limit, threshold);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new SearchResult(
                rs.getString("content"),
                rs.getDouble("score")
        ), vectorString);
    }
}