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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EdgeRepository 통합 테스트.
 * H2 In-Memory DB 사용, Neo4j 자동 설정 제외.
 * JOIN FETCH 쿼리로 LazyInitializationException 없이 연관 노드 접근 가능 검증.
 */
@DataJpaTest(excludeAutoConfiguration = {
        Neo4jAutoConfiguration.class,
        Neo4jDataAutoConfiguration.class,
        Neo4jRepositoriesAutoConfiguration.class
})
class EdgeRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private EdgeRepository edgeRepository;

    private Node persistNode(String name, String type) {
        Node node = Node.builder().name(name).type(type).description(null).build();
        return entityManager.persistAndFlush(node);
    }

    private Edge persistEdge(Node source, Node target, String relation) {
        Edge edge = Edge.builder().source(source).target(target).relation(relation).build();
        return entityManager.persistAndFlush(edge);
    }

    @Test
    @DisplayName("existsBySourceIdAndTargetIdAndRelation: 존재 → true")
    void existsBySourceIdAndTargetIdAndRelation_존재() {
        // given
        Node source = persistNode("Docker", "Technology");
        Node target = persistNode("Container", "Concept");
        persistEdge(source, target, "IS_A");

        // when
        boolean result = edgeRepository.existsBySourceIdAndTargetIdAndRelation(
                source.getId(), target.getId(), "IS_A"
        );

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("existsBySourceIdAndTargetIdAndRelation: 없으면 → false")
    void existsBySourceIdAndTargetIdAndRelation_없으면_false() {
        // given
        Node source = persistNode("Redis", "Technology");
        Node target = persistNode("Cache", "Concept");

        // when: 저장하지 않고 조회
        boolean result = edgeRepository.existsBySourceIdAndTargetIdAndRelation(
                source.getId(), target.getId(), "IS_A"
        );

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("findEdgesByNodeNamesIn: JOIN FETCH로 연관 노드 접근 가능 (LazyInitializationException 없음)")
    void findEdgesByNodeNamesIn_JOIN_FETCH_검증() {
        // given
        Node docker = persistNode("Docker", "Technology");
        Node container = persistNode("Container", "Concept");
        Node kubernetes = persistNode("Kubernetes", "Technology");
        persistEdge(docker, container, "IS_A");
        persistEdge(kubernetes, container, "ORCHESTRATES");

        entityManager.clear(); // 영속성 컨텍스트 초기화 → LazyLoading 강제

        // when
        List<Edge> edges = edgeRepository.findEdgesByNodeNamesIn(List.of("Docker", "Kubernetes"));

        // then: JOIN FETCH로 source, target 미리 로딩 → LazyInitializationException 없음
        assertThat(edges).hasSize(2);
        assertThat(edges).extracting(e -> e.getSource().getName())
                .containsExactlyInAnyOrder("Docker", "Kubernetes");
        assertThat(edges).extracting(e -> e.getTarget().getName())
                .containsOnly("Container");
    }

    @Test
    @DisplayName("findEdgesByNodeName: 단일 노드 이름으로 관련 엣지 반환")
    void findEdgesByNodeName_단일노드_엣지_반환() {
        // given
        Node docker = persistNode("Docker2", "Technology");
        Node container = persistNode("Container2", "Concept");
        Node redis = persistNode("Redis2", "Technology");
        persistEdge(docker, container, "IS_A");
        persistEdge(redis, container, "USES");

        entityManager.clear();

        // when: docker 이름으로 조회
        List<Edge> edges = edgeRepository.findEdgesByNodeName("Docker2");

        // then
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).getSource().getName()).isEqualTo("Docker2");
        assertThat(edges.get(0).getRelation()).isEqualTo("IS_A");
    }

    @Test
    @DisplayName("findEdgesByNodeName: 없는 노드 이름 → 빈 리스트")
    void findEdgesByNodeName_없는노드_빈리스트() {
        // given: 저장 없음

        // when
        List<Edge> edges = edgeRepository.findEdgesByNodeName("없는노드");

        // then
        assertThat(edges).isEmpty();
    }

    @Test
    @DisplayName("findEdgesByNodeNamesIn: 빈 이름 목록 → 빈 리스트")
    void findEdgesByNodeNamesIn_빈목록_빈리스트() {
        // given: 엣지 저장 없음

        // when
        List<Edge> edges = edgeRepository.findEdgesByNodeNamesIn(List.of());

        // then
        assertThat(edges).isEmpty();
    }
}
