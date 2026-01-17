package org.mg.mindgraph.repository;

import org.mg.mindgraph.entity.Node;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface NodeRepository extends JpaRepository<Node, Long> {
    // 중복 방지를 위해 이름과 타입으로 노드를 찾는 메서드
    Optional<Node> findByNameAndType(String name, String type);
}