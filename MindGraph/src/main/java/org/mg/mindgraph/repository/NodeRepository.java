package org.mg.mindgraph.repository;

import org.mg.mindgraph.entity.Node;
import org.springframework.data.domain.Pageable;
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
    // [P0-3] name 기준 단일 조회 — 동일 이름 노드가 여러 개 있어도 첫 번째 반환 (NonUniqueResultException 방지)
    Optional<Node> findFirstByName(String name);
    List<Node> findByNameContainingIgnoreCase(String name);

    // [추가] 여러 이름을 한 번에 조회하여 N+1 문제 방지 (SQL 최적화)
    List<Node> findByNameIn(Collection<String> names);

    // [C-3] 최근 추가된 노드 N개 (createdAt 내림차순) — buildFullGraphSummary fallback용
    @Query("SELECT n FROM Node n ORDER BY n.createdAt DESC")
    List<Node> findRecentNodes(Pageable pageable);

    // [C-3] 연결 엣지 수(degree) 기준 허브 노드 N개 — OOM 방지를 위한 findAll() 대체
    // 설정값에서 limit을 받으므로 SQL 인젝션 위험 없음
    @Query(value = "SELECT * FROM nodes n " +
            "ORDER BY (SELECT COUNT(*) FROM edges e WHERE e.source_id = n.id OR e.target_id = n.id) DESC " +
            "LIMIT :limit", nativeQuery = true)
    List<Node> findTopNodesByDegree(@Param("limit") int limit);

    // [D-1] 이름 임베딩 유사도 기반 노드 검색 — 언어 불일치(한/영) 해소
    // CAST()로 named parameter 충돌 방지 (:param::type 형식은 Spring이 오파싱함)
    @Query(value = """
            SELECT * FROM nodes
            WHERE name_embedding IS NOT NULL
              AND 1 - (name_embedding <=> CAST(:embedding AS vector)) >= :threshold
            ORDER BY name_embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<Node> findSimilarByNameEmbedding(
            @Param("embedding") String embedding,
            @Param("threshold") double threshold,
            @Param("limit") int limit);

    // [F-1c] 이름 임베딩 유사도 + score를 함께 반환 — RRF 동적 스코어링용
    // 결과: [node_id(Long), node_name(String), similarity(Double)]
    @Query(value = """
            SELECT id, name, 1 - (name_embedding <=> CAST(:embedding AS vector)) AS similarity
            FROM nodes
            WHERE name_embedding IS NOT NULL
              AND 1 - (name_embedding <=> CAST(:embedding AS vector)) >= :threshold
            ORDER BY name_embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findSimilarWithScore(
            @Param("embedding") String embedding,
            @Param("threshold") double threshold,
            @Param("limit") int limit);

    // [D-1] 마이그레이션용 — name_embedding 미생성 노드 조회
    @Query(value = "SELECT * FROM nodes WHERE name_embedding IS NULL", nativeQuery = true)
    List<Node> findNodesWithoutEmbedding();
}