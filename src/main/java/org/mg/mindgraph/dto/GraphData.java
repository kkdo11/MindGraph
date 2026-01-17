package org.mg.mindgraph.dto;

import java.util.List;

public record GraphData(List<NodeDTO> nodes, List<EdgeDTO> edges) {
    public record NodeDTO(String name, String type, String description) {}
    public record EdgeDTO(String sourceName, String targetName, String relation) {}
}
