package org.mg.mindgraph.neo4j.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("KnowledgeNode")
@Getter
@NoArgsConstructor
public class KnowledgeNode {

    @Id
    @GeneratedValue
    private Long id;

    private String name;
    private String type;

    public KnowledgeNode(String name, String type) {
        this.name = name;
        this.type = type;
    }
}
