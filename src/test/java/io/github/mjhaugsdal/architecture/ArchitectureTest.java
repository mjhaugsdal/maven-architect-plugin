package io.github.mjhaugsdal.architecture;

import io.github.mjhaugsdal.architecture.generator.BuildLevelGenerator;
import io.github.mjhaugsdal.architecture.generator.MermaidGenerator;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ExternalArtifact;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
import io.github.mjhaugsdal.architecture.scanner.ProjectScanner;
import io.github.mjhaugsdal.architecture.writer.DiagramWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureTest {
    @TempDir Path temp;

    @Test
    void identitiesAndDuplicateRelationships() {
        ModuleId first = new ModuleId("one", "same", "1");
        ModuleId second = new ModuleId("two", "same", "1");
        ModuleId third = new ModuleId("one", "same", "2");
        assertNotEquals(first, second);
        assertNotEquals(first, third);
        ArchitectureGraph graph = new ArchitectureGraph();
        graph.addModule(new ArchitectureModule(first, "pom", null, "", null));
        graph.addModule(new ArchitectureModule(second, "jar", null, "", first));
        graph.addModule(new ArchitectureModule(third, "jar", null, "", first));
        ModuleRelationship relationship = new ModuleRelationship(first, second, ModuleRelationship.Type.AGGREGATION);
        graph.addRelationship(relationship);
        graph.addRelationship(relationship);
        assertEquals(1, graph.relationships().size());
        assertThrows(IllegalArgumentException.class,
                () -> graph.addModule(new ArchitectureModule(first, "jar", null, "", null)));
    }

    @Test
    void scannerDistinguishesAggregationInheritanceAndDependencyScopes() {
        MavenProject root = project("parent", "1", "pom", temp);
        root.getModel().addModule("child");
        MavenProject child = project("child", "1", "jar", temp.resolve("child"));
        child.setParent(root);
        child.getModel().addDependency(dependency("target", "1", "runtime"));
        child.getModel().addDependency(dependency("test-only", "1", "test"));
        child.getModel().addDependency(dependency("external", "1", "compile"));
        MavenProject target = project("target", "1", "jar", temp.resolve("target"));
        target.getModel().addDependency(dependency("child", "1", "compile"));
        MavenProject testOnly = project("test-only", "1", "jar", temp.resolve("test-only"));

        ArchitectureGraph defaultGraph = scanner().scan(List.of(root, child, target, testOnly), false);
        ModuleId rootId = new ModuleId("example", "parent", "1");
        ModuleId childId = new ModuleId("example", "child", "1");
        assertTrue(defaultGraph.relationships().contains(
                new ModuleRelationship(rootId, childId, ModuleRelationship.Type.AGGREGATION)));
        assertTrue(defaultGraph.relationships().contains(
                new ModuleRelationship(rootId, childId, ModuleRelationship.Type.INHERITANCE)));
        assertEquals(2, defaultGraph.dependencies().size());
        assertEquals(3, scanner().scan(List.of(root, child, target, testOnly), true).dependencies().size());
        assertTrue(defaultGraph.modules().stream().anyMatch(module -> module.id().equals(testOnlyId())));
    }

    @Test
    void scannerDoesNotInferInheritanceFromAggregationOrAggregationFromInheritance() {
        MavenProject aggregator = project("aggregator", "1", "pom", temp);
        aggregator.getModel().addModule("child");
        MavenProject parent = project("parent", "1", "pom", temp.resolve("parent"));
        MavenProject child = project("child", "1", "jar", temp.resolve("child"));
        child.setParent(parent);
        ArchitectureGraph graph = scanner().scan(List.of(aggregator, parent, child), false);
        ModuleId childId = new ModuleId("example", "child", "1");
        assertEquals(2, graph.relationships().size());
        assertTrue(graph.relationships().contains(new ModuleRelationship(
                new ModuleId("example", "aggregator", "1"), childId, ModuleRelationship.Type.AGGREGATION)));
        assertTrue(graph.relationships().contains(new ModuleRelationship(
                new ModuleId("example", "parent", "1"), childId, ModuleRelationship.Type.INHERITANCE)));
    }

    @Test
    void scannerExcludesManagedOnlyAndNonmatchingDependencyTypes() {
        MavenProject source = project("source", "1", "jar", temp);
        MavenProject target = project("target", "1", "jar", temp.resolve("target"));
        DependencyManagement management = new DependencyManagement();
        management.addDependency(dependency("target", "1", "compile"));
        source.getModel().setDependencyManagement(management);
        Dependency provided = dependency("target", "1", "provided");
        source.getModel().addDependency(provided);
        Dependency classified = dependency("target", "1", "compile");
        classified.setClassifier("tests");
        source.getModel().addDependency(classified);
        Dependency wrongVersion = dependency("target", "2", "compile");
        source.getModel().addDependency(wrongVersion);
        assertTrue(scanner().scan(List.of(source, target), true).dependencies().isEmpty());
    }

    @Test
    void scannerMatchesDependencyArtifactExtensionToCustomPackaging() {
        MavenProject source = project("source", "1", "jar", temp);
        MavenProject target = project("target", "1", "bundle", temp.resolve("target"));
        DefaultArtifactHandler targetHandler = new DefaultArtifactHandler("bundle");
        targetHandler.setExtension("jar");
        target.getArtifact().setArtifactHandler(targetHandler);
        source.getModel().addDependency(dependency("target", "1", "compile"));

        assertEquals(1, scanner().scan(List.of(source, target), false).dependencies().size());
    }

    @Test
    void scannerMatchesVersionRangesOnlyWhenOneReactorVersionQualifies() {
        MavenProject source = project("source", "1", "jar", temp);
        source.getModel().addDependency(dependency("target", "[1.0,2.0)", "compile"));
        MavenProject matchingTarget = project("target", "1.1", "jar", temp.resolve("target-1.1"));
        MavenProject nonmatchingTarget = project("target", "2.1", "jar", temp.resolve("target-2.1"));
        ModuleId sourceId = new ModuleId("example", "source", "1");
        ModuleId matchingTargetId = new ModuleId("example", "target", "1.1");
        assertTrue(scanner().scan(List.of(source, matchingTarget, nonmatchingTarget), true)
                .dependencies().contains(new ModuleDependency(sourceId, matchingTargetId, "compile")));

        source.getModel().getDependencies().clear();
        source.getModel().addDependency(dependency("target", "[1.0,3.0)", "compile"));
        MavenProject secondMatchingTarget = project("target", "1.2", "jar", temp.resolve("target-1.2"));
        assertTrue(scanner().scan(List.of(source, matchingTarget, secondMatchingTarget), true)
                .dependencies().isEmpty());
    }

    @Test
    void scannerIncludesDeduplicatedExternalArtifactsAndMatchesFullCoordinates() {
        MavenProject source = project("source", "1", "jar", temp);
        source.getModel().addDependency(dependency("target", "1", "compile"));
        source.getModel().addDependency(dependency("target", "2", "compile"));
        Dependency classifiedTarget = dependency("target", "1", "compile");
        classifiedTarget.setClassifier("tests");
        source.getModel().addDependency(classifiedTarget);
        Dependency testJarTarget = dependency("target", "1", "compile");
        testJarTarget.setType("test-jar");
        testJarTarget.setClassifier("tests");
        source.getModel().addDependency(testJarTarget);
        source.getModel().addDependency(dependency("shared", "1", "compile"));
        source.getModel().addDependency(dependency("shared", "1", "compile"));
        source.getModel().addDependency(dependency("shared", "2", "runtime"));
        Dependency classifiedShared = dependency("shared", "1", "runtime");
        classifiedShared.setClassifier("tests");
        source.getModel().addDependency(classifiedShared);
        source.getModel().addDependency(dependency("test-only", "1", "test"));
        source.getModel().addDependency(dependency("provided-only", "1", "provided"));
        MavenProject target = project("target", "1", "jar", temp.resolve("target"));

        ArchitectureGraph defaultGraph = scanner().scan(List.of(source, target), false);
        assertTrue(defaultGraph.externalArtifacts().isEmpty());

        ArchitectureGraph graph = scanner().scan(List.of(source, target), false, true);
        ModuleId sourceId = new ModuleId("example", "source", "1");
        ModuleId targetId = new ModuleId("example", "target", "1");
        assertEquals(1, graph.dependencies().size());
        assertTrue(graph.dependencies().contains(new ModuleDependency(sourceId, targetId, "compile")));
        assertEquals(6, graph.externalArtifacts().size());
        assertEquals(6, graph.externalDependencies().size());
        assertTrue(graph.externalArtifacts().contains(
                new ExternalArtifact("example", "target", "1", "jar", "tests")));
        assertTrue(graph.externalArtifacts().contains(
                new ExternalArtifact("example", "target", "1", "test-jar", "tests")));
        assertTrue(graph.externalArtifacts().contains(
                new ExternalArtifact("example", "shared", "1", "jar", "")));
        assertTrue(graph.externalArtifacts().contains(
                new ExternalArtifact("example", "shared", "2", "jar", "")));
        assertTrue(graph.externalArtifacts().contains(
                new ExternalArtifact("example", "shared", "1", "jar", "tests")));
        assertFalse(graph.externalArtifacts().stream().anyMatch(artifact ->
                artifact.artifactId().equals("test-only") || artifact.artifactId().equals("provided-only")));

        String diagram = new MermaidGenerator().generate(graph, true, true);
        assertTrue(diagram.contains("example:shared:1"));
        assertTrue(diagram.contains("example:shared:2"));
        assertTrue(diagram.contains("example:shared:1 (classifier=tests)"));
        assertTrue(diagram.contains("example:target:2"));
        assertFalse(new MermaidGenerator().generate(graph, true, false).contains("example:shared"));

        ArchitectureGraph withTests = scanner().scan(List.of(source, target), true, true);
        assertTrue(withTests.externalArtifacts().stream()
                .anyMatch(artifact -> artifact.artifactId().equals("test-only")));
        assertFalse(withTests.externalArtifacts().stream()
                .anyMatch(artifact -> artifact.artifactId().equals("provided-only")));
    }

    @Test
    void scannerTreatsDependenciesOnProjectsOmittedFromSelectedReactorAsExternal() {
        MavenProject source = project("source", "1", "jar", temp);
        source.getModel().addDependency(dependency("omitted", "1", "compile"));

        ArchitectureGraph graph = scanner().scan(List.of(source), false, true);

        assertTrue(graph.externalArtifacts().contains(
                new ExternalArtifact("example", "omitted", "1", "jar", "")));
    }

    @Test
    void buildLevelsGroupParallelModulesAndIgnoreAggregatorsParentsAndScopes() {
        MavenProject root = project("root", "1", "pom", temp);
        root.getModel().addModule("core");
        root.getModel().addModule("left");
        root.getModel().addModule("right");
        root.getModel().addModule("app");
        MavenProject core = project("core", "1", "jar", temp.resolve("core"));
        MavenProject left = project("left", "1", "jar", temp.resolve("left"));
        MavenProject right = project("right", "1", "jar", temp.resolve("right"));
        MavenProject app = project("app", "1", "jar", temp.resolve("app"));
        for (MavenProject child : List.of(core, left, right, app)) {
            child.setParent(root);
        }
        left.getModel().addDependency(dependency("core", "1", "provided"));
        Dependency testJar = dependency("core", "1", "test");
        testJar.setType("test-jar");
        testJar.setClassifier("tests");
        right.getModel().addDependency(testJar);
        app.getModel().addDependency(dependency("left", "1", "compile"));
        app.getModel().addDependency(dependency("core", "1", "compile"));

        String diagram = new BuildLevelGenerator().generate(scanner().scanBuildOrder(List.of(root, core, left, right, app)));
        assertEquals("""
                flowchart TD
                    subgraph L0["Level 0"]
                        direction LR
                        n0["core"]
                    end
                    subgraph L1["Level 1"]
                        direction LR
                        n1["left"]
                        n2["right"]
                    end
                    subgraph L2["Level 2"]
                        direction LR
                        n3["app"]
                    end
                    L0 --> L1
                    L1 --> L2
                """, diagram);
    }

    @Test
    void buildLevelsRejectCyclesAndKeepIsolatedModules() {
        ArchitectureGraph graph = new ArchitectureGraph();
        ModuleId a = new ModuleId("g", "a", "1");
        ModuleId b = new ModuleId("g", "b", "1");
        graph.addModule(new ArchitectureModule(a, "jar", null, "", null));
        graph.addModule(new ArchitectureModule(b, "jar", null, "", null));
        assertEquals("flowchart TD\n    subgraph L0[\"Level 0\"]\n        direction LR\n        n0[\"a\"]\n"
                + "        n1[\"b\"]\n    end\n", new BuildLevelGenerator().generate(graph));
        graph.addDependency(new ModuleDependency(a, b, "compile"));
        graph.addDependency(new ModuleDependency(b, a, "test"));
        assertThrows(IllegalArgumentException.class, () -> new BuildLevelGenerator().generate(graph));
        assertEquals("flowchart TD\n", new BuildLevelGenerator().generate(new ArchitectureGraph()));
    }

    private ModuleId testOnlyId() {
        return new ModuleId("example", "test-only", "1");
    }

    @Test
    void generatorEscapesLabelsAndProducesStableUniqueNodesForCycles() {
        ArchitectureGraph graph = new ArchitectureGraph();
        ModuleId a = new ModuleId("a", "module", "1");
        ModuleId b = new ModuleId("b", "module", "1");
        graph.addModule(new ArchitectureModule(b, "jar", "Name \"<x>\"", "", null));
        graph.addModule(new ArchitectureModule(a, "jar", "Name \"<x>\"", "", null));
        graph.addDependency(new ModuleDependency(a, b, "compile"));
        graph.addDependency(new ModuleDependency(b, a, "runtime"));
        graph.addDependency(new ModuleDependency(a, b, "runtime"));
        String diagram = new MermaidGenerator().generate(graph, true, true);
        assertEquals(diagram, new MermaidGenerator().generate(graph, true, true));
        assertTrue(diagram.contains("n0[\"Name #34;#60;x#62;#34; (a:module:1)\"]"));
        assertTrue(diagram.contains("n1[\"Name #34;#60;x#62;#34; (b:module:1)\"]"));
        assertEquals(1, diagram.split("n0 -.-> n1", -1).length - 1);
        assertTrue(diagram.contains("n1 -.-> n0"));
    }

    @Test
    void emptyAndIsolatedGraphsAndWriter() throws Exception {
        MermaidGenerator generator = new MermaidGenerator();
        assertEquals("flowchart TD\n", generator.generate(new ArchitectureGraph(), true, true));
        ArchitectureGraph graph = new ArchitectureGraph();
        graph.addModule(new ArchitectureModule(new ModuleId("g", "a", "1"), "jar", null, "", null));
        String content = generator.generate(graph, true, true);
        assertTrue(content.contains("n0[\"a\"]"));
        Path output = temp.resolve("nested/diagram.mmd");
        new DiagramWriter().write(output, content);
        var modified = Files.getLastModifiedTime(output);
        new DiagramWriter().write(output, content);
        assertEquals(modified, Files.getLastModifiedTime(output));
        assertThrows(IllegalArgumentException.class, () -> new DiagramWriter().write(output, ""));
        assertThrows(IllegalArgumentException.class, () -> scanner().scan(List.of(), false));
    }

    private ProjectScanner scanner() {
        return new ProjectScanner(new ArtifactHandlerManager() {
            @Override
            public ArtifactHandler getArtifactHandler(String type) {
                return new DefaultArtifactHandler(type);
            }

            @Override
            public void addHandlers(java.util.Map<String, ArtifactHandler> handlers) {
            }
        });
    }

    private MavenProject project(String artifact, String version, String packaging, Path directory) {
        Model model = new Model();
        model.setGroupId("example");
        model.setArtifactId(artifact);
        model.setVersion(version);
        model.setPackaging(packaging);
        MavenProject project = new MavenProject(model);
        project.setArtifact(new DefaultArtifact("example", artifact, version, "compile", packaging, null,
                new DefaultArtifactHandler(packaging)));
        project.setFile(directory.resolve("pom.xml").toFile());
        return project;
    }

    private Dependency dependency(String artifact, String version, String scope) {
        Dependency dependency = new Dependency();
        dependency.setGroupId("example");
        dependency.setArtifactId(artifact);
        dependency.setVersion(version);
        dependency.setScope(scope);
        return dependency;
    }
}
