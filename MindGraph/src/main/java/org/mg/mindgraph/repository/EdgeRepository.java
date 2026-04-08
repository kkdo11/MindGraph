// EdgeRepository.java
package org.mg.mindgraph.repository;

import org.mg.mindgraph.entity.Edge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface EdgeRepository extends JpaRepository<Edge, Long> {

    boolean existsBySourceIdAndTargetIdAndRelation(Long sourceId, Long targetId, String relation);

    void deleteBySourceIdOrTargetId(Long sourceId, Long targetId);

    // UI 전체 그래프 로드용 — JOIN FETCH로 N+1 방지
    @Query("SELECT e FROM Edge e JOIN FETCH e.source JOIN FETCH e.target")
    List<Edge> findAllWithNodes();

    // [최적화 핵심] JOIN FETCH를 사용하여 N+1 문제를 원천 차단합니다.
    @Query("SELECT e FROM Edge e " +
            "JOIN FETCH e.source s " +
            "JOIN FETCH e.target t " +
            "WHERE s.name IN :names OR t.name IN :names")
    List<Edge> findEdgesByNodeNamesIn(@Param("names") List<String> names);

    @Query("SELECT e FROM Edge e " +
            "JOIN FETCH e.source s " +
            "JOIN FETCH e.target t " +
            "WHERE s.name = :nodeName OR t.name = :nodeName")
    List<Edge> findEdgesByNodeName(@Param("nodeName") String nodeName);
}