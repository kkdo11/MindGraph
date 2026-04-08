package org.mg.mindgraph.dto;

/**
 * 중심성 분석 결과 DTO.
 * Neo4j Degree Centrality(순수 Cypher) 또는 PostgreSQL degree 기반 fallback으로 생성됩니다.
 *
 * <p>degree 의미:
 * <ul>
 *   <li>Neo4j 정상: MATCH (n)-[r]-() count(r) 실측값</li>
 *   <li>PostgreSQL fallback: 0 (순서만 유효, 정확한 값 미제공)</li>
 * </ul>
 */
public record NodeInsight(String name, String type, long degree) {}
