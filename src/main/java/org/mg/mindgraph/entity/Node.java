package org.mg.mindgraph.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "nodes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"name", "type"}) // DB의 unique 제약조건 반영
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 스펙 준수 (protected 권장)
public class Node {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Builder
    public Node(String name, String type, String description) {
        this.name = name;
        this.type = type;
        this.description = description;
    }
}