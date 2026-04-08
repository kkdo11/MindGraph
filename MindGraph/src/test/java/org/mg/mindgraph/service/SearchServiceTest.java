package org.mg.mindgraph.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mg.mindgraph.dto.SearchResult;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private SearchService searchService;

    private static final float[] SAMPLE_VECTOR = {0.1f, 0.2f, 0.3f};

    @BeforeEach
    void setUp() {
        // @Value 필드는 Spring 컨텍스트 없이 주입되지 않으므로 기본값을 직접 설정
        ReflectionTestUtils.setField(searchService, "vectorLimit", 5);
        ReflectionTestUtils.setField(searchService, "vectorThreshold", 0.3);
    }

    private void givenEmbeddingModel() {
        Embedding embedding = Embedding.from(SAMPLE_VECTOR);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(embedding));
    }

    @Test
    @DisplayName("search: 임베딩 생성 후 JdbcTemplate.query 호출")
    void search_임베딩생성_후_JdbcTemplate_호출() {
        // given
        givenEmbeddingModel();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
        String query = "Docker란 무엇인가?";

        // when
        searchService.search(query);

        // then
        verify(embeddingModel).embed(query);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), any());
    }

    @Test
    @DisplayName("search: 결과를 SearchResult 리스트로 반환")
    void search_결과_SearchResult_리스트_반환() {
        // given
        givenEmbeddingModel();
        List<SearchResult> expected = List.of(new SearchResult("Docker 컨테이너 기술", 0.9));
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any())).thenReturn(expected);

        // when
        List<SearchResult> result = searchService.search("Docker");

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("Docker 컨테이너 기술");
        assertThat(result.get(0).score()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("search: 결과 없으면 빈 리스트 반환")
    void search_빈결과_빈리스트_반환() {
        // given
        givenEmbeddingModel();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());

        // when
        List<SearchResult> result = searchService.search("없는내용");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("search: SQL에 LIMIT 5 포함 확인 (기본값)")
    void search_상위5개만_반환() {
        // given
        givenEmbeddingModel();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sqlCaptor.capture(), any(RowMapper.class), any())).thenReturn(List.of());

        // when
        searchService.search("테스트");

        // then: 기본 vectorLimit=5 가 SQL에 포함됨
        assertThat(sqlCaptor.getValue()).containsIgnoringCase("LIMIT 5");
    }

    @Test
    @DisplayName("search: SQL에 threshold 0.3 필터링 조건 포함 확인")
    void search_threshold_필터링_SQL에_포함() {
        // given
        givenEmbeddingModel();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sqlCaptor.capture(), any(RowMapper.class), any())).thenReturn(List.of());

        // when: 기본 threshold=0.3 적용
        searchService.search("테스트");

        // then: WHERE score >= 0.3000 조건이 SQL에 포함됨
        assertThat(sqlCaptor.getValue()).contains("0.3000");
    }

    @Test
    @DisplayName("search(limit, threshold): 커스텀 파라미터 SQL에 반영")
    void search_커스텀_limit_threshold_파라미터화() {
        // given
        givenEmbeddingModel();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sqlCaptor.capture(), any(RowMapper.class), any())).thenReturn(List.of());

        // when: limit=3, threshold=0.7 로 직접 호출
        searchService.search("테스트", 3, 0.7);

        // then: 커스텀 값이 SQL에 반영됨
        String sql = sqlCaptor.getValue();
        assertThat(sql).containsIgnoringCase("LIMIT 3");
        assertThat(sql).contains("0.7000");
    }

    @Test
    @DisplayName("search: SQL에 pgvector <=> 연산자 포함 확인")
    void search_임베딩_벡터_SQL에_포함() {
        // given
        givenEmbeddingModel();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sqlCaptor.capture(), any(RowMapper.class), any())).thenReturn(List.of());

        // when
        searchService.search("테스트");

        // then
        assertThat(sqlCaptor.getValue()).contains("<=>");
    }
}
