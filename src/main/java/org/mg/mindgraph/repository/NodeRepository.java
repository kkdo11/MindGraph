package org.mg.mindgraph.repository;

import org.mg.mindgraph.entity.Node;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NodeRepository extends JpaRepository<Node, Long> {
    Optional<Node> findByNameAndType(String name, String type);
    boolean existsByName(String name);
    Optional<Node> findByName(String name);
    List<Node> findByNameContainingIgnoreCase(String name);

    // [추가] 여러 이름을 한 번에 조회하여 N+1 문제 방지 (SQL 최적화)
    List<Node> findByNameIn(Collection<String> names);
}