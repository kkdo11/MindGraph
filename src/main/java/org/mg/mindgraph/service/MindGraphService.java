package org.mg.mindgraph.service;


import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.dto.SearchResult;
import org.mg.mindgraph.entity.Edge;
import org.mg.mindgraph.entity.Node;
import org.mg.mindgraph.repository.EdgeRepository;
import org.mg.mindgraph.repository.NodeRepository;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MindGraphService {

    private final SearchService searchService;
    private final GraphService graphService;
    private final NodeRepository nodeRepository;
    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper; // JSON 파싱을 위해 주입 필요
    private final EdgeRepository edgeRepository;

    // 정규식: 대괄호 [ ... ] 사이의 내용을 추출 (JSON Array)
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

    private static final String RAG_PROMPT_TEMPLATE = """
        [Role]
        You are an intelligent 'Second Brain' AI assistant.
        Your goal is to answer the user's question accurately using the provided [Context].

        [Instructions]
        1. **Source Attribution**: You MUST specify the source for each piece of information.
           - Information from Knowledge Graph: [Source: Knowledge Graph]
           - Information from Text Documents: [Source: Documents]
        2. **Relationship Clarity**: When describing graph data, clearly explain the nature of the relationship (e.g., "A is the founder of B").
        3. **Conflict Resolution**: If information from different sources conflicts, prioritize [Knowledge Graph].
        4. **Language Constraint**: Regardless of the context language, you MUST answer in KOREAN.
        5. **Groundedness**: Answer ONLY based on the provided context. If the answer is not in the context, say "정보를 찾을 수 없습니다." Do not use internal knowledge.

        [Context]
        {{context}}

        [User Question]
        {{question}}

        [Answer Example]
        - Manus AI는 2026년에 설립된 기업입니다. [Source: Knowledge Graph]
        """;

    // 개선됨: JSON 포맷 강제 및 명사/숫자(연도) 포함 명시
    private static final String KEYWORD_EXTRACTION_PROMPT = """
        [Role]
        You are a high-precision query analyzer for a RAG system.
        
        [Task]
        Extract key entities and temporal values (years) from the user's question to be used as database search keys.

        [Output Rules]
        1. **Format**: Return strictly a JSON list of strings.
        2. **Example**: ["Manus", "2026", "AI"]
        3. **Constraint**: No conversational text, no explanations, no markdown blocks. Just the raw JSON array.
        
        Question: {{question}}
        """;

    public String ask(String question) {
        log.info("Processing question: {}", question);

        // Task 1: 벡터 검색
        CompletableFuture<String> vectorSearchFuture = CompletableFuture.supplyAsync(() -> {
            List<SearchResult> results = searchService.search(question);
            return results.stream()
                    .map(SearchResult::content)
                    .collect(Collectors.joining("\n"));
        });

        // Task 2: 그래프 검색
        CompletableFuture<String> graphSearchFuture = CompletableFuture.supplyAsync(() ->
                searchGraph(question)
        );

        String vectorContext = vectorSearchFuture.join();
        String graphContext = graphSearchFuture.join();

        // 컨텍스트 통합
        String finalContext = String.format("""
                [텍스트 문서 정보]
                %s
                
                [지식 그래프 정보]
                %s
                """,
                vectorContext.isEmpty() ? "관련 문서 없음" : vectorContext,
                graphContext.isEmpty() ? "관련 그래프 데이터 없음" : graphContext
        );

        // 답변 생성
        Prompt prompt = PromptTemplate.from(RAG_PROMPT_TEMPLATE)
                .apply(Map.of("context", finalContext, "question", question));

        return chatLanguageModel.generate(prompt.text());
    }

    private String searchGraph(String question) {
        List<String> keywords = extractKeywords(question);
        if (keywords.isEmpty()) return "";

        // 1. 키워드 분류
        List<String> entityKeywords = keywords.stream().filter(k -> !k.matches("\\d{4}")).toList();
        List<String> yearKeywords = keywords.stream().filter(k -> k.matches("\\d{4}")).toList();

        // 2. 엔티티 노드 Batch 조회 (이미 배치로 구현됨)
        List<Node> nodes = nodeRepository.findByNameIn(entityKeywords);

        // 3. [최적화 핵심] 모든 관련 엣지를 한 번의 쿼리로 가져오기
        List<String> nodeNames = nodes.stream().map(Node::getName).toList();
        List<Edge> allRelatedEdges = edgeRepository.findEdgesByNodeNamesIn(nodeNames); // 새 메서드

        // 4. 메모리 상에서 데이터 가공 (DB I/O 없이 처리)
        return allRelatedEdges.stream()
                .map(edge -> formatEdgeToKnowledge(edge)) // 엣지를 텍스트로 변환하는 헬퍼 메서드
                .filter(info -> yearKeywords.isEmpty() || yearsMatch(info, yearKeywords))
                .distinct()
                .collect(Collectors.joining("\n"));
    }

    /**
     * 지식 텍스트 내에서 특정 연도와 관련된 정보만 추출하거나 강조합니다.
     */
    private String filterByYear(String knowledge, List<String> years) {
        return Arrays.stream(knowledge.split("\n"))
                .filter(line -> years.stream().anyMatch(line::contains))
                .collect(Collectors.joining("\n"));
    }

    /**
     * LLM을 사용하여 질문에서 키워드를 추출하고 JSON 파싱을 수행합니다.
     * (Single Responsibility: Keyword Extraction & Parsing)
     */
    private List<String> extractKeywords(String question) {
        try {
            Prompt prompt = PromptTemplate.from(KEYWORD_EXTRACTION_PROMPT)
                    .apply(Map.of("question", question));
            String response = chatLanguageModel.generate(prompt.text());

            // LLM 응답에서 JSON Array 부분만 추출
            Matcher matcher = JSON_ARRAY_PATTERN.matcher(response);
            if (matcher.find()) {
                String jsonPart = matcher.group();
                // ObjectMapper가 정상 작동한다면 여기서 결과가 반환됩니다.
                return Arrays.asList(objectMapper.readValue(jsonPart, String[].class));
            } else {
                log.warn("Failed to find JSON array in LLM response: {}", response);
                return Collections.emptyList();
            }
        } catch (Exception e) {
            // 모든 종류의 예외(파싱 에러, 라이브러리 에러, LLM 응답 에러 등)를 여기서 통합 처리합니다.
            log.error("Keyword extraction failed for question: '{}'. Error: {}", question, e.getMessage());
            return Collections.emptyList();
        }
    }

    private String formatEdgeToKnowledge(Edge edge) {
        return String.format("- %s(%s)은(는) %s(%s)와(과) '%s' 관계가 있습니다.",
                edge.getSource().getName(), edge.getSource().getType(),
                edge.getTarget().getName(), edge.getTarget().getType(),
                edge.getRelation());
    }

    /**
     * 연도 필터링 로직 (메모리 상에서 처리하여 DB I/O 절감)
     */
    private boolean yearsMatch(String info, List<String> years) {
        return years.stream().anyMatch(info::contains);
    }
}