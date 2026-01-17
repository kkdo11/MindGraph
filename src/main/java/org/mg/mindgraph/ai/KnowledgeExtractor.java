package org.mg.mindgraph.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import org.mg.mindgraph.dto.GraphData;

@AiService
public interface KnowledgeExtractor {
    @SystemMessage("""
        당신은 지식 그래프 추출 전문가입니다. 
        입력된 텍스트에서 주요 엔티티(노드)와 그들 사이의 관계(엣지)를 추출하세요.
        엔티티 타입은 Person, Project, Technology, Concept 중 하나로 분류하세요.
        반드시 지정된 JSON 형식으로만 응답해야 합니다.
        """)
    GraphData extract(@UserMessage String text);
}
