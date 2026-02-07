package org.mg.mindgraph.service;

/*
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import notion.api.v1.NotionClient;
import notion.api.v1.model.pages.Page;
import notion.api.v1.request.databases.QueryDatabaseRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotionSyncService {

    private final ExtractionPublisher extractionPublisher;

    @Value("${mindgraph.notion.api-key}")
    private String apiKey;

    @Value("${mindgraph.notion.database-id}")
    private String databaseId;

    private NotionClient notionClient;

    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.equals("YOUR_NOTION_API_KEY_HERE")) {
            log.warn("Notion API key is not configured. NotionSyncService will be disabled.");
            return;
        }
        this.notionClient = new NotionClient(apiKey);
        log.info("NotionClient initialized.");
    }

    public void syncDatabase() {
        if (notionClient == null) {
            log.warn("NotionSyncService is disabled because API key is not set.");
            return;
        }

        log.info("Starting Notion database sync for database ID: {}", databaseId);

        try {
            // Notion API는 페이지네이션을 지원합니다. 모든 페이지를 가져오려면 반복 처리가 필요합니다.
            String nextCursor = null;
            do {
                QueryDatabaseRequest request = new QueryDatabaseRequest(databaseId, nextCursor);
                List<Page> pages = notionClient.queryDatabase(request).getResults();

                for (Page page : pages) {
                    // 페이지에서 텍스트 콘텐츠를 추출하는 로직 (향후 구체화)
                    String pageContent = extractTextFromPage(page);
                    if (pageContent != null && !pageContent.isBlank()) {
                        // 비동기 처리를 위해 RabbitMQ에 발행
                        extractionPublisher.publishExtractionRequest(pageContent);
                    }
                }
                nextCursor = notionClient.queryDatabase(request).getNextCursor();
            } while (nextCursor != null);

            log.info("Finished Notion database sync for database ID: {}", databaseId);

        } catch (Exception e) {
            log.error("Failed to sync Notion database.", e);
        }
    }

    
     * Notion 페이지 객체에서 텍스트 콘텐츠를 추출하는 헬퍼 메소드입니다.
     * 현재는 제목(Title) 속성만 추출하는 간단한 예시입니다.
     * 추후 블록(Block) 내용을 순회하며 전체 텍스트를 조합하는 로직으로 발전시켜야 합니다.
     * @param page Notion Page 객체
     * @return 추출된 텍스트
     
    private String extractTextFromPage(Page page) {
        // "Name" 또는 "제목" 이라는 이름의 title 속성을 찾는다고 가정
        return page.getProperties().values().stream()
                .filter(prop -> prop.getType() == notion.api.v1.model.databases.Property.Type.Title)
                .findFirst()
                .map(prop -> prop.getTitle().get(0).getText().getContent())
                .orElse(null);
    }
}
*/
