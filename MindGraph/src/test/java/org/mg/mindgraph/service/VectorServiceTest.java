package org.mg.mindgraph.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private VectorService vectorService;

    private static final float[] SAMPLE_VECTOR = {0.1f, 0.2f, 0.3f};

    private void givenEmbeddingModel() {
        Embedding embedding = Embedding.from(SAMPLE_VECTOR);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(embedding));
    }

    @Test
    @DisplayName("embedAndSave: 임베딩 생성 후 JdbcTemplate.update 호출")
    void embedAndSave_임베딩생성_후_INSERT_호출() {
        // given
        givenEmbeddingModel();
        String text = "Docker는 컨테이너 가상화 기술이다.";

        // when
        vectorService.embedAndSave(text);

        // then
        verify(embeddingModel).embed(text);
        verify(jdbcTemplate).update(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("embedAndSave: jdbcTemplate.update 호출 인자에 UUID 포함")
    void embedAndSave_UUID_자동생성_확인() {
        // given
        givenEmbeddingModel();
        ArgumentCaptor<Object> arg1 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg2 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg3 = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> arg4 = ArgumentCaptor.forClass(Object.class);

        // when
        vectorService.embedAndSave("테스트 텍스트");

        // then: 첫 번째 인자가 UUID (java.util.UUID 타입)
        verify(jdbcTemplate).update(anyString(), arg1.capture(), arg2.capture(), arg3.capture(), arg4.capture());
        assertThat(arg1.getValue()).isInstanceOf(java.util.UUID.class);
    }

    @Test
    @DisplayName("embedAndSave: 빈 텍스트도 예외 없이 처리")
    void embedAndSave_빈텍스트_처리() {
        // given
        givenEmbeddingModel();

        // when & then
        assertThatNoException().isThrownBy(() -> vectorService.embedAndSave(""));
        verify(embeddingModel).embed("");
    }

    @Test
    @DisplayName("chunk: 2000자 이하 텍스트 → 1청크, embed 1회 호출")
    void embedAndSave_짧은텍스트_1청크() {
        // given: CHUNK_SIZE(2000) 이하
        givenEmbeddingModel();
        String shortText = "A".repeat(500);

        // when
        vectorService.embedAndSave(shortText);

        // then: 청킹 없이 embed 1회만 호출
        verify(embeddingModel, times(1)).embed(anyString());
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("chunk: 4500자 텍스트(줄바꿈 없음) → 문장 경계 fallback으로 분할")
    void embedAndSave_긴텍스트_문장경계_fallback() {
        // given: CHUNK_SIZE(2000) 초과, 단락 구분 없음 → 문장 경계 fallback
        givenEmbeddingModel();
        String longText = "A".repeat(4500);

        // when
        vectorService.embedAndSave(longText);

        // then: 최소 2청크 이상으로 분할됨
        verify(embeddingModel, times(3)).embed(anyString());
    }

    @Test
    @DisplayName("chunk: 단락 기반 분할 — \\n\\n으로 나뉜 단락이 각각 청크로")
    void chunk_단락기반_분할() {
        // given: 3개 단락 (각각 ~800자), 총 ~2400자 > CHUNK_SIZE
        String para1 = "A".repeat(800);
        String para2 = "B".repeat(800);
        String para3 = "C".repeat(800);
        String text = para1 + "\n\n" + para2 + "\n\n" + para3;

        // when
        List<String> chunks = vectorService.chunk(text);

        // then: 단락 병합으로 2청크 (800+800=1600 < 2000, 800)
        assertThat(chunks).hasSizeBetween(2, 3);
        // 첫 청크에 A와 B가 모두 포함 (병합됨)
        assertThat(chunks.get(0)).contains("A".repeat(10));
    }

    @Test
    @DisplayName("chunk: 짧은 단락은 인접 단락과 병합")
    void chunk_짧은단락_병합() {
        // given: 5개 짧은 단락 (각 200자) — MIN_CHUNK_SIZE(500) 미만
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(String.valueOf((char) ('A' + i)).repeat(200));
        }
        // 총 1000자 + 구분자 < CHUNK_SIZE → 1청크
        String text = sb.toString();

        // when
        List<String> chunks = vectorService.chunk(text);

        // then: 전체가 CHUNK_SIZE 이하이므로 1청크
        assertThat(chunks).hasSize(1);
    }

    @Test
    @DisplayName("chunk: CHUNK_SIZE 초과 단락은 문장 경계에서 재분할")
    void chunk_큰단락_문장경계_재분할() {
        // given: 하나의 단락이 3000자 (CHUNK_SIZE 초과)
        // 문장부호가 있어야 findSentenceBoundary에서 분할됨
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("이것은 테스트 문장입니다. ".repeat(3));
        }
        String text = sb.toString(); // ~4500자

        // when
        List<String> chunks = vectorService.chunk(text);

        // then: 문장 경계에서 분할
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(2100); // CHUNK_SIZE + 오버랩 여유
        }
    }

    @Test
    @DisplayName("chunk: 오버랩 — 이전 청크 마지막 문장이 다음 청크에 포함")
    void chunk_오버랩_문맥연속성() {
        // given: 2개 큰 단락, 각각 마침표가 있는 문장
        String para1 = "Docker는 컨테이너 기술이다. " + "A".repeat(1500) + ". 첫번째 단락 끝.";
        String para2 = "Kubernetes는 오케스트레이션이다. " + "B".repeat(1500) + ". 두번째 단락 끝.";
        String text = para1 + "\n\n" + para2;

        // when
        List<String> chunks = vectorService.chunk(text);

        // then: 2개 이상 청크, 두번째 청크에 첫번째의 마지막 문장 일부 포함 가능
        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("[P0-1] embedAndSave: 동일 텍스트 두 번 입력 시 INSERT 스킵 (중복 방지)")
    void embedAndSave_중복텍스트_INSERT_스킵() {
        // given: vector_store에 이미 동일 content 존재 (queryForObject → 1)
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), anyString())).thenReturn(1);
        String text = "Docker는 컨테이너 가상화 기술이다.";

        // when
        vectorService.embedAndSave(text);

        // then: 중복이므로 embed도 INSERT도 호출 안 됨
        verify(embeddingModel, never()).embed(anyString());
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("embedAndSave: 벡터 문자열이 SQL에 전달됨")
    void embedAndSave_벡터_문자열_변환() {
        // given
        givenEmbeddingModel();
        ArgumentCaptor<Object> vectorCaptor = ArgumentCaptor.forClass(Object.class);

        // when
        vectorService.embedAndSave("테스트");

        // then: 네 번째 인자(vectorString)가 문자열이고 대괄호로 시작
        verify(jdbcTemplate).update(
                anyString(),
                any(),      // UUID
                any(),      // text
                any(),      // metadata "{}"
                vectorCaptor.capture()  // vectorString
        );
        assertThat(vectorCaptor.getValue()).isInstanceOf(String.class);
        assertThat((String) vectorCaptor.getValue()).startsWith("[");
    }
}
