package org.mg.mindgraph.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mg.mindgraph.ai.KnowledgeExtractor;
import org.mg.mindgraph.dto.GraphData;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExtractionListenerTest {

    @Mock
    private KnowledgeExtractor knowledgeExtractor;

    @Mock
    private GraphService graphService;

    @Mock
    private VectorService vectorService;

    @InjectMocks
    private ExtractionListener listener;

    private GraphData emptyGraph() {
        return new GraphData(List.of(), List.of());
    }

    @Test
    @DisplayName("정상 플로우: extract → saveGraph → embedAndSave 순서로 모두 호출")
    void 정상플로우_전체단계_실행() {
        String text = "Docker는 컨테이너 가상화 기술이다.";
        when(knowledgeExtractor.extract(text)).thenReturn(emptyGraph());

        listener.onExtractionRequest(text);

        verify(knowledgeExtractor).extract(text);
        verify(graphService).saveGraph(any(GraphData.class));
        verify(vectorService).embedAndSave(text);
    }

    @Test
    @DisplayName("[P1-2] LLM 추출 실패 시 embedAndSave는 반드시 호출됨 (원문 유실 방지)")
    void 추출실패해도_벡터저장_실행됨() {
        String text = "저장해야 할 중요한 지식.";
        when(knowledgeExtractor.extract(anyString())).thenThrow(new RuntimeException("LLM parsing failed"));

        listener.onExtractionRequest(text);

        verify(graphService, never()).saveGraph(any());
        verify(vectorService).embedAndSave(text);
    }

    @Test
    @DisplayName("[P1-2] saveGraph 실패 시에도 embedAndSave는 반드시 호출됨")
    void saveGraph_실패해도_벡터저장_실행됨() {
        String text = "저장해야 할 지식.";
        when(knowledgeExtractor.extract(anyString())).thenReturn(emptyGraph());
        doThrow(new RuntimeException("DB 저장 실패")).when(graphService).saveGraph(any());

        listener.onExtractionRequest(text);

        verify(vectorService).embedAndSave(text);
    }

    @Test
    @DisplayName("[P1-3] 2000자 이하 텍스트 → 청킹 없이 extract 1회만 호출")
    void 짧은텍스트_단일추출() {
        String text = "A".repeat(500);
        when(knowledgeExtractor.extract(text)).thenReturn(emptyGraph());

        listener.onExtractionRequest(text);

        verify(knowledgeExtractor, times(1)).extract(anyString());
    }

    @Test
    @DisplayName("[P1-3] 2000자 초과 텍스트 → 청킹되어 extract 여러 번 호출")
    void 긴텍스트_청킹_여러번_추출() {
        // 4200자 → 청크 3개: [0,2000), [1800,3800), [3600,4200)
        String text = "A".repeat(4200);
        when(knowledgeExtractor.extract(anyString())).thenReturn(emptyGraph());

        listener.onExtractionRequest(text);

        verify(knowledgeExtractor, atLeast(2)).extract(anyString());
    }

    @Test
    @DisplayName("[P1-3] 청킹 중 일부 청크 추출 실패 → 나머지 청크 정상 처리, embedAndSave 호출됨")
    void 청킹중_일부실패_나머지_정상처리() {
        String text = "B".repeat(4200);
        when(knowledgeExtractor.extract(anyString()))
                .thenThrow(new RuntimeException("첫 번째 청크 실패"))
                .thenReturn(emptyGraph())
                .thenReturn(emptyGraph());

        listener.onExtractionRequest(text);

        verify(vectorService).embedAndSave(text);
    }

    @Test
    @DisplayName("[P1-3] 청킹 결과 같은 이름 노드 중복 제거 — saveGraph에 노드 1개만 전달")
    void 청킹_노드_중복제거() {
        String text = "A".repeat(4200);
        GraphData chunkWithDocker = new GraphData(
                List.of(new GraphData.NodeDTO("Docker", "Technology", "컨테이너")),
                List.of()
        );
        when(knowledgeExtractor.extract(anyString())).thenReturn(chunkWithDocker);

        listener.onExtractionRequest(text);

        verify(graphService).saveGraph(argThat(data ->
                data.nodes().stream().filter(n -> n.name().equals("Docker")).count() == 1
        ));
    }
}
