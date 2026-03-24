package org.mg.mindgraph.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorService {

    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    /**
     * nodes 테이블에 name_embedding 컬럼을 추가합니다.
     * IF NOT EXISTS로 멱등 보장 — 이미 존재해도 오류 없음.
     * JPA ddl-auto=update는 vector 타입을 지원하지 않으므로 직접 DDL 실행.
     */
    @PostConstruct
    void initNodeEmbeddingSchema() {
        jdbcTemplate.execute(
                "ALTER TABLE nodes ADD COLUMN IF NOT EXISTS name_embedding vector(1024)"
        );
        log.info("nodes.name_embedding column ensured (vector(1024))");
    }

    // bge-m3: 8192 토큰 ≈ 한국어 약 6000자
    // 청크당 2000자 (의미 단위 충분히 포함), 오버랩 300자 (문맥 연속성)
    private static final int CHUNK_SIZE = 2000;
    private static final int CHUNK_OVERLAP = 300;

    /**
     * 텍스트를 청크로 분할하여 각각 임베딩 후 벡터 스토어에 저장합니다.
     * 짧은 텍스트는 그대로 저장, 긴 텍스트는 슬라이딩 윈도우 방식으로 분할합니다.
     */
    @Transactional
    public void embedAndSave(String text) {
        List<String> chunks = chunk(text);
        int skipped = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (isDuplicateChunk(chunk)) {
                log.debug("Duplicate chunk {}/{}, skipping.", i + 1, chunks.size());
                skipped++;
                continue;
            }
            embedAndInsert(chunk, i, chunks.size());
        }

        log.info("Vector saved. Total length: {}, Chunks: {}, Skipped(duplicate): {}",
                text.length(), chunks.size(), skipped);
    }

    /**
     * 동일 content가 vector_store에 이미 존재하는지 확인합니다.
     * 같은 텍스트를 두 번 입력해도 중복 저장되지 않습니다.
     */
    private boolean isDuplicateChunk(String chunk) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE content = ?", Integer.class, chunk);
        return count != null && count > 0;
    }

    /**
     * 텍스트를 슬라이딩 윈도우 방식으로 분할합니다.
     * CHUNK_SIZE 이하면 분할 없이 그대로 반환.
     *
     * <p>[버그 수정] 마지막 청크(end >= text.length()) 처리 후 break 추가.
     * 수정 전: start = end - CHUNK_OVERLAP 으로 start가 length-300에 고정되어 무한루프 발생.
     */
    private List<String> chunk(String text) {
        if (text.length() <= CHUNK_SIZE) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());

            // 문장 경계에서 자르기 (마침표/줄바꿈 찾기)
            if (end < text.length()) {
                int boundary = findSentenceBoundary(text, start, end);
                if (boundary > start) end = boundary;
            }

            chunks.add(text.substring(start, end).strip());

            if (end >= text.length()) break; // 마지막 청크 완료 — 이후는 중복이므로 종료
            start = end - CHUNK_OVERLAP;  // 오버랩으로 문맥 연속성 유지
        }

        return chunks;
    }

    /**
     * start~end 범위에서 가장 가까운 문장 경계(마침표, 줄바꿈)를 찾습니다.
     * 없으면 end를 그대로 반환.
     */
    private int findSentenceBoundary(String text, int start, int end) {
        for (int i = end; i > start + CHUNK_SIZE / 2; i--) {
            char c = text.charAt(i - 1);
            if (c == '.' || c == '。' || c == '\n' || c == '!' || c == '?') {
                return i;
            }
        }
        return end;
    }

    private void embedAndInsert(String chunk, int chunkIndex, int totalChunks) {
        Embedding embedding = embeddingModel.embed(chunk).content();

        String vectorString = embedding.vectorAsList().toString();
        String sql = """
            INSERT INTO vector_store (id, content, metadata, embedding)
            VALUES (?, ?, ?::jsonb, ?::vector)
        """;

        String metadata = String.format("{\"chunk\": %d, \"total\": %d}", chunkIndex, totalChunks);

        jdbcTemplate.update(sql,
                UUID.randomUUID(),
                chunk,
                metadata,
                vectorString
        );

        log.debug("Chunk {}/{} embedded. Length: {}, Dim: {}",
                chunkIndex + 1, totalChunks, chunk.length(), embedding.dimension());
    }
}
