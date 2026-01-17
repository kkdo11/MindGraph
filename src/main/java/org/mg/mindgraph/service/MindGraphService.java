package org.mg.mindgraph.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mg.mindgraph.dto.SearchResult;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MindGraphService {

    private final SearchService searchService;
    private final ChatLanguageModel chatLanguageModel;

    // RAG 프롬프트 템플릿
    private static final String RAG_PROMPT_TEMPLATE = """
            당신은 사용자의 '제2의 뇌' 역할을 하는 AI 비서입니다.
            아래 제공된 [관련 지식]을 바탕으로 사용자의 질문에 답변하세요.
            만약 관련 지식에 답이 없다면 "제 기억에는 없는 내용입니다."라고 솔직하게 말하세요.
            
            [관련 지식]
            {{context}}
            
            [사용자 질문]
            {{question}}
            """;

    public String ask(String question) {
        // 1. 질문과 관련된 지식 검색 (Retrieve)
        List<SearchResult> searchResults = searchService.search(question);
        
        // 검색된 내용이 없으면 조기 반환 (Fail Fast)
        if (searchResults.isEmpty()) {
            return "해당 내용에 대해 아는 바가 없습니다.";
        }

        // 2. 검색 결과를 하나의 문자열로 병합 (Context 구성)
        String context = searchResults.stream()
                .map(SearchResult::content)
                .collect(Collectors.joining("\n"));

        // 3. 프롬프트 구성 (Augment)
        Map<String, Object> variables = new HashMap<>();
        variables.put("context", context);
        variables.put("question", question);
        
        Prompt prompt = PromptTemplate.from(RAG_PROMPT_TEMPLATE).apply(variables);

        // 4. LLM 답변 생성 (Generate)
        String answer = chatLanguageModel.generate(prompt.text());
        
        log.info("Answer generated for query: {}", question);
        return answer;
    }
}