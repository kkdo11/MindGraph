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
    @DisplayName("chunk: 4500자 텍스트 → 3청크, embed 3회 호출 (무한루프 버그 수정 검증)")
    void embedAndSave_긴텍스트_여러청크_무한루프없음() {
        // given: CHUNK_SIZE(2000) 초과 → 슬라이딩 윈도우 분할
        // 예상 청크: [0,2000), [1700,3700), [3400,4500) → 3개
        // 수정 전 코드는 마지막 청크 이후 start=4200에서 무한루프 발생
        givenEmbeddingModel();
        String longText = "A".repeat(4500);

        // when: 수정 후 정상 종료
        vectorService.embedAndSave(longText);

        // then: embed 3회 (청크 3개)
        verify(embeddingModel, times(3)).embed(anyString());
        verify(jdbcTemplate, times(3)).update(anyString(), any(), any(), any(), any());
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
