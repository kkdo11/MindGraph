package org.mg.mindgraph.repository;

import org.mg.mindgraph.entity.Edge;
import org.springframework.data.jpa.repository.JpaRepository;


public interface EdgeRepository extends JpaRepository<Edge, Long> {
    // 엣지 중복 검사용 (Source ID, Target ID, 관계명으로 조회)
    boolean existsBySourceIdAndTargetIdAndRelation(Long sourceId, Long targetId, String relation);
}