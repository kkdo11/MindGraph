package org.mg.mindgraph.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel; // 추가
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel; // 추가
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

@Configuration
public class LangChainConfig {

    @Value("${langchain4j.ollama.chat-model.base-url}")
    private String chatBaseUrl;

    @Value("${langchain4j.ollama.embedding-model.base-url}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.ollama.rag-model.base-url:http://localhost:11434}")
    private String ragBaseUrl;

    @Value("${langchain4j.ollama.chat-model.model-name}")
    private String chatModelName;

    @Value("${langchain4j.ollama.embedding-model.model-name}")
    private String embeddingModelName;

    @Bean
    @Primary
    public ChatLanguageModel chatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl(chatBaseUrl)
                .modelName(chatModelName)
                .temperature(0.0) // 지식 추출 / 키워드 추출용 (LLM-OPT 캐싱)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    // RAG 최종 답변 전용 — Ollama 직접 호출 (DB 의존적이므로 캐싱 불가)
    @Bean(name = "ragChatLanguageModel")
    public ChatLanguageModel ragChatLanguageModel() {
        return OllamaChatModel.builder()
                .baseUrl(ragBaseUrl)
                .modelName(chatModelName)
                .temperature(0.2)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    // 임베딩 모델은 Ollama 직접 호출 (프록시 우회)
    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .modelName(embeddingModelName) // mxbai-embed-large
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}