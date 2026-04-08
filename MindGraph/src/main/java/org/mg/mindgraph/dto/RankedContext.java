package org.mg.mindgraph.dto;

/**
 * 컨텍스트 조각과 그 출처/점수를 함께 관리하는 DTO.
 * assembleContext()에서 score 기반 정렬·중복 제거·토큰 예산 관리에 사용됩니다.
 *
 * <p>고정 점수 기준:
 * <ul>
 *   <li>VECTOR: SearchResult.score 그대로 (0~1, pgvector 코사인 유사도)</li>
 *   <li>PG_GRAPH: 0.7 (직접 관계 노드)</li>
 *   <li>NEO4J_1HOP: 0.6 (1-hop 연결)</li>
 *   <li>NEO4J_2HOP: 0.4 (2-hop 확장)</li>
 * </ul>
 */
public record RankedContext(String content, double score, Source source) {

    public enum Source {
        VECTOR,      // pgvector 코사인 유사도 검색
        PG_GRAPH,    // PostgreSQL 그래프 직접 관계
        NEO4J_1HOP,  // Neo4j 1-hop 연결
        NEO4J_2HOP,  // Neo4j 2-hop 확장
    }
}
