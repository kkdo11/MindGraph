package org.mg.mindgraph.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mg.mindgraph.repository.EdgeRepository;
import org.mg.mindgraph.repository.NodeRepository;
import org.mg.mindgraph.service.ExtractionPublisher;
import org.mg.mindgraph.service.MindGraphService;
import org.mg.mindgraph.service.SearchService;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = MindGraphController.class,
        excludeAutoConfiguration = {
                Neo4jAutoConfiguration.class,
                Neo4jDataAutoConfiguration.class,
                Neo4jRepositoriesAutoConfiguration.class,
                RabbitAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                JpaRepositoriesAutoConfiguration.class
        }
)
@TestPropertySource(properties = "spring.jpa.open-in-view=false")
class MindGraphControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // @EnableJpaRepositories on MindGraphApplication registers JPA infrastructure
    // even when datasource is excluded — mock the factory to satisfy the dependency
    @MockBean(name = "entityManagerFactory")
    private EntityManagerFactory entityManagerFactory;

    @MockBean private SearchService searchService;
    @MockBean private MindGraphService mindGraphService;
    @MockBean private ExtractionPublisher extractionPublisher;
    @MockBean private NodeRepository nodeRepository;
    @MockBean private EdgeRepository edgeRepository;

    // ==================== P2-3: 입력 검증 ====================

    @Test
    @DisplayName("[P2-3] POST /extract: 빈 텍스트 → 400 Bad Request")
    void extract_빈텍스트_400() throws Exception {
        // @RequestBody String + application/json → StringHttpMessageConverter reads raw body.
        // Empty JSON string '""' arrives as the 2-char string '""' (quotes included),
        // so isBlank() is false and the "too short" branch fires instead.
        mockMvc.perform(post("/api/graph/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("텍스트가 너무 짧습니다. (최소 10자)"));

        verify(extractionPublisher, never()).publishExtractionRequest(anyString());
    }

    @Test
    @DisplayName("[P2-3] POST /extract: 10자 미만 텍스트 → 400 Bad Request")
    void extract_짧은텍스트_400() throws Exception {
        mockMvc.perform(post("/api/graph/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("\"짧음\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("텍스트가 너무 짧습니다. (최소 10자)"));

        verify(extractionPublisher, never()).publishExtractionRequest(anyString());
    }

    @Test
    @DisplayName("[P2-3] POST /extract: 50,000자 초과 텍스트 → 400 Bad Request")
    void extract_너무긴텍스트_400() throws Exception {
        String longText = "\"" + "A".repeat(50_001) + "\"";
        mockMvc.perform(post("/api/graph/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(longText))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("텍스트가 너무 깁니다. (최대 50000자)"));

        verify(extractionPublisher, never()).publishExtractionRequest(anyString());
    }

    @Test
    @DisplayName("[P2-3] POST /extract: 유효한 텍스트 → 202 Accepted")
    void extract_유효한텍스트_202() throws Exception {
        String validText = "\"Docker는 컨테이너 가상화 기술입니다. Kubernetes는 Docker를 오케스트레이션합니다.\"";
        mockMvc.perform(post("/api/graph/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validText))
                .andExpect(status().isAccepted());

        verify(extractionPublisher).publishExtractionRequest(anyString());
    }
}
