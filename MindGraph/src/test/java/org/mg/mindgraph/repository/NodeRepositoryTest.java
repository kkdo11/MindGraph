package org.mg.mindgraph.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mg.mindgraph.entity.Edge;
import org.mg.mindgraph.entity.Node;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jRepositoriesAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NodeRepository 통합 테스트.
 * H2 In-Memory DB 사용, Neo4j 자동 설정 제외.
 */
@DataJpaTest(excludeAutoConfiguration = {
        Neo4jAutoConfiguration.class,
        Neo4jDataAutoConfiguration.class,
        Neo4jRepositoriesAutoConfiguration.class
})
class NodeRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private EdgeRepository edgeRepository; // findTopNodesByDegree 테스트용

    private Node persistNode(String name, String type) {
        Node node = Node.builder().name(name).type(type).description(null).build();
        return entityManager.persistAndFlush(node);
    }

    @Test
    @DisplayName("findByNameAndType: 저장 후 name+type으로 정확히 조회")
    void findByNameAndType_정확히_조회() {
        // given
        persistNode("Docker", "Technology");

        // when
        Optional<Node> result = nodeRepository.findByNameAndType("Docker", "Technology");

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Docker");
        assertThat(result.get().getType()).isEqualTo("Technology");
    }

    @Test
    @DisplayName("findByNameAndType: 없는 name → Optional.empty()")
    void findByNameAndType_없으면_empty() {
        // given: 저장 없음

        // when
        Optional<Node> result = nodeRepository.findByNameAndType("없는노드", "Technology");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByNameIn: 여러 이름 배치 조회")
    void findByNameIn_배치조회() {
        // given
        persistNode("Redis", "Technology");
        persistNode("FastAPI", "Technology");
        persistNode("Docker", "Technology");

        // when
        List<Node> result = nodeRepository.findByNameIn(List.of("Redis", "FastAPI", "Docker"));

        // then
        assertThat(result).hasSize(3);
        assertThat(result).extracting(Node::getName)
                .containsExactlyInAnyOrder("Redis", "FastAPI", "Docker");
    }

    @Test
    @DisplayName("findByNameIn: 일부만 존재하면 존재하는 것만 반환")
    void findByNameIn_일부만_존재() {
        // given
        persistNode("Redis", "Technology");

        // when
        List<Node> result = nodeRepository.findByNameIn(List.of("Redis", "없는노드"));

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Redis");
    }

    // ==================== @PrePersist 타임스탬프 테스트 ====================

    @Test
    @DisplayName("@PrePersist: 노드 저장 시 createdAt/updatedAt 자동 설정")
    void prePersist_저장시_타임스탬프_자동설정() {
        // given: 타임스탬프 미설정 상태
        Node node = Node.builder().name("Docker").type("Technology").build();
        assertThat(node.getCreatedAt()).isNull();
        assertThat(node.getUpdatedAt()).isNull();

        // when: 저장 시 @PrePersist 콜백 실행
        Node saved = nodeRepository.saveAndFlush(node);

        // then: 자동으로 채워짐
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    // ==================== 신규 쿼리 테스트 ====================

    @Test
    @DisplayName("findRecentNodes: Pageable limit 적용 — 3개 저장 후 limit=2이면 2개 반환")
    void findRecentNodes_limit_적용() {
        // given
        persistNode("A", "Technology");
        persistNode("B", "Technology");
        persistNode("C", "Technology");

        // when
        List<Node> result = nodeRepository.findRecentNodes(PageRequest.of(0, 2));

        // then: limit 준수
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("findTopNodesByDegree: 엣지 수(degree) 기준 상위 노드 반환")
    void findTopNodesByDegree_연결수_많은_노드_우선() {
        // given
        Node hub = persistNode("Hub", "Technology");       // 엣지 2개
        Node leaf = persistNode("Leaf", "Concept");        // 엣지 1개
        Node isolated = persistNode("Isolated", "Concept"); // 엣지 0개

        entityManager.persistAndFlush(
                Edge.builder().source(hub).target(leaf).relation("USES").build());
        entityManager.persistAndFlush(
                Edge.builder().source(hub).target(isolated).relation("CONNECTS").build());

        entityManager.flush();
        entityManager.clear();

        // when: 상위 1개만 요청
        List<Node> result = nodeRepository.findTopNodesByDegree(1);

        // then: degree=2인 Hub가 1위
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Hub");
    }
}
