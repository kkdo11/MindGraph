package org.mg.mindgraph.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "nodes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"name", "type"}) // DB의 unique 제약조건 반영
})
@Getter
@Setter
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

    // 기존 데이터 호환: DEFAULT NOW() 로 기존 rows에 현재 시간 자동 적용
    @Column(columnDefinition = "TIMESTAMP DEFAULT NOW()")
    private LocalDateTime createdAt;

    @Column(columnDefinition = "TIMESTAMP DEFAULT NOW()")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Builder
    public Node(String name, String type, String description) {
        this.name = name;
        this.type = type;
        this.description = description;
    }
}