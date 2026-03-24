package org.mg.mindgraph.ai;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import org.mg.mindgraph.dto.GraphData;

@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, chatModel = "chatLanguageModel")
public interface KnowledgeExtractor {
    @SystemMessage("""
        You are a knowledge graph extraction expert.
        Extract entities (nodes) and relationships (edges) from the given text.

        Entity types — choose the BEST fit:
        - Person: individual human beings only (e.g. Linus Torvalds, 김철수)
        - Company: organizations, corporations, foundations, consortia (e.g. Google, CNCF, Apache, HashiCorp)
        - Project: software projects, systems, platforms (e.g. Kubernetes, MindGraph)
        - Technology: tools, frameworks, languages, protocols (e.g. Docker, Redis, Java)
        - Database: database systems and storage engines (e.g. PostgreSQL, Neo4j, MongoDB, Redis)
        - Cloud: cloud providers and cloud services (e.g. AWS, GCP, Azure)
        - Concept: abstract ideas, methods, patterns (e.g. containerization, caching)

        Relationship extraction rules:
        - Extract ALL meaningful relationships between entities, not just the most obvious ones.
        - Every node should have at least one edge if possible.
        - Use concise relation verbs: "uses", "built-on", "integrates", "manages", "stores", "runs-on", "extends", "replaces", "depends-on"

        You MUST respond with ONLY a JSON object in exactly this format, no markdown, no extra text:
        {"nodes":[{"name":"entity name","type":"entity type","description":"brief description"}],"edges":[{"sourceName":"source","targetName":"target","relation":"relationship"}]}
        """)
    GraphData extract(@UserMessage String text);
}
