package io.github.mjhaugsdal.architecture;

import io.github.mjhaugsdal.architecture.generator.BuildLevelGenerator;
import io.github.mjhaugsdal.architecture.generator.MermaidGenerator;
import io.github.mjhaugsdal.architecture.generator.ModuleGrouping;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ModuleGroupingTest {
    @TempDir Path root;

    private final ModuleId parent = new ModuleId("g", "parent", "1");
    private final ModuleId a = new ModuleId("g", "a", "1");
    private final ModuleId b = new ModuleId("other", "a", "2");
    private final ModuleId outside = new ModuleId("other", "outside", "1");

    @Test
    void strategiesUseMetadataWithoutChangingGraphIdentity() {
        ArchitectureGraph graph = graph(false);
        assertEquals(Map.of(), groups(graph, "none", Map.of()));
        assertEquals(Map.of(a, "g:parent:1", b, "g:parent:1", outside, "external:parent:1"),
                groups(graph, "parent", Map.of()));
        assertEquals(Map.of(parent, "parent", a, "a", b, "a", outside, "outside"),
                groups(graph, "artifactId", Map.of()));
        assertEquals(Map.of(parent, "g", a, "g", b, "other", outside, "other"),
                groups(graph, "groupId", Map.of()));
        assertEquals(Map.of(a, "services", b, "libraries"), groups(graph, "directory", Map.of()));
        assertEquals(Map.of(a, "Backend", b, "Backend"),
                groups(graph, "custom", Map.of("Backend", List.of("g:a", "other:a:2"))));
        assertEquals(4, graph.modules().size());
        assertEquals(1, graph.dependencies().size());
        assertEquals(2, graph.relationships().size());
        assertThrows(IllegalArgumentException.class, () -> groups(graph, "unknown", Map.of()));
    }

    @Test
    void customMappingsRejectOverlapsAndInvalidConfiguration() {
        ArchitectureGraph graph = graph(false);
        assertThrows(IllegalArgumentException.class, () -> groups(graph, "custom",
                Map.of("One", List.of("g:a"), "Two", List.of("g:a"))));
        assertThrows(IllegalArgumentException.class, () -> groups(new ArchitectureGraph(), "custom",
                Map.of("One", List.of("g:a"), "Two", List.of("g:a:1"))));
        assertThrows(IllegalArgumentException.class, () -> groups(graph, "custom",
                Map.of("One", List.of("a"))));
        assertThrows(IllegalArgumentException.class, () -> groups(graph, "custom",
                Map.of(" ", List.of("g:a"))));
        assertEquals(Map.of(a, "One"), groups(graph, "custom",
                Map.of("One", List.of("g:a", "g:a:1", "g:a", "missing:module"))));
        assertTrue(groups(graph, "custom", null).isEmpty());
    }

    @Test
    void groupedOutputIsDeterministicEscapedAndPreservesNodesAndAllEdges() {
        ArchitectureGraph graph = graph(false);
        MermaidGenerator generator = new MermaidGenerator();
        String original = generator.generate(graph, true, true);
        Map<String, List<String>> custom = new LinkedHashMap<>();
        custom.put("Z \"<&", List.of("g:a"));
        custom.put("A", List.of("other:a:2"));
        String grouped = generator.generate(graph, true, true, groups(graph, "custom", custom));
        assertTrue(grouped.contains("subgraph G0[\"A\"]"));
        assertTrue(grouped.contains("subgraph G1[\"Z #34;#60;#38;\"]"));
        assertTrue(grouped.contains("    n1[\"parent\"]\n"));
        assertTrue(grouped.contains("    n3[\"outside\"]\n"));
        assertEquals(lines(original), lines(grouped));
        assertEquals(grouped, generator.generate(graph(true), true, true, groups(graph(true), "custom", custom)));
        for (String strategy : List.of("parent", "artifactId", "groupId", "directory", "none")) {
            String diagram = generator.generate(graph, true, true, groups(graph, strategy, Map.of()));
            assertEquals(lines(original), lines(diagram));
            assertEquals(diagram, generator.generate(graph(true), true, true,
                    groups(graph(true), strategy, Map.of())));
        }
        String noEdges = generator.generate(graph, false, false, groups(graph, "custom", custom));
        assertFalse(noEdges.contains("-->"));
        assertFalse(noEdges.contains("-.->"));
    }

    @Test
    void buildLevelGroupingKeepsLevelsAndNodeIdentity() {
        ArchitectureGraph graph = graph(false);
        BuildLevelGenerator generator = new BuildLevelGenerator();
        String original = generator.generate(graph);
        Map<ModuleId, String> groups = groups(graph, "groupId", Map.of());
        String grouped = generator.generate(graph, groups);
        assertTrue(grouped.contains("subgraph L0G0[\"other\"]"));
        assertTrue(grouped.contains("subgraph L1G0[\"g\"]"));
        assertTrue(grouped.contains("    L0 --> L1\n"));
        assertEquals(lines(original), lines(grouped));
        assertEquals(grouped, generator.generate(graph(true), groups));
        assertEquals(original, generator.generate(graph, Map.of()));
        assertEquals("flowchart TD\n", generator.generate(new ArchitectureGraph(), Map.of()));
    }

    private List<String> lines(String diagram) {
        return diagram.lines().map(String::strip).filter(line -> line.startsWith("n") || line.contains(" --> "))
                .sorted().toList();
    }

    private Map<ModuleId, String> groups(ArchitectureGraph graph, String strategy,
            Map<String, List<String>> custom) {
        return new ModuleGrouping().project(graph, strategy, root, custom);
    }

    private ArchitectureGraph graph(boolean reverse) {
        List<ArchitectureModule> modules = new ArrayList<>(List.of(
                new ArchitectureModule(parent, "pom", null, root.toString(), null),
                new ArchitectureModule(a, "jar", null, root.resolve("services/api").toString(), parent),
                new ArchitectureModule(b, "jar", null, root.resolve("libraries/core").toString(), parent),
                new ArchitectureModule(outside, "jar", null, root.resolve("../outside").toString(),
                        new ModuleId("external", "parent", "1"))));
        if (reverse) Collections.reverse(modules);
        ArchitectureGraph graph = new ArchitectureGraph();
        modules.forEach(graph::addModule);
        graph.addDependency(new ModuleDependency(a, b, "compile"));
        graph.addRelationship(new ModuleRelationship(parent, a, ModuleRelationship.Type.AGGREGATION));
        graph.addRelationship(new ModuleRelationship(parent, a, ModuleRelationship.Type.INHERITANCE));
        return graph;
    }
}
