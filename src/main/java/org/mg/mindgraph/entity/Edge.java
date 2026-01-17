package org.mg.mindgraph.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "edges", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"source_id", "target_id", "relation"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Edge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 성능을 위해 LAZY 로딩 사용 (필요할 때만 조회)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private Node source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_id", nullable = false)
    private Node target;

    @Column(nullable = false)
    private String relation;

    @Builder
    public Edge(Node source, Node target, String relation) {
        this.source = source;
        this.target = target;
        this.relation = relation;
    }
}