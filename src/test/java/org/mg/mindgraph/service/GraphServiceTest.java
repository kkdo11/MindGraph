package org.mg.mindgraph.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mg.mindgraph.dto.GraphData;
import org.mg.mindgraph.entity.Edge;
import org.mg.mindgraph.entity.Node;
import org.mg.mindgraph.repository.EdgeRepository;
import org.mg.mindgraph.repository.NodeRepository;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphServiceTest {

    @Mock
    private NodeRepository nodeRepository;

    @Mock
    private EdgeRepository edgeRepository;

    @Mock
    private Neo4jClient neo4jClient;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private GraphService graphService;

    private static final float[] SAMPLE_VECTOR = new float[1024];

    // Neo4j fluent API mock 체인 (bind() → OngoingBindSpec, to() → RunnableSpec)
    private Neo4jClient.UnboundRunnableSpec mockRunnableSpec;
    @SuppressWarnings("rawtypes")
    private Neo4jClient.OngoingBindSpec mockBindSpec;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        mockRunnableSpec = mock(Neo4jClient.UnboundRunnableSpec.class);
        mockBindSpec = mock(Neo4jClient.OngoingBindSpec.class);
        lenient().when(neo4jClient.query(anyString())).thenReturn(mockRunnableSpec);
        lenient().when(mockRunnableSpec.bind(any())).thenReturn(mockBindSpec);
        lenient().when(mockBindSpec.to(anyString())).thenReturn(mockRunnableSpec);
        // [D-1] 이름 임베딩 기본 stub
        lenient().when(embeddingModel.embed(anyString()))
                .thenReturn(Response.from(Embedding.from(SAMPLE_VECTOR)));
    }

    private Node buildNode(String name, String type) {
        return Node.builder().name(name).type(type).description(null).build();
    }

    // ==================== 노드 저장 ====================

    @Test
    @DisplayName("saveGraph: 새 노드이면 save 호출")
    void saveGraph_새노드_저장() {
        GraphData data = new GraphData(
                List.of(new GraphData.NodeDTO("Docker", "Technology", "컨테이너")),
                List.of()
        );
        Node savedNode = buildNode("Docker", "Technology");
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenReturn(savedNode);

        graphService.saveGraph(data);

        verify(nodeRepository).save(any(Node.class));
    }

    @Test
    @DisplayName("saveGraph: 기존 노드에 description 이미 있으면 save 미호출 (재사용)")
    void saveGraph_기존노드_description있으면_save_미호출() {
        Node existingNode = Node.builder().name("Docker").type("Technology").description("기존 설명").build();
        GraphData data = new GraphData(
                List.of(new GraphData.NodeDTO("Docker", "Technology", "새 설명")),
                List.of()
        );
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.of(existingNode));

        graphService.saveGraph(data);

        verify(nodeRepository, never()).save(any(Node.class));
    }

    @Test
    @DisplayName("saveGraph: 기존 노드 description 비어 있으면 새 값으로 채우고 save 호출")
    void saveGraph_기존노드_description없으면_업데이트_save_호출() {
        Node existingNode = buildNode("Docker", "Technology"); // description=null
        GraphData data = new GraphData(
                List.of(new GraphData.NodeDTO("Docker", "Technology", "컨테이너 가상화 기술")),
                List.of()
        );
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.of(existingNode));
        when(nodeRepository.save(any(Node.class))).thenReturn(existingNode);

        graphService.saveGraph(data);

        verify(nodeRepository, times(1)).save(existingNode);
    }

    @Test
    @DisplayName("[P0-3] saveGraph: 같은 이름 다른 타입 → 기존 노드 재사용 (노드 분열 방지)")
    void saveGraph_같은이름_다른타입_기존노드_재사용() {
        // given: DB에 "Docker" / Technology 존재
        Node existing = buildNode("Docker", "Technology");
        // LLM이 이번엔 "Docker"를 Database 타입으로 추출
        GraphData data = new GraphData(
                List.of(new GraphData.NodeDTO("Docker", "Database", null)),
                List.of()
        );
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.of(existing));

        graphService.saveGraph(data);

        // name으로 찾아 기존 노드 재사용 — 새 ("Docker", "Database") 노드 생성 없음
        verify(nodeRepository).findFirstByName("Docker");
        verify(nodeRepository, never()).save(any(Node.class));
    }

    // ==================== Neo4j 동기화 ====================

    @Test
    @DisplayName("[P0-2] saveGraph: Neo4j sync에 description 파라미터 전달됨")
    void saveGraph_neo4j_description_전달() {
        GraphData data = new GraphData(
                List.of(new GraphData.NodeDTO("Docker", "Technology", "컨테이너 기술")),
                List.of()
        );
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenAnswer(inv -> inv.getArgument(0));

        graphService.saveGraph(data);

        // Neo4j bind() 체인에 description 값이 전달되는지 확인
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(mockRunnableSpec, atLeastOnce()).bind(captor.capture());
        assertThat(captor.getAllValues()).contains("컨테이너 기술");
    }

    // ==================== 엣지 저장 ====================

    @Test
    @DisplayName("saveGraph: 엣지 중복 없으면 저장")
    void saveGraph_엣지_중복없으면_저장() {
        Node docker = buildNode("Docker", "Technology");
        Node container = buildNode("Container", "Concept");
        GraphData data = new GraphData(
                List.of(
                        new GraphData.NodeDTO("Docker", "Technology", null),
                        new GraphData.NodeDTO("Container", "Concept", null)
                ),
                List.of(new GraphData.EdgeDTO("Docker", "Container", "IS_A"))
        );
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.of(docker));
        when(nodeRepository.findFirstByName("Container")).thenReturn(Optional.of(container));
        when(edgeRepository.existsBySourceIdAndTargetIdAndRelation(any(), any(), anyString())).thenReturn(false);

        graphService.saveGraph(data);

        verify(edgeRepository).save(any(Edge.class));
    }

    @Test
    @DisplayName("saveGraph: 엣지 중복이면 저장 안 함")
    void saveGraph_엣지_중복이면_저장안함() {
        Node docker = buildNode("Docker", "Technology");
        Node container = buildNode("Container", "Concept");
        GraphData data = new GraphData(
                List.of(
                        new GraphData.NodeDTO("Docker", "Technology", null),
                        new GraphData.NodeDTO("Container", "Concept", null)
                ),
                List.of(new GraphData.EdgeDTO("Docker", "Container", "IS_A"))
        );
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.of(docker));
        when(nodeRepository.findFirstByName("Container")).thenReturn(Optional.of(container));
        when(edgeRepository.existsBySourceIdAndTargetIdAndRelation(any(), any(), anyString())).thenReturn(true);

        graphService.saveGraph(data);

        verify(edgeRepository, never()).save(any(Edge.class));
    }

    @Test
    @DisplayName("saveGraph: source/target 노드 없는 엣지는 경고 로그 후 스킵")
    void saveGraph_노드_없는_엣지는_스킵() {
        GraphData data = new GraphData(
                List.of(),
                List.of(new GraphData.EdgeDTO("Unknown", "Missing", "RELATED"))
        );

        assertThatNoException().isThrownBy(() -> graphService.saveGraph(data));
        verify(edgeRepository, never()).save(any(Edge.class));
    }

    @Test
    @DisplayName("saveGraph: 빈 GraphData도 예외 없이 처리")
    void saveGraph_빈_GraphData_예외없이_처리() {
        GraphData emptyData = new GraphData(List.of(), List.of());

        assertThatNoException().isThrownBy(() -> graphService.saveGraph(emptyData));
        verify(nodeRepository, never()).save(any(Node.class));
        verify(edgeRepository, never()).save(any(Edge.class));
    }

    @Test
    @DisplayName("saveGraph: 여러 노드와 엣지 모두 저장")
    void saveGraph_여러노드_엣지_모두저장() {
        Node n1 = buildNode("Redis", "Technology");
        Node n2 = buildNode("Cache", "Concept");
        Node n3 = buildNode("FastAPI", "Technology");
        GraphData data = new GraphData(
                List.of(
                        new GraphData.NodeDTO("Redis", "Technology", null),
                        new GraphData.NodeDTO("Cache", "Concept", null),
                        new GraphData.NodeDTO("FastAPI", "Technology", null)
                ),
                List.of(
                        new GraphData.EdgeDTO("Redis", "Cache", "IS_A"),
                        new GraphData.EdgeDTO("FastAPI", "Cache", "USES")
                )
        );
        when(nodeRepository.findFirstByName("Redis")).thenReturn(Optional.empty());
        when(nodeRepository.findFirstByName("Cache")).thenReturn(Optional.empty());
        when(nodeRepository.findFirstByName("FastAPI")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenAnswer(inv -> inv.getArgument(0));
        when(edgeRepository.existsBySourceIdAndTargetIdAndRelation(any(), any(), anyString())).thenReturn(false);

        graphService.saveGraph(data);

        verify(nodeRepository, times(3)).save(any(Node.class));
        verify(edgeRepository, times(2)).save(any(Edge.class));
    }

    @Test
    @DisplayName("saveGraph: 동일 노드 두 번 — description 없으면 생성 시 save 1회만 호출")
    void processNodes_중복노드_find_or_create() {
        GraphData data = new GraphData(
                List.of(
                        new GraphData.NodeDTO("Docker", "Technology", null),
                        new GraphData.NodeDTO("Docker", "Technology", null)
                ),
                List.of()
        );
        Node existing = buildNode("Docker", "Technology");
        when(nodeRepository.findFirstByName("Docker"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(nodeRepository.save(any(Node.class))).thenReturn(existing);

        graphService.saveGraph(data);

        verify(nodeRepository, times(1)).save(any(Node.class));
    }

    // ==================== P2-1: description 교체 정책 ====================

    @Test
    @DisplayName("[P2-1] saveGraph: 새 description이 기존보다 길면 교체")
    void saveGraph_description_더_길면_교체() {
        // given: 기존 description이 짧음
        Node existing = Node.builder().name("Docker").type("Technology").description("컨테이너").build();
        GraphData data = new GraphData(
                List.of(new GraphData.NodeDTO("Docker", "Technology", "Docker는 오픈소스 컨테이너 가상화 플랫폼입니다.")),
                List.of()
        );
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.of(existing));
        when(nodeRepository.save(any(Node.class))).thenReturn(existing);

        graphService.saveGraph(data);

        // 새 description이 더 길므로 save 호출되어 교체됨
        verify(nodeRepository).save(argThat(n ->
                n.getDescription().equals("Docker는 오픈소스 컨테이너 가상화 플랫폼입니다.")
        ));
    }

    @Test
    @DisplayName("[P2-1] saveGraph: 새 description이 기존보다 짧으면 유지")
    void saveGraph_description_더_짧으면_유지() {
        // given: 기존 description이 더 길고 상세함
        Node existing = Node.builder().name("Docker").type("Technology")
                .description("Docker는 오픈소스 컨테이너 가상화 플랫폼입니다.").build();
        GraphData data = new GraphData(
                List.of(new GraphData.NodeDTO("Docker", "Technology", "컨테이너")),
                List.of()
        );
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.of(existing));

        graphService.saveGraph(data);

        // 새 description이 더 짧으므로 save 미호출 (기존 유지)
        verify(nodeRepository, never()).save(any(Node.class));
    }

    // ==================== P2-2: syncAllToNeo4j ====================

    @Test
    @DisplayName("[P2-2] syncAllToNeo4j: 전체 노드·엣지를 Neo4j에 MERGE")
    void syncAllToNeo4j_전체_노드_엣지_전달() {
        Node docker = buildNode("Docker", "Technology");
        Node redis = buildNode("Redis", "Database");
        Edge edge = Edge.builder().source(docker).target(redis).relation("uses").build();

        when(nodeRepository.findAll()).thenReturn(List.of(docker, redis));
        when(edgeRepository.findAllWithNodes()).thenReturn(List.of(edge));

        Map<String, Integer> result = graphService.syncAllToNeo4j();

        // 노드 2개, 엣지 1개 Neo4j 전달
        verify(neo4jClient, times(3)).query(anyString()); // 노드 2 + 엣지 1
        assertThat(result.get("nodes")).isEqualTo(2);
        assertThat(result.get("edges")).isEqualTo(1);
    }

    // ==================== P1-1: relation 정규화 ====================

    @Test
    @DisplayName("[P1-1] saveGraph: relation 대소문자 정규화 — USES → uses로 저장")
    void saveGraph_relation_대소문자_정규화() {
        Node docker = buildNode("Docker", "Technology");
        Node container = buildNode("Container", "Concept");
        GraphData data = new GraphData(
                List.of(
                        new GraphData.NodeDTO("Docker", "Technology", null),
                        new GraphData.NodeDTO("Container", "Concept", null)
                ),
                List.of(new GraphData.EdgeDTO("Docker", "Container", "USES"))
        );
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.of(docker));
        when(nodeRepository.findFirstByName("Container")).thenReturn(Optional.of(container));
        when(edgeRepository.existsBySourceIdAndTargetIdAndRelation(any(), any(), eq("uses"))).thenReturn(false);

        graphService.saveGraph(data);

        ArgumentCaptor<Edge> edgeCaptor = ArgumentCaptor.forClass(Edge.class);
        verify(edgeRepository).save(edgeCaptor.capture());
        assertThat(edgeCaptor.getValue().getRelation()).isEqualTo("uses");
    }

    @Test
    @DisplayName("[P1-1] saveGraph: 동일 relation 대소문자만 다른 엣지 → 중복으로 처리")
    void saveGraph_relation_중복_대소문자_동일처리() {
        Node docker = buildNode("Docker", "Technology");
        Node container = buildNode("Container", "Concept");
        GraphData data = new GraphData(
                List.of(
                        new GraphData.NodeDTO("Docker", "Technology", null),
                        new GraphData.NodeDTO("Container", "Concept", null)
                ),
                // "USES" → 정규화 후 "uses" → 이미 존재하므로 스킵
                List.of(new GraphData.EdgeDTO("Docker", "Container", "USES"))
        );
        when(nodeRepository.findFirstByName("Docker")).thenReturn(Optional.of(docker));
        when(nodeRepository.findFirstByName("Container")).thenReturn(Optional.of(container));
        when(edgeRepository.existsBySourceIdAndTargetIdAndRelation(any(), any(), eq("uses"))).thenReturn(true);

        graphService.saveGraph(data);

        verify(edgeRepository, never()).save(any(Edge.class));
    }

    // ==================== getRelatedKnowledge ====================

    @Test
    @DisplayName("getRelatedKnowledge: 키워드로 엣지 조회 후 포맷 반환")
    void getRelatedKnowledge_키워드로_엣지_조회() {
        String keyword = "Docker";
        Node source = buildNode("Docker", "Technology");
        Node target = buildNode("Container", "Concept");
        Edge edge = Edge.builder().source(source).target(target).relation("IS_A").build();
        when(edgeRepository.findEdgesByNodeName(keyword)).thenReturn(List.of(edge));

        String result = graphService.getRelatedKnowledge(keyword);

        verify(edgeRepository).findEdgesByNodeName(keyword);
        assertThat(result).contains("Docker");
        assertThat(result).contains("Container");
        assertThat(result).contains("IS_A");
    }

    @Test
    @DisplayName("getRelatedKnowledge: 결과 없으면 빈 문자열 반환")
    void getRelatedKnowledge_결과없으면_빈문자열() {
        when(edgeRepository.findEdgesByNodeName(anyString())).thenReturn(List.of());

        String result = graphService.getRelatedKnowledge("없는키워드");

        assertThat(result).isEmpty();
    }

    // ==================== [D-1] 이름 임베딩 ====================

    @Test
    @DisplayName("[D-1] saveGraph: 노드 저장 후 name_embedding UPDATE 쿼리 실행")
    void saveGraph_노드저장후_nameEmbedding_저장() {
        // given
        Node savedNode = buildNode("도커", "Technology");
        GraphData data = new GraphData(
                List.of(new GraphData.NodeDTO("도커", "Technology", null)),
                List.of()
        );
        when(nodeRepository.findFirstByName("도커")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenReturn(savedNode);

        // when
        graphService.saveGraph(data);

        // then: embeddingModel.embed() 호출 확인
        verify(embeddingModel).embed("도커");
        // jdbcTemplate.update() varargs — any(Object[].class)로 매처
        verify(jdbcTemplate).update(
                argThat(sql -> sql.contains("name_embedding")),
                any(Object[].class));
    }

    @Test
    @DisplayName("[D-1] saveGraph: 임베딩 실패해도 JPA 저장은 완료됨 (독립 try-catch)")
    void saveGraph_임베딩실패해도_노드저장_완료() {
        // given: 임베딩 모델이 예외 던짐
        Node savedNode = buildNode("도커", "Technology");
        GraphData data = new GraphData(
                List.of(new GraphData.NodeDTO("도커", "Technology", null)),
                List.of()
        );
        when(nodeRepository.findFirstByName("도커")).thenReturn(Optional.empty());
        when(nodeRepository.save(any(Node.class))).thenReturn(savedNode);
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("임베딩 모델 오류"));

        // when: 예외 발생해도 saveGraph 정상 완료
        assertThatNoException().isThrownBy(() -> graphService.saveGraph(data));

        // then: 노드는 저장됨
        verify(nodeRepository).save(any(Node.class));
    }
}
