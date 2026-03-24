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
    private static final int CHUNK_SIZE = 2000;
    private static final int MIN_CHUNK_SIZE = 500;  // 이보다 짧은 단락은 인접 단락과 병합

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
     * 텍스트를 의미 단위(단락)로 분할합니다.
     * 1) \n\n 기준 단락 분리
     * 2) 작은 단락(MIN_CHUNK_SIZE 미만)은 인접 단락과 병합
     * 3) 큰 단락(CHUNK_SIZE 초과)은 문장 경계에서 분할 (fallback)
     * 4) 오버랩: 이전 청크의 마지막 문장을 다음 청크 앞에 포함
     */
    List<String> chunk(String text) {
        if (text.length() <= CHUNK_SIZE) {
            return List.of(text);
        }

        // 1단계: 단락 분리
        String[] paragraphs = text.split("\n\n+");

        // 2단계: 인접 단락 병합 (MIN_CHUNK_SIZE~CHUNK_SIZE 범위로)
        List<String> merged = mergeParagraphs(paragraphs);

        // 3단계: CHUNK_SIZE 초과 블록은 문장 경계에서 재분할
        List<String> split = new ArrayList<>();
        for (String block : merged) {
            if (block.length() <= CHUNK_SIZE) {
                split.add(block);
            } else {
                split.addAll(splitBysentenceBoundary(block));
            }
        }

        // 4단계: 오버랩 — 이전 청크의 마지막 문장을 다음 청크 앞에 추가
        return applyOverlap(split);
    }

    /**
     * 단락들을 CHUNK_SIZE 이내로 인접 병합합니다.
     * MIN_CHUNK_SIZE 미만 단락은 다음 단락과 합칩니다.
     */
    private List<String> mergeParagraphs(String[] paragraphs) {
        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.strip();
            if (trimmed.isEmpty()) continue;

            if (buffer.isEmpty()) {
                buffer.append(trimmed);
            } else if (buffer.length() + trimmed.length() + 2 <= CHUNK_SIZE) {
                buffer.append("\n\n").append(trimmed);
            } else {
                merged.add(buffer.toString());
                buffer.setLength(0);
                buffer.append(trimmed);
            }
        }
        if (!buffer.isEmpty()) {
            // 마지막 버퍼가 너무 짧으면 이전 청크에 합침
            if (buffer.length() < MIN_CHUNK_SIZE && !merged.isEmpty()) {
                String last = merged.remove(merged.size() - 1);
                if (last.length() + buffer.length() + 2 <= CHUNK_SIZE) {
                    merged.add(last + "\n\n" + buffer);
                } else {
                    merged.add(last);
                    merged.add(buffer.toString());
                }
            } else {
                merged.add(buffer.toString());
            }
        }
        return merged;
    }

    /**
     * CHUNK_SIZE 초과 텍스트를 문장 경계에서 분할합니다 (fallback).
     */
    private List<String> splitBysentenceBoundary(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());

            if (end < text.length()) {
                int boundary = findSentenceBoundary(text, start, end);
                if (boundary > start) end = boundary;
            }

            chunks.add(text.substring(start, end).strip());
            if (end >= text.length()) break;
            start = end;
        }

        return chunks;
    }

    /**
     * 이전 청크의 마지막 문장을 다음 청크 앞에 추가하여 문맥 연속성을 유지합니다.
     */
    private List<String> applyOverlap(List<String> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String lastSentence = extractLastSentence(chunks.get(i - 1));
            String current = chunks.get(i);
            // 오버랩이 현재 청크와 중복되지 않고, 합쳐도 CHUNK_SIZE 이내일 때만
            if (!lastSentence.isEmpty()
                    && !current.startsWith(lastSentence)
                    && lastSentence.length() + current.length() + 1 <= CHUNK_SIZE) {
                result.add(lastSentence + "\n" + current);
            } else {
                result.add(current);
            }
        }
        return result;
    }

    /**
     * 텍스트의 마지막 문장을 추출합니다.
     */
    private String extractLastSentence(String text) {
        // 마지막 문장부호 위치부터 역탐색
        int end = text.length();
        for (int i = end - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '。' || c == '!' || c == '?' || c == '\n') {
                // 이 문장부호 다음부터 끝까지가 마지막 문장
                String candidate = text.substring(i + 1).strip();
                if (!candidate.isEmpty()) return candidate;
                // 빈 문자열이면 (텍스트가 문장부호로 끝남) — 그 앞 문장 찾기
                end = i;
            }
        }
        // 문장부호가 없으면 전체가 하나의 문장 — 너무 길면 포기
        return text.length() <= 200 ? text : "";
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
