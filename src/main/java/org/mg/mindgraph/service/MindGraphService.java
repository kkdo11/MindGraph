package org.mg.mindgraph.service;


import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.dto.NodeInsight;
import org.mg.mindgraph.dto.NodeWithScore;
import org.mg.mindgraph.dto.RankedContext;
import org.mg.mindgraph.dto.SearchResult;
import org.mg.mindgraph.entity.Edge;
import org.mg.mindgraph.entity.Node;
import org.mg.mindgraph.repository.EdgeRepository;
import org.mg.mindgraph.repository.NodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.Objects;
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
    private final ChatLanguageModel chatLanguageModel; // 키워드/지식 추출 — LLM-OPT 캐싱
    private final ObjectMapper objectMapper;
    private final EdgeRepository edgeRepository;
    private final Neo4jClient neo4jClient;
    private final EmbeddingModel embeddingModel; // 노드 이름 유사도 검색용

    @Autowired
    @Qualifier("ragChatLanguageModel")
    private ChatLanguageModel ragChatLanguageModel; // RAG 최종 답변 — Ollama 직접 호출

    // 정규식: 대괄호 [ ... ] 사이의 내용을 추출 (JSON Array)
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile("\\[.*?\\]", Pattern.DOTALL);

    // 컨텍스트 최대 문자 수 — 토큰 예산 관리 (4000자 ≈ ~1000토큰)
    private static final int MAX_CONTEXT_CHARS = 4000;

    // [F-1c] RRF (Reciprocal Rank Fusion) 파라미터 — 스케일 차이 없이 다중 소스 결과를 통합
    @Value("${mindgraph.search.rrf-k:60}")
    private int rrfK;

    private static final String RAG_PROMPT_TEMPLATE = """
        반드시 한국어로만 답변하세요.

        [역할]
        당신은 개인 지식 그래프 AI 어시스턴트입니다.
        아래 [컨텍스트]에 있는 정보를 바탕으로 사용자 질문에 답변합니다.

        [지침]
        1. [컨텍스트]에 있는 정보만 사용하여 답변하세요.
        2. 그래프 데이터는 "[지식 그래프]", 문서는 "[문서]" 출처를 표시하세요.
        3. 컨텍스트에 없는 내용은 "해당 정보가 그래프에 없습니다."라고 말하세요.
        4. 반드시 한국어로만 답변하세요. 절대 중국어, 영어로 답변하지 마세요.

        [컨텍스트]
        {{context}}

        [질문]
        {{question}}

        [답변] (한국어로):
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

        // [F-6b] 키워드 추출 (1회) — 벡터 검색 확장 + 그래프 검색 양쪽에 재사용
        List<String> keywords = extractKeywords(question);

        // [F-6b] Query Expansion — 키워드로 찾은 노드 description을 질문에 concat
        String expandedQuestion = expandQuery(question, keywords);
        log.debug("Query expanded: '{}' → '{}'", question,
                expandedQuestion.length() > 200 ? expandedQuestion.substring(0, 200) + "..." : expandedQuestion);

        // Task 1: 벡터 검색 — 확장된 질문으로 (병렬)
        CompletableFuture<List<SearchResult>> vectorFuture = CompletableFuture.supplyAsync(() ->
                searchService.search(expandedQuestion)
        );

        // Task 2: 그래프 검색 — 이미 추출된 키워드 재사용 (병렬)
        CompletableFuture<List<RankedContext>> graphFuture = CompletableFuture.supplyAsync(() ->
                searchGraphRankedWithKeywords(question, keywords)
        );

        List<SearchResult> vectorResults = vectorFuture.join();
        List<RankedContext> graphResults = graphFuture.join();

        // 컨텍스트 조립 (score 정렬 + 중복 제거 + 토큰 예산)
        String context = assembleContext(vectorResults, graphResults);

        Prompt prompt = PromptTemplate.from(RAG_PROMPT_TEMPLATE)
                .apply(Map.of("context", context, "question", question));

        return ragChatLanguageModel.generate(prompt.text());
    }

    /**
     * [F-1c] 그래프 검색 결과를 RankedContext 리스트로 반환합니다.
     * 노드 유사도를 기반으로 동적 점수를 부여합니다 (기존 고정 0.7/0.6/0.4 제거).
     */
    private List<RankedContext> searchGraphRanked(String question) {
        return searchGraphRankedWithKeywords(question, extractKeywords(question));
    }

    private List<RankedContext> searchGraphRankedWithKeywords(String question, List<String> keywords) {
        if (keywords.isEmpty()) return List.of();

        // 키워드 분류: 엔티티 vs 연도
        List<String> entityKeywords = keywords.stream().filter(k -> !k.matches("\\d{4}")).toList();
        List<String> yearKeywords = keywords.stream().filter(k -> k.matches("\\d{4}")).toList();

        List<RankedContext> results = new ArrayList<>();

        // [F-1c] PostgreSQL 그래프 검색 — 노드 유사도 점수 함께 가져오기
        List<NodeWithScore> nodesWithScore = findNodesWithScore(entityKeywords);
        List<String> nodeNames = nodesWithScore.stream().map(ns -> ns.node().getName()).toList();

        // 노드 이름 → 최대 유사도 매핑 (엣지 점수 산출용)
        Map<String, Double> nodeScoreMap = new HashMap<>();
        for (NodeWithScore ns : nodesWithScore) {
            nodeScoreMap.merge(ns.node().getName(), ns.similarity(), Math::max);
        }

        List<Edge> allRelatedEdges = edgeRepository.findEdgesByNodeNamesIn(nodeNames);

        allRelatedEdges.stream()
                .filter(edge -> yearKeywords.isEmpty() || yearsMatch(formatEdgeToKnowledge(edge), yearKeywords))
                .distinct()
                .forEach(edge -> {
                    String content = formatEdgeToKnowledge(edge);
                    // 엣지 점수 = 연결된 노드 중 더 높은 유사도 사용
                    double nodeSim = Math.max(
                            nodeScoreMap.getOrDefault(edge.getSource().getName(), 0.0),
                            nodeScoreMap.getOrDefault(edge.getTarget().getName(), 0.0));
                    results.add(new RankedContext(content, nodeSim, RankedContext.Source.PG_GRAPH));
                });

        // Neo4j 2-hop 확장 검색 (독립 try-catch — 실패 시 PG 결과만 반환)
        // [F-1a] entityKeywords + nodeNames union — PG에만 있는 노드, Neo4j에만 있는 노드 모두 커버
        List<String> neo4jSearchNames = new ArrayList<>(nodeNames);
        entityKeywords.stream().filter(k -> !nodeNames.contains(k)).forEach(neo4jSearchNames::add);
        try {
            List<String> neo4jRaw = searchNeo4jTwoHop(neo4jSearchNames, yearKeywords);
            for (int i = 0; i < neo4jRaw.size(); i++) {
                String info = neo4jRaw.get(i);
                boolean isTwoHop = info.contains("[2-hop]");
                RankedContext.Source source = isTwoHop ? RankedContext.Source.NEO4J_2HOP : RankedContext.Source.NEO4J_1HOP;
                // Neo4j 결과도 시작 노드 유사도 기반 — 1-hop은 80%, 2-hop은 50% 감쇠
                double baseSim = nodesWithScore.isEmpty() ? 0.5 :
                        nodesWithScore.stream().mapToDouble(NodeWithScore::similarity).max().orElse(0.5);
                double score = isTwoHop ? baseSim * 0.5 : baseSim * 0.8;
                results.add(new RankedContext(info, score, source));
            }
        } catch (Exception e) {
            log.warn("Neo4j 2-hop search failed, using PostgreSQL results only: {}", e.getMessage());
        }

        return results;
    }

    /**
     * [F-6b] 키워드로 찾은 노드의 description을 질문에 concat하여 벡터 검색 재현율을 높입니다.
     * LLM 추가 호출 없이 기존 DB 데이터만 활용합니다.
     *
     * <p>예: "Docker란?" → "Docker란? Docker: 컨테이너 가상화 기술..."
     * 짧은 질문과 긴 청크 사이의 구조적 유사도 저하(#10)를 완화합니다.
     */
    /**
     * [F-6b] 키워드로 찾은 노드의 description을 질문에 concat하여 벡터 검색 재현율을 높입니다.
     * LLM 추가 호출 없이 기존 DB 데이터만 활용합니다.
     *
     * <p>1차: exact name match (findByNameIn) — 빠른 조회
     * <p>2차: 매칭 안 된 키워드는 embedding similarity fallback — 언어 불일치 보완
     * ("Docker" 키워드로 "도커" 노드를 찾거나, "도커" 키워드로 "Docker" 노드를 찾음)
     */
    private String expandQuery(String question, List<String> keywords) {
        if (keywords.isEmpty()) return question;

        List<String> entityKeywords = keywords.stream().filter(k -> !k.matches("\\d{4}")).toList();

        // 1차: exact match
        List<Node> exactNodes = nodeRepository.findByNameIn(entityKeywords);
        Set<Long> foundIds = exactNodes.stream()
                .map(Node::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> matchedLower = exactNodes.stream()
                .map(n -> n.getName().toLowerCase()).collect(Collectors.toSet());

        // 2차: exact miss 키워드는 embedding similarity fallback
        List<Node> allNodes = new ArrayList<>(exactNodes);
        for (String kw : entityKeywords) {
            if (!matchedLower.contains(kw.toLowerCase())) {
                try {
                    String vec = embeddingModel.embed(kw).content().vectorAsList().toString();
                    nodeRepository.findSimilarByNameEmbedding(vec, 0.7, 1).stream()
                            .filter(n -> n.getId() == null || !foundIds.contains(n.getId()))
                            .findFirst()
                            .ifPresent(n -> {
                                allNodes.add(n);
                                if (n.getId() != null) foundIds.add(n.getId());
                                log.debug("Query expansion: '{}' → semantic match '{}'", kw, n.getName());
                            });
                } catch (Exception e) {
                    log.warn("Query expansion semantic fallback failed for '{}': {}", kw, e.getMessage());
                }
            }
        }

        StringBuilder expansion = new StringBuilder(question);
        for (Node node : allNodes) {
            if (node.getDescription() != null && !node.getDescription().isBlank()) {
                expansion.append(" ").append(node.getName()).append(": ").append(node.getDescription());
            }
        }

        // 확장된 질문이 너무 길면 임베딩 품질 저하 — 1000자 제한
        String expanded = expansion.toString();
        if (expanded.length() > 1000) {
            expanded = expanded.substring(0, 1000);
        }
        return expanded;
    }

    /**
     * [F-1c] RRF(Reciprocal Rank Fusion)로 벡터 결과와 그래프 결과를 통합합니다.
     * 각 소스의 순위(rank) 기반으로 최종 순위를 결정하여 스케일 차이 문제를 해소합니다.
     *
     * <p>RRF_score = Σ (1 / (k + rank_i)) — 여러 소스에서 나온 결과는 점수가 합산됨.
     */
    private String assembleContext(List<SearchResult> vectorResults, List<RankedContext> graphResults) {
        // Fallback: 둘 다 비어있으면 허브/최근 노드 요약으로 대체
        if (vectorResults.isEmpty() && graphResults.isEmpty()) {
            String summary = buildFullGraphSummary();
            return String.format("""
                    [텍스트 문서 정보]
                    관련 문서 없음

                    [지식 그래프 정보]
                    %s
                    """, summary.isEmpty() ? "관련 그래프 데이터 없음" : summary);
        }

        // 1. 각 소스별 결과를 score 내림차순 정렬하여 rank 부여
        List<RankedContext> vectorRanked = vectorResults.stream()
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .map(sr -> new RankedContext(sr.content(), sr.score(), RankedContext.Source.VECTOR))
                .toList();

        List<RankedContext> graphRanked = graphResults.stream()
                .sorted(Comparator.comparingDouble(RankedContext::score).reversed())
                .toList();

        // 2. RRF score 계산: content → (rrfScore, source) 매핑
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, RankedContext> contextMap = new LinkedHashMap<>();

        for (int rank = 0; rank < vectorRanked.size(); rank++) {
            RankedContext ctx = vectorRanked.get(rank);
            String key = ctx.content().substring(0, Math.min(50, ctx.content().length()));
            rrfScores.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
            contextMap.putIfAbsent(key, ctx);
        }
        for (int rank = 0; rank < graphRanked.size(); rank++) {
            RankedContext ctx = graphRanked.get(rank);
            String key = ctx.content().substring(0, Math.min(50, ctx.content().length()));
            rrfScores.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
            contextMap.putIfAbsent(key, ctx);
        }

        // 3. RRF score 내림차순 정렬
        List<Map.Entry<String, Double>> sorted = rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();

        // 4. 토큰 예산 적용 + 섹션 분리
        List<String> vectorParts = new ArrayList<>();
        List<String> graphParts = new ArrayList<>();
        int totalChars = 0;

        for (Map.Entry<String, Double> entry : sorted) {
            if (totalChars >= MAX_CONTEXT_CHARS) break;
            RankedContext ctx = contextMap.get(entry.getKey());
            if (ctx == null) continue;

            int remaining = MAX_CONTEXT_CHARS - totalChars;
            String content = ctx.content().length() <= remaining
                    ? ctx.content()
                    : ctx.content().substring(0, remaining);
            totalChars += content.length();

            if (ctx.source() == RankedContext.Source.VECTOR) {
                vectorParts.add(content);
            } else {
                graphParts.add(content);
            }
        }

        log.debug("Context assembled (RRF k={}): {}chars, {} vector, {} graph entries",
                rrfK, totalChars, vectorParts.size(), graphParts.size());

        return String.format("""
                [텍스트 문서 정보]
                %s

                [지식 그래프 정보]
                %s
                """,
                vectorParts.isEmpty() ? "관련 문서 없음" : String.join("\n", vectorParts),
                graphParts.isEmpty() ? "관련 그래프 데이터 없음" : String.join("\n", graphParts)
        );
    }

    /**
     * Neo4j에서 키워드별 1~2-hop 연관 노드를 조회하고 텍스트로 반환합니다.
     * 1-hop: 직접 연결된 노드 / 2-hop: 연결 노드의 연결 노드 ([2-hop] suffix 포함)
     */
    private List<String> searchNeo4jTwoHop(List<String> entityKeywords, List<String> yearKeywords) {
        List<String> results = new ArrayList<>();
        for (String keyword : entityKeywords) {
            Collection<String> found = neo4jClient.query("""
                            MATCH (start:KnowledgeNode {name: $name})-[r1:RELATED_TO]->(mid:KnowledgeNode)
                            RETURN start.name + '(' + start.type + ')은(는) ' +
                                   mid.name + '(' + mid.type + ')와(과) \\'' + r1.relation + '\\' 관계가 있습니다.' AS info
                            UNION
                            MATCH (start2:KnowledgeNode {name: $name})-[:RELATED_TO]->(mid2:KnowledgeNode)
                                  -[r2:RELATED_TO]->(end2:KnowledgeNode)
                            WHERE end2.name <> $name
                            RETURN mid2.name + '(' + mid2.type + ')은(는) ' +
                                   end2.name + '(' + end2.type + ')와(과) \\'' + r2.relation + '\\' 관계가 있습니다. [2-hop]' AS info
                            """)
                    .bind(keyword).to("name")
                    .fetchAs(String.class)
                    .mappedBy((typeSystem, record) -> record.get("info").asString())
                    .all();
            found.stream()
                    .filter(info -> yearKeywords.isEmpty() || yearsMatch(info, yearKeywords))
                    .forEach(results::add);
        }
        return results.stream().distinct().toList();
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

    /**
     * 컨텍스트가 없을 때 허브 노드(degree 기준) + 최근 노드로 그래프 요약을 생성합니다.
     * findAll() 대신 허브/최근 노드 각 10개를 조회하여 OOM 위험을 제거합니다.
     */
    private String buildFullGraphSummary() {
        // 허브 노드(degree 기준) 10개 + 최근 추가 노드 10개
        List<Node> hubNodes = nodeRepository.findTopNodesByDegree(10);
        List<Node> recentNodes = nodeRepository.findRecentNodes(PageRequest.of(0, 10));

        // 합집합 (허브 우선, 중복 제거)
        Set<Long> seen = new LinkedHashSet<>();
        List<Node> selected = new ArrayList<>();
        for (Node n : hubNodes) {
            if (seen.add(n.getId())) selected.add(n);
        }
        for (Node n : recentNodes) {
            if (seen.add(n.getId())) selected.add(n);
        }

        if (selected.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("저장된 전체 지식 그래프 요약 (주요 노드):\n");
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (Node n : selected) {
            byType.computeIfAbsent(n.getType(), k -> new ArrayList<>()).add(n.getName());
        }
        byType.forEach((type, names) ->
                sb.append(String.format("- [%s]: %s\n", type, String.join(", ", names)))
        );

        // 선택된 노드 간 주요 관계 최대 15개
        List<String> nodeNames = selected.stream().map(Node::getName).toList();
        List<Edge> edges = edgeRepository.findEdgesByNodeNamesIn(nodeNames);
        if (!edges.isEmpty()) {
            sb.append("\n주요 관계:\n");
            edges.stream().limit(15).forEach(e ->
                    sb.append(String.format("- %s → %s (%s)\n",
                            e.getSource().getName(), e.getTarget().getName(), e.getRelation()))
            );
        }
        return sb.toString();
    }

    /**
     * 지식 그래프에서 중심성(Degree Centrality) 기준 상위 노드를 반환합니다.
     * GDS 플러그인 없이 순수 Cypher로 degree를 계산합니다.
     *
     * <p>우선순위:
     * <ol>
     *   <li>Neo4j — OPTIONAL MATCH로 연결 수 집계 (실측 degree 반환)</li>
     *   <li>PostgreSQL fallback — Neo4j 불가 시 findTopNodesByDegree() 사용 (degree=0)</li>
     * </ol>
     *
     * @param limit 반환할 최대 노드 수
     */
    public List<NodeInsight> getInsights(int limit) {
        // 1. Neo4j Degree Centrality (순수 Cypher — GDS 플러그인 불필요)
        try {
            Collection<NodeInsight> results = neo4jClient.query("""
                    MATCH (n:KnowledgeNode)
                    OPTIONAL MATCH (n)-[r]-()
                    RETURN n.name AS name, n.type AS type, count(r) AS degree
                    ORDER BY degree DESC
                    LIMIT $limit
                    """)
                    .bind(limit).to("limit")
                    .fetchAs(NodeInsight.class)
                    .mappedBy((typeSystem, record) -> new NodeInsight(
                            record.get("name").asString(),
                            record.get("type").asString(),
                            record.get("degree").asLong()
                    ))
                    .all();

            if (!results.isEmpty()) {
                log.info("Neo4j centrality query: {} nodes returned", results.size());
                return new ArrayList<>(results);
            }
        } catch (Exception e) {
            log.warn("Neo4j centrality query failed, falling back to PostgreSQL: {}", e.getMessage());
        }

        // 2. PostgreSQL fallback — degree 실측값 없음(0으로 표시), 정렬 순서만 유효
        log.debug("Using PostgreSQL fallback for insights (degree=0)");
        return nodeRepository.findTopNodesByDegree(limit).stream()
                .map(n -> new NodeInsight(n.getName(), n.getType(), 0L))
                .toList();
    }

    /**
     * [P2-2] PostgreSQL 전체 노드·엣지를 Neo4j에 재동기화합니다. GraphService에 위임.
     */
    public Map<String, Integer> syncAllToNeo4j() {
        return graphService.syncAllToNeo4j();
    }

    /**
     * [D-1] name_embedding 미생성 노드를 일괄 임베딩합니다. GraphService에 위임.
     */
    public Map<String, Integer> rebuildNodeEmbeddings() {
        return graphService.rebuildNodeEmbeddings();
    }

    private String formatEdgeToKnowledge(Edge edge) {
        return String.format("- %s(%s)은(는) %s(%s)와(과) '%s' 관계가 있습니다.",
                edge.getSource().getName(), edge.getSource().getType(),
                edge.getTarget().getName(), edge.getTarget().getType(),
                edge.getRelation());
    }

    /**
     * [F-1c] 키워드 임베딩 → 노드 이름 벡터 유사도로 노드를 탐색합니다.
     * 각 노드의 유사도 점수를 함께 반환하여 RRF 동적 스코어링에 사용합니다.
     *
     * <p>중복 제거: ID 기준, 더 높은 유사도를 가진 쪽 유지.
     * threshold=0.6: bge-m3 기준 한/영 교차 언어 매칭에 충분한 임계값.
     */
    private List<NodeWithScore> findNodesWithScore(List<String> keywords) {
        Map<Long, NodeWithScore> seen = new LinkedHashMap<>();
        for (String keyword : keywords) {
            try {
                String vec = embeddingModel.embed(keyword).content().vectorAsList().toString();
                for (Object[] row : nodeRepository.findSimilarWithScore(vec, 0.6, 3)) {
                    Long id = ((Number) row[0]).longValue();
                    String name = (String) row[1];
                    double similarity = ((Number) row[2]).doubleValue();

                    if (!seen.containsKey(id) || seen.get(id).similarity() < similarity) {
                        // Node 엔티티 조회를 피하기 위해 최소한의 정보만 가진 프록시 생성
                        Node node = nodeRepository.findById(id).orElse(null);
                        if (node != null) {
                            seen.put(id, new NodeWithScore(node, similarity));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Node similarity search failed for keyword '{}': {}", keyword, e.getMessage());
            }
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * 연도 필터링 로직 (메모리 상에서 처리하여 DB I/O 절감)
     */
    private boolean yearsMatch(String info, List<String> years) {
        return years.stream().anyMatch(info::contains);
    }
}