package org.mg.mindgraph.dto;

import org.mg.mindgraph.entity.Node;

/**
 * 노드와 name_embedding 유사도 점수를 함께 전달하는 DTO.
 * findNodesBySimilarity()에서 유사도 기반 동적 스코어링에 사용됩니다.
 */
public record NodeWithScore(Node node, double similarity) {}
