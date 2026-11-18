package io.github.mjhaugsdal.architecture;

import io.github.mjhaugsdal.architecture.analyzer.DependencyAnalysis;
import io.github.mjhaugsdal.architecture.analyzer.DependencyAnalyzer;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DependencyAnalyzerTest {
    @Test
    void reportsDiamondPathsAndDeduplicatedDirectedReachability() {
        ModuleId source = id("source");
        ModuleId left = id("left");
        ModuleId right = id("right");
        ModuleId target = id("target");
        ArchitectureGraph graph = graph(source, left, right, target);
        graph.addDependency(dependency(source, left, "compile"));
        graph.addDependency(dependency(source, right, "compile"));
        graph.addDependency(dependency(left, target, "runtime"));
        graph.addDependency(dependency(right, target, "compile"));

        DependencyAnalysis analysis = new DependencyAnalyzer().analyze(graph);

        assertEquals(4, analysis.directDependencies().size());
        assertEquals(List.of(left, right, target), List.copyOf(analysis.reachableDependencies().get(source)));
        assertEquals(List.of(List.of(source, left, target), List.of(source, right, target)),
                analysis.paths(source, target));
        assertTrue(analysis.reachableDependencies().get(target).isEmpty());
        assertTrue(analysis.paths(target, source).isEmpty());
    }

    @Test
    void appliesDefaultAndOptionalTestScopePolicy() {
        ModuleId source = id("source");
        ModuleId compile = id("compile");
        ModuleId runtime = id("runtime");
        ModuleId test = id("test");
        ModuleId provided = id("provided");
        ArchitectureGraph graph = graph(source, compile, runtime, test, provided);
        graph.addDependency(dependency(source, compile, "compile"));
        graph.addDependency(dependency(source, runtime, "runtime"));
        graph.addDependency(dependency(source, test, "test"));
        graph.addDependency(dependency(source, provided, "provided"));

        DependencyAnalyzer analyzer = new DependencyAnalyzer();
        DependencyAnalysis defaults = analyzer.analyze(graph);
        DependencyAnalysis withTests = analyzer.analyze(graph, true);

        assertEquals(List.of(compile, runtime), List.copyOf(defaults.reachableDependencies().get(source)));
        assertEquals(List.of(compile, runtime, test), List.copyOf(withTests.reachableDependencies().get(source)));
        assertEquals(2, defaults.directDependencies().size());
        assertEquals(3, withTests.directDependencies().size());
    }

    @Test
    void terminatesOnCyclesAndCapsPathsDeterministically() {
        List<ModuleId> modules = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            modules.add(id("module-" + i));
        }
        ArchitectureGraph graph = graph(modules.toArray(ModuleId[]::new));
        for (ModuleId from : modules) {
            for (ModuleId to : modules) {
                if (!from.equals(to)) {
                    graph.addDependency(dependency(from, to, "compile"));
                }
            }
        }

        DependencyAnalyzer analyzer = new DependencyAnalyzer();
        DependencyAnalysis first = analyzer.analyze(graph);
        DependencyAnalysis second = analyzer.analyze(graph);

        assertEquals(modules.size() - 1, first.reachableDependencies().get(modules.get(0)).size());
        assertEquals(DependencyAnalyzer.MAX_PATHS_PER_PAIR, first.paths(modules.get(0), modules.get(1)).size());
        assertEquals(first.paths(modules.get(0), modules.get(1)), second.paths(modules.get(0), modules.get(1)));
        assertTrue(first.paths(modules.get(0), modules.get(1)).stream()
                .allMatch(path -> path.get(0).equals(modules.get(0))
                        && path.get(path.size() - 1).equals(modules.get(1))
                        && path.size() == path.stream().distinct().count()));
    }

    private ArchitectureGraph graph(ModuleId... modules) {
        ArchitectureGraph graph = new ArchitectureGraph();
        for (ModuleId module : modules) {
            graph.addModule(new ArchitectureModule(module, "jar", null, "", null));
        }
        return graph;
    }

    private ModuleDependency dependency(ModuleId from, ModuleId to, String scope) {
        return new ModuleDependency(from, to, scope);
    }

    private ModuleId id(String artifact) {
        return new ModuleId("g", artifact, "1");
    }
}
