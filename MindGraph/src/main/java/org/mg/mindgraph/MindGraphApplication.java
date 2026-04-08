package org.mg.mindgraph;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

@SpringBootApplication
@EnableJpaRepositories("org.mg.mindgraph.repository")
@EnableNeo4jRepositories("org.mg.mindgraph.neo4j.repository")
public class MindGraphApplication {

	public static void main(String[] args) {
		SpringApplication.run(MindGraphApplication.class, args);
	}

}
