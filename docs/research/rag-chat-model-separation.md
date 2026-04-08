# 분석: RAG ChatLanguageModel 분리

> 목적: RAG 최종 답변 LLM 호출을 LLM-OPT 프록시에서 분리해 캐시 오염 방지

---

## 현재 상태

### LangChainConfig.java

단일 `chatLanguageModel` 빈만 존재. 모든 LLM 호출이 이 빈을 통해 LLM-OPT(:8000) 경유.

```java
@Bean
public ChatLanguageModel chatLanguageModel() {
    return OllamaChatModel.builder()
            .baseUrl(chatBaseUrl)        // :8000 (LLM-OPT)
            .modelName(chatModelName)
            .temperature(0.0)            // 하드코딩
            .timeout(Duration.ofSeconds(60))
            .build();
}
```

임베딩 빈은 이미 분리됨 (`embeddingBaseUrl` → :11434 직접).

### MindGraphService.java

`@RequiredArgsConstructor` 사용. `chatLanguageModel` 단일 필드 주입.

| 호출 위치 | 메서드 | 용도 | 변경 여부 |
|----------|--------|------|----------|
| ask() 마지막 줄 | `chatLanguageModel.generate(prompt.text())` | RAG 최종 답변 | **변경 대상** |
| extractKeywords() | `chatLanguageModel.generate(prompt.text())` | 키워드 추출 | 변경 없음 |

KnowledgeExtractor (@AiService)는 Spring이 자동으로 `chatLanguageModel` 주입 → 변경 없음.

### application.properties

```properties
langchain4j.ollama.chat-model.base-url=http://localhost:8000
langchain4j.ollama.chat-model.model-name=qwen2.5:14b
langchain4j.ollama.chat-model.temperature=0.2   # LangChainConfig에서 무시됨 (0.0 하드코딩)
langchain4j.ollama.embedding-model.base-url=http://localhost:11434
```

### build.gradle

LangChain4j `0.31.0` — Spring 6.0+ @Qualifier 완전 지원 확인.

---

## 변경 범위

**파일 3개 수정 / 테스트 1개 수정**

### 1. LangChainConfig.java

`@Value` 필드 1개 + `@Bean` 1개 추가.

```java
@Value("${langchain4j.ollama.rag-model.base-url:http://localhost:11434}")
private String ragBaseUrl;

@Bean
@Primary
public ChatLanguageModel chatLanguageModel() {
    // 기존 그대로, @Primary 추가만
}

@Bean(name = "ragChatLanguageModel")
public ChatLanguageModel ragChatLanguageModel() {
    return OllamaChatModel.builder()
            .baseUrl(ragBaseUrl)         // :11434 직접
            .modelName(chatModelName)
            .temperature(0.2)
            .timeout(Duration.ofSeconds(120))
            .build();
}
```

### 2. MindGraphService.java

필드 1개 추가 + ask() 마지막 줄 1개 변경.

```java
// 필드 추가
@Qualifier("ragChatLanguageModel")
private final ChatLanguageModel ragChatLanguageModel;

// ask() 마지막 줄 변경
// 전: return chatLanguageModel.generate(prompt.text());
// 후: return ragChatLanguageModel.generate(prompt.text());
```

### 3. application.properties

```properties
# 추가
langchain4j.ollama.rag-model.base-url=http://localhost:11434
```

### 4. MindGraphServiceTest.java

Mock 필드 1개 추가.

```java
@Mock
ChatLanguageModel chatLanguageModel;

@Mock(name = "ragChatLanguageModel")
ChatLanguageModel ragChatLanguageModel;
```

---

## 주의사항

1. **@Primary 필수**: 빈이 2개가 되면 `chatLanguageModel` 타입 단순 주입 시 ambiguous 에러. `@Primary` 추가해야 @AiService(@KnowledgeExtractor) 등 기존 의존성 안 깨짐.

2. **@Qualifier + @RequiredArgsConstructor**: Lombok이 생성자를 자동 생성하므로, @Qualifier를 **필드 위**에 선언해야 Spring이 인식함.

3. **timeout 분리**: RAG 답변 생성은 컨텍스트가 길어 60초를 초과할 수 있음. 120초로 여유있게 설정.

4. **테스트 Mock**: @Qualifier 없는 단순 @Mock은 타입으로 매칭 → 2개가 되면 충돌. `@Mock(name="...")` 으로 이름 지정 필요.

5. **LLM-OPT 효과**: 키워드 추출 / 지식 추출은 계속 :8000 경유 → 캐시 히트 유지. RAG 최종 답변은 :11434 직접 → 항상 최신 DB 기준 응답.
