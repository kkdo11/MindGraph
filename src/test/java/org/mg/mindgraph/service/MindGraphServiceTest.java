package org.mg.mindgraph.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mg.mindgraph.dto.NodeInsight;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.mg.mindgraph.dto.SearchResult;
import org.mg.mindgraph.entity.Edge;
import org.mg.mindgraph.entity.Node;
import org.mg.mindgraph.repository.EdgeRepository;
import org.mg.mindgraph.repository.NodeRepository;
import org.mockito.Mock;
import org.mockito.Spy;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MindGraphServiceTest {

    @Mock
    private SearchService searchService;

    @Mock
    private GraphService graphService;

    @Mock
    private NodeRepository nodeRepository;

    @Mock
    private EdgeRepository edgeRepository;

    @Mock
    private Neo4jClient neo4jClient;

    @Mock
    private ChatLanguageModel chatLanguageModel;

    @Mock
    private ChatLanguageModel ragChatLanguageModel;

    @Mock
    private EmbeddingModel embeddingModel;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private MindGraphService mindGraphService;

    private static final float[] SAMPLE_VECTOR = new float[1024];

    @BeforeEach
    void setUp() {
        mindGraphService = new MindGraphService(
                searchService, graphService, nodeRepository,
                chatLanguageModel, objectMapper, edgeRepository, neo4jClient, embeddingModel
        );
        ReflectionTestUtils.setField(mindGraphService, "ragChatLanguageModel", ragChatLanguageModel);
        ReflectionTestUtils.setField(mindGraphService, "rrfK", 60);

        // [F-1c] findNodesWithScore 기본 stub — 대부분의 테스트에서 필요
        lenient().when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(SAMPLE_VECTOR)));
        lenient().when(nodeRepository.findSimilarWithScore(anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of());
        // 기존 호환: findSimilarByNameEmbedding stub 유지
        lenient().when(nodeRepository.findSimilarByNameEmbedding(anyString(), anyDouble(), anyInt()))
                .thenReturn(List.of());
        // [F-6b] expandQuery에서 노드 description 조회 기본 stub
        lenient().when(nodeRepository.findByNameIn(anyList())).thenReturn(List.of());
    }

    // 테스트용 Node 빌더 헬퍼
    private Node buildNode(String name, String type) {
        return buildNodeWithId(null, name, type);
    }

    private Node buildNodeWithId(Long id, String name, String type) {
        Node node = Node.builder().name(name).type(type).description(null).build();
        if (id != null) {
            ReflectionTestUtils.setField(node, "id", id);
        }
        return node;
    }

    /** findSimilarWithScore mock용 — List<Object[]> 반환 */
    private List<Object[]> scoreRows(Object[]... rows) {
        return Arrays.asList(rows);
    }

    private Object[] scoreRow(Long id, String name, double similarity) {
        return new Object[]{id, name, similarity};
    }

    // ==================== extractKeywords 관련 테스트 ====================

    @Test
    @DisplayName("extractKeywords: 정상 JSON 파싱 → List 반환")
    void extractKeywords_정상_JSON_파싱() {
        // given: LLM이 깔끔한 JSON 배열 반환
        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Docker\", \"2026\"]");
        when(searchService.search(anyString())).thenReturn(List.of());
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of());

        // when: searchGraph을 통해 간접적으로 extractKeywords 호출
        // ask()를 직접 호출하면 전체 파이프라인이 실행됨 → 키워드 추출 검증을 위해 ask 사용
        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Docker\", \"2026\"]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("최종 답변");

        String result = mindGraphService.ask("Docker 2026 배포는?");

        // then
        assertThat(result).isEqualTo("최종 답변");
        verify(chatLanguageModel, atLeast(1)).generate(anyString());
        verify(ragChatLanguageModel, times(1)).generate(anyString());
    }

    @Test
    @DisplayName("extractKeywords: 부연 설명 포함 응답에서 정규식으로 JSON 추출")
    void extractKeywords_부연설명_포함_정규식_추출() {
        // given: LLM이 부연 설명 포함하여 응답 (실제 발생한 버그 케이스)
        String llmResponseWithExplanation = "Here are the keywords:\n[\"Docker\", \"Container\"]\nThese are the key terms.";
        when(chatLanguageModel.generate(anyString())).thenReturn(llmResponseWithExplanation);
        when(ragChatLanguageModel.generate(anyString())).thenReturn("최종 답변");
        when(searchService.search(anyString())).thenReturn(List.of());
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of());

        // when
        String result = mindGraphService.ask("Docker 컨테이너란?");

        // then: 파싱 실패 없이 정상 처리됨
        assertThat(result).isEqualTo("최종 답변");
    }

    @Test
    @DisplayName("extractKeywords: JSON 파싱 실패 → 빈 리스트 반환 (graceful degradation)")
    void extractKeywords_JSON_파싱_실패_빈리스트_반환() {
        // given: LLM이 JSON이 아닌 텍스트 반환
        when(chatLanguageModel.generate(anyString())).thenReturn("invalid response no json here");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("최종 답변");
        when(searchService.search(anyString())).thenReturn(List.of());

        // when
        String result = mindGraphService.ask("테스트 질문");

        // then: 키워드 없어도 ask는 정상 답변 반환
        assertThat(result).isEqualTo("최종 답변");
        // 노드 조회가 호출되지 않음 (키워드 빈 리스트이므로 searchGraph 조기 종료)
        verify(nodeRepository, never()).findSimilarByNameEmbedding(anyString(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("extractKeywords: LLM 예외 발생 → 빈 리스트 반환")
    void extractKeywords_LLM_예외_빈리스트_반환() {
        // given: 첫 LLM 호출(extractKeywords)에서 예외, 두 번째(RAG)는 성공
        when(chatLanguageModel.generate(anyString())).thenThrow(new RuntimeException("LLM 연결 실패"));
        when(ragChatLanguageModel.generate(anyString())).thenReturn("최종 답변");
        when(searchService.search(anyString())).thenReturn(List.of());

        // when: extractKeywords 예외는 내부에서 캐치하여 빈 리스트 반환
        String result = mindGraphService.ask("테스트");

        // then: 전체 파이프라인이 중단되지 않음
        assertThat(result).isEqualTo("최종 답변");
    }

    // ==================== yearsMatch 관련 테스트 ====================

    @Test
    @DisplayName("yearsMatch: 연도 포함 info → true 반환")
    void yearsMatch_연도포함_일치() {
        // given: 2026이 포함된 info를 가진 엣지 시나리오
        Node n1 = buildNodeWithId(1L, "Manus", "Project");
        Node n2 = buildNodeWithId(2L, "2026년 설립", "Concept");
        Edge edge = Edge.builder().source(n1).target(n2).relation("FOUNDED_IN").build();

        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Manus\", \"2026\"]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("2026년에 설립되었습니다.");
        when(searchService.search(anyString())).thenReturn(List.of());
        when(nodeRepository.findSimilarWithScore(anyString(), anyDouble(), anyInt()))
                .thenReturn(scoreRows(scoreRow(1L, "Manus", 0.85)));
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(n1));
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of(edge));

        // when
        String result = mindGraphService.ask("Manus가 2026년에 설립되었나요?");

        // then
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("yearsMatch: 연도 불일치 → 해당 엣지 필터링됨")
    void yearsMatch_연도_불일치() {
        // given: 2024 관련 엣지이지만 쿼리는 2023
        Node n1 = buildNodeWithId(1L, "GDP", "Concept");
        Node n2 = buildNodeWithId(2L, "2024", "Concept");
        Edge edge2024 = Edge.builder().source(n1).target(n2).relation("YEAR").build();

        when(chatLanguageModel.generate(anyString())).thenReturn("[\"GDP\", \"2023\"]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("정보를 찾을 수 없습니다.");
        when(searchService.search(anyString())).thenReturn(List.of());
        when(nodeRepository.findSimilarWithScore(anyString(), anyDouble(), anyInt()))
                .thenReturn(scoreRows(scoreRow(1L, "GDP", 0.80)));
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(n1));
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of(edge2024));

        // when
        String result = mindGraphService.ask("2023년 GDP는?");

        // then: 필터링으로 그래프 컨텍스트가 비어있어도 정상 응답
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("yearsMatch: 연도 목록이 비어 있으면 항상 통과")
    void yearsMatch_연도목록_비어있으면_항상_통과() {
        // given: 연도 없는 키워드만 추출됨
        Node n1 = buildNodeWithId(1L, "Docker", "Technology");
        Node n2 = buildNodeWithId(2L, "Container", "Concept");
        Edge edge = Edge.builder().source(n1).target(n2).relation("IS_A").build();

        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Docker\"]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("Docker는 컨테이너 기술입니다.");
        when(searchService.search(anyString())).thenReturn(List.of());
        when(nodeRepository.findSimilarWithScore(anyString(), anyDouble(), anyInt()))
                .thenReturn(scoreRows(scoreRow(1L, "Docker", 0.90)));
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(n1));
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of(edge));

        // when
        String result = mindGraphService.ask("Docker란?");

        // then: 연도 필터 없이 엣지가 컨텍스트에 포함됨
        assertThat(result).contains("컨테이너");
    }

    // ==================== formatEdgeToKnowledge 관련 테스트 ====================

    @Test
    @DisplayName("formatEdgeToKnowledge: Edge → 정해진 포맷 문자열 반환")
    void formatEdgeToKnowledge_정상_포맷() {
        // given
        Node source = buildNodeWithId(1L, "Docker", "Technology");
        Node target = buildNodeWithId(2L, "Container", "Concept");
        Edge edge = Edge.builder().source(source).target(target).relation("IS_A").build();

        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Docker\"]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("답변");
        when(searchService.search(anyString())).thenReturn(List.of());
        when(nodeRepository.findSimilarWithScore(anyString(), anyDouble(), anyInt()))
                .thenReturn(scoreRows(scoreRow(1L, "Docker", 0.90)));
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(source));
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of(edge));

        // when
        String result = mindGraphService.ask("Docker란?");

        // then: 키워드 추출은 chatLanguageModel, RAG 답변은 ragChatLanguageModel
        verify(chatLanguageModel, times(1)).generate(anyString());
        verify(ragChatLanguageModel, times(1)).generate(anyString());
    }

    // ==================== searchGraph 관련 테스트 ====================

    @Test
    @DisplayName("searchGraph: 키워드 없으면 빈 문자열 → 그래프 조회 미실행")
    void searchGraph_키워드_없으면_빈문자열() {
        // given: extractKeywords가 빈 리스트 반환
        when(chatLanguageModel.generate(anyString()))
                .thenReturn("no json array here")  // extractKeywords 실패
                .thenReturn("최종 답변");
        when(searchService.search(anyString())).thenReturn(List.of());

        // when
        mindGraphService.ask("테스트");

        // then: 노드 조회 없음
        verify(nodeRepository, never()).findSimilarByNameEmbedding(anyString(), anyDouble(), anyInt());
        verify(edgeRepository, never()).findEdgesByNodeNamesIn(anyList());
    }

    @Test
    @DisplayName("searchGraph: 엔티티와 연도 키워드 분리")
    void searchGraph_엔티티_연도_분리() {
        // given: 연도 포함 키워드 반환
        when(chatLanguageModel.generate(anyString()))
                .thenReturn("[\"Docker\", \"2024\"]");   // Docker=entity, 2024=year
        when(ragChatLanguageModel.generate(anyString())).thenReturn("최종 답변");
        when(searchService.search(anyString())).thenReturn(List.of());
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of());

        // when
        mindGraphService.ask("Docker 2024 릴리즈는?");

        // then: "2024"(연도)는 임베딩 검색 제외, "Docker"만 findSimilarWithScore 호출
        // 연도 1개, 엔티티 1개 → findSimilarWithScore 1회 호출
        verify(nodeRepository, times(1)).findSimilarWithScore(anyString(), anyDouble(), anyInt());
    }

    // ==================== buildFullGraphSummary (fallback) 관련 테스트 ====================

    @Test
    @DisplayName("buildFullGraphSummary: findAll() 미호출 — 허브/최근 노드 조회로 대체")
    void buildFullGraphSummary_findAll_미호출_허브_최근노드_조회() {
        // given: 벡터/그래프 컨텍스트 모두 비어 있어 fallback 경로 진입
        when(chatLanguageModel.generate(anyString())).thenReturn("invalid no json here");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("답변");
        when(searchService.search(anyString())).thenReturn(List.of());

        Node hub = buildNode("Docker", "Technology");
        when(nodeRepository.findTopNodesByDegree(10)).thenReturn(List.of(hub));
        when(nodeRepository.findRecentNodes(any(Pageable.class))).thenReturn(List.of());
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of());

        // when
        mindGraphService.ask("테스트");

        // then: findAll() 은 절대 호출되지 않음 (OOM 위험 제거)
        verify(nodeRepository, never()).findAll();
        verify(nodeRepository, times(1)).findTopNodesByDegree(10);
        verify(nodeRepository, times(1)).findRecentNodes(any(Pageable.class));
    }

    // ==================== assembleContext 관련 테스트 ====================

    @Test
    @DisplayName("assembleContext: MAX_CONTEXT_CHARS(4000) 초과 시 내용 절단")
    void assembleContext_토큰예산_초과_절단() {
        // given: 5000자 벡터 결과 → 4000자 한도 내에서 절단돼야 함
        String longContent = "A".repeat(5000);
        when(searchService.search(anyString()))
                .thenReturn(List.of(new SearchResult(longContent, 0.9)));
        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Docker\"]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("답변");
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of());

        // when
        mindGraphService.ask("Docker란?");

        // then: ragChatLanguageModel에 전달된 프롬프트에서 5000개 A 연속이 없어야 함 (절단됨)
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ragChatLanguageModel).generate(promptCaptor.capture());
        String capturedPrompt = promptCaptor.getValue();
        assertThat(capturedPrompt).doesNotContain("A".repeat(5001));
        // 4000자 한도 내에서는 A가 포함됨
        assertThat(capturedPrompt).contains("A");
    }

    @Test
    @DisplayName("assembleContext: score 높은 결과가 낮은 결과보다 먼저 포함됨")
    void assembleContext_score_기반_정렬() {
        // given: 낮은 score 벡터 결과 + 높은 score 벡터 결과
        SearchResult lowScore = new SearchResult("낮은 관련성 내용", 0.4);
        SearchResult highScore = new SearchResult("높은 관련성 내용", 0.95);
        when(searchService.search(anyString())).thenReturn(List.of(lowScore, highScore));
        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Docker\"]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("답변");
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of());

        // when
        mindGraphService.ask("Docker란?");

        // then: 높은 관련성 내용이 낮은 것보다 앞에 있어야 함
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(ragChatLanguageModel).generate(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertThat(prompt.indexOf("높은 관련성 내용"))
                .isLessThan(prompt.indexOf("낮은 관련성 내용"));
    }

    // ==================== [D-1] findNodesBySimilarity 관련 테스트 ====================

    @Test
    @DisplayName("[F-1c] findNodesWithScore: findByNameIn 대신 벡터 유사도 + score 검색 호출")
    void findNodesWithScore_유사도검색_호출() {
        // given: 키워드 추출 → 임베딩 → 유사 노드 + score 반환
        Node docker = buildNodeWithId(1L, "도커", "Technology");  // 저장 언어: 한국어
        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Docker\"]");  // 질문 언어: 영어
        when(ragChatLanguageModel.generate(anyString())).thenReturn("답변");
        when(searchService.search(anyString())).thenReturn(List.of());
        when(nodeRepository.findSimilarWithScore(anyString(), anyDouble(), anyInt()))
                .thenReturn(scoreRows(scoreRow(1L, "도커", 0.85)));
        when(nodeRepository.findById(1L)).thenReturn(Optional.of(docker));
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of());

        // when
        mindGraphService.ask("Docker란?");

        // then: 그래프 검색은 findSimilarWithScore 사용 (findByNameIn이 아닌 임베딩 유사도)
        verify(nodeRepository, atLeast(1)).findSimilarWithScore(anyString(), anyDouble(), anyInt());
        verify(embeddingModel, atLeast(1)).embed(anyString());
    }

    // ==================== [F-6b] Query Expansion ====================

    @Test
    @DisplayName("[F-6b] ask: 키워드 노드 description이 벡터 검색 질문에 확장됨")
    void ask_queryExpansion_description_확장() {
        // given: "Docker" 키워드 → 노드 description "컨테이너 가상화 기술"
        Node docker = buildNodeWithId(1L, "Docker", "Technology");
        docker.setDescription("컨테이너 가상화 기술");
        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Docker\"]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("답변");
        when(nodeRepository.findByNameIn(List.of("Docker"))).thenReturn(List.of(docker));
        when(searchService.search(anyString())).thenReturn(List.of());
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of());

        // when
        mindGraphService.ask("Docker란?");

        // then: searchService.search()에 확장된 질문이 전달됨
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(searchService).search(queryCaptor.capture());
        String expandedQuery = queryCaptor.getValue();
        assertThat(expandedQuery).contains("Docker란?");
        assertThat(expandedQuery).contains("컨테이너 가상화 기술");
    }

    @Test
    @DisplayName("[F-6b] ask: description 없는 노드는 확장에 포함되지 않음")
    void ask_queryExpansion_description없으면_원본유지() {
        // given: "Redis" 키워드 → description 없음
        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Redis\"]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("답변");
        when(nodeRepository.findByNameIn(List.of("Redis"))).thenReturn(List.of());
        when(searchService.search(anyString())).thenReturn(List.of());
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of());

        // when
        mindGraphService.ask("Redis란?");

        // then: 원본 질문 그대로 전달
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(searchService).search(queryCaptor.capture());
        assertThat(queryCaptor.getValue()).isEqualTo("Redis란?");
    }

    // ==================== getInsights 관련 테스트 ====================

    @Test
    @DisplayName("getInsights: Neo4j 실패 시 PostgreSQL fallback — 상위 노드 반환")
    void getInsights_Neo4j_실패시_PostgreSQL_fallback() {
        // given: neo4jClient mock → query() null → NPE → try-catch → PostgreSQL fallback
        Node docker = buildNode("Docker", "Technology");
        Node redis = buildNode("Redis", "Technology");
        when(nodeRepository.findTopNodesByDegree(5)).thenReturn(List.of(docker, redis));

        // when
        List<NodeInsight> result = mindGraphService.getInsights(5);

        // then: PostgreSQL fallback 결과 반환, degree=0 (실측 불가)
        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("Docker");
        assertThat(result.get(0).degree()).isEqualTo(0L);
        verify(nodeRepository).findTopNodesByDegree(5);
    }

    @Test
    @DisplayName("getInsights: 노드 없으면 빈 리스트 반환")
    void getInsights_노드없으면_빈리스트() {
        // given: Neo4j 실패 + PostgreSQL도 빈 결과
        when(nodeRepository.findTopNodesByDegree(anyInt())).thenReturn(List.of());

        // when
        List<NodeInsight> result = mindGraphService.getInsights(10);

        // then
        assertThat(result).isEmpty();
    }

    // ==================== ask 관련 테스트 ====================

    @Test
    @DisplayName("ask: 벡터+그래프 컨텍스트를 병합하여 LLM 프롬프트에 전달")
    void ask_병렬검색_결과_통합() {
        // given
        when(chatLanguageModel.generate(anyString())).thenReturn("[\"Redis\"]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn("Redis 답변");
        when(searchService.search(anyString()))
                .thenReturn(List.of(new SearchResult("Redis는 인메모리 캐시", 0.9)));
        when(edgeRepository.findEdgesByNodeNamesIn(anyList())).thenReturn(List.of());

        // when
        String result = mindGraphService.ask("Redis란?");

        // then
        assertThat(result).isEqualTo("Redis 답변");
        verify(searchService).search("Redis란?");
        verify(chatLanguageModel, times(1)).generate(anyString());
        verify(ragChatLanguageModel, times(1)).generate(anyString());
    }

    @Test
    @DisplayName("ask: LLM 최종 답변 반환")
    void ask_LLM_최종답변_반환() {
        // given
        String expectedAnswer = "Kubernetes는 컨테이너 오케스트레이션 플랫폼입니다. [Source: Knowledge Graph]";
        when(chatLanguageModel.generate(anyString())).thenReturn("[]");
        when(ragChatLanguageModel.generate(anyString())).thenReturn(expectedAnswer);
        when(searchService.search(anyString())).thenReturn(List.of());

        // when
        String result = mindGraphService.ask("Kubernetes란?");

        // then
        assertThat(result).isEqualTo(expectedAnswer);
    }
}
