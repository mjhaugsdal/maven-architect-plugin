package io.github.mjhaugsdal.architecture;

import io.github.mjhaugsdal.architecture.analyzer.ArchitectureAnalysis;
import io.github.mjhaugsdal.architecture.analyzer.ArchitectureAnalyzer;
import io.github.mjhaugsdal.architecture.analyzer.ModuleMetrics;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureAnalyzerTest {
    @Test
    void analyzesUniqueDependencyEdgesAndRanksCouplingDeterministically() {
        ArchitectureGraph graph = graph("a", "b", "c", "d", "e");
        ModuleId a = id("a");
        ModuleId b = id("b");
        ModuleId c = id("c");
        ModuleId d = id("d");
        ModuleId e = id("e");
        graph.addDependency(new ModuleDependency(b, a, "compile"));
        graph.addDependency(new ModuleDependency(b, a, "runtime"));
        graph.addDependency(new ModuleDependency(c, a, "test"));
        graph.addDependency(new ModuleDependency(c, b, "compile"));
        graph.addDependency(new ModuleDependency(d, a, "compile"));

        ArchitectureAnalysis result = new ArchitectureAnalyzer().analyze(graph);

        assertEquals(5, result.moduleCount());
        assertEquals(4, result.dependencyEdgeCount());
        assertEquals(new ModuleMetrics(a, 3, 0, 3, 0), result.modules().get(a));
        assertEquals(new ModuleMetrics(b, 1, 1, 2, 1), result.modules().get(b));
        assertEquals(new ModuleMetrics(c, 0, 2, 2, 2), result.modules().get(c));
        assertEquals(new ModuleMetrics(d, 0, 1, 1, 1), result.modules().get(d));
        assertEquals(new ModuleMetrics(e, 0, 0, 0, 0), result.modules().get(e));
        assertEquals(List.of(a, b, c), result.highestCouplingModules(3).stream()
                .map(ModuleMetrics::module).toList());
        assertTrue(result.highestCouplingModules(0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> result.highestCouplingModules(-1));
        assertThrows(UnsupportedOperationException.class, () -> result.modules().clear());
    }

    @Test
    void assignsCycleMembersTheSameFiniteDependencyDepth() {
        ArchitectureGraph graph = graph("a", "b", "c", "isolated");
        ModuleId a = id("a");
        ModuleId b = id("b");
        ModuleId c = id("c");
        graph.addDependency(new ModuleDependency(a, b, "compile"));
        graph.addDependency(new ModuleDependency(b, a, "runtime"));
        graph.addDependency(new ModuleDependency(c, a, "compile"));

        ArchitectureAnalysis result = new ArchitectureAnalyzer().analyze(graph);

        assertEquals(4, result.moduleCount());
        assertEquals(3, result.dependencyEdgeCount());
        assertEquals(0, result.modules().get(a).dependencyDepth());
        assertEquals(0, result.modules().get(b).dependencyDepth());
        assertEquals(1, result.modules().get(c).dependencyDepth());
        assertEquals(0, result.modules().get(id("isolated")).dependencyDepth());
    }

    private ArchitectureGraph graph(String... artifacts) {
        ArchitectureGraph graph = new ArchitectureGraph();
        for (String artifact : artifacts) {
            graph.addModule(new ArchitectureModule(id(artifact), "jar", null, "", null));
        }
        return graph;
    }

    private ModuleId id(String artifact) {
        return new ModuleId("example", artifact, "1");
    }
}
