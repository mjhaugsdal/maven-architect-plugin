package io.github.mjhaugsdal.architecture;

import io.github.mjhaugsdal.architecture.generator.BuildLevelGenerator;
import io.github.mjhaugsdal.architecture.generator.MermaidGenerator;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ArtifactIdentity;
import io.github.mjhaugsdal.architecture.model.ExternalDependency;
import io.github.mjhaugsdal.architecture.model.ModuleDependency;
import io.github.mjhaugsdal.architecture.model.ModuleId;
import io.github.mjhaugsdal.architecture.model.ModuleRelationship;
import io.github.mjhaugsdal.architecture.model.ResolvedDependencyVersion;
import io.github.mjhaugsdal.architecture.scanner.MavenDependencyResolver;
import io.github.mjhaugsdal.architecture.scanner.ProjectScanner;
import io.github.mjhaugsdal.architecture.writer.DiagramWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.aether.graph.DefaultDependencyNode;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.ArtifactTypeRegistry;
import org.eclipse.aether.artifact.DefaultArtifactType;

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
        classified.setType("war");
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
        ArchitectureGraph graph = scanner().scan(List.of(source, matchingTarget, nonmatchingTarget), true);
        assertTrue(graph.dependencies().stream().anyMatch(edge -> edge.from().equals(sourceId)
                        && edge.to().equals(matchingTargetId)
                        && edge.requestedVersion().equals("[1.0,2.0)")));
        assertTrue(new MermaidGenerator().generate(graph, false, true)
                .contains("requested #91;1.0,2.0)"));

        source.getModel().getDependencies().clear();
        source.getModel().addDependency(dependency("target", "[1.0,3.0)", "compile"));
        MavenProject secondMatchingTarget = project("target", "1.2", "jar", temp.resolve("target-1.2"));
        assertTrue(scanner().scan(List.of(source, matchingTarget, secondMatchingTarget), true)
                .dependencies().isEmpty());
    }

    @Test
    void scannerPreservesManagedVersionsAndArtifactIdentity() throws Exception {
        MavenProject source = project("source", "1", "jar", temp);
        MavenProject target = project("target", "1.1", "jar", temp.resolve("target"));
        Dependency managed = dependency("target", "1.1", "compile");
        source.getModel().addDependency(managed);
        Dependency classified = dependency("target", "1.1", "compile");
        classified.setType("test-jar");
        source.getModel().addDependency(classified);

        Model original = new Model();
        Dependency unmanagedVersion = dependency("target", null, "compile");
        original.addDependency(unmanagedVersion);
        source.setOriginalModel(original);

        Dependency firstExternal = dependency("shared", "1.0", "compile");
        Dependency secondExternal = dependency("shared", "2.0", "compile");
        Dependency classifierExternal = dependency("shared", "2.0", "compile");
        classifierExternal.setClassifier("tests");
        Dependency typeExternal = dependency("shared", "2.0", "compile");
        typeExternal.setType("war");
        source.getModel().addDependency(firstExternal);
        source.getModel().addDependency(secondExternal);
        source.getModel().addDependency(classifierExternal);
        source.getModel().addDependency(typeExternal);

        ArtifactIdentity managedIdentity = new ArtifactIdentity("example", "target", "jar", "jar", "");
        ModuleId sourceId = new ModuleId("example", "source", "1");
        Map<ModuleId, List<ResolvedDependencyVersion>> resolution = Map.of(sourceId, List.of(
                new ResolvedDependencyVersion(managedIdentity, "compile", "1.1", "1.1", false, false, true)));
        ArchitectureGraph graph = new ProjectScanner(scannerArtifactHandlerManager(), testArtifactTypeRegistry())
                .scan(List.of(source, target), false, true, resolution);

        assertEquals(2, graph.dependencies().size());
        assertTrue(graph.dependencies().stream().anyMatch(edge -> edge.managed()
                && edge.requestedVersion().equals("1.1") && edge.artifact().type().equals("jar")));
        assertTrue(graph.dependencies().stream().anyMatch(edge -> edge.artifact().type().equals("test-jar")
                && edge.artifact().classifier().equals("tests")));
        assertEquals(4, graph.externalDependencies().size());
        assertTrue(new MermaidGenerator().generate(graph, false, true)
                .contains("requested 1.1; managed"));
    }

    @Test
    void resolverCapturesMediatedVersionAndGeneratorDoesNotAssumeMissingResolutionConverged() {
        MavenProject source = project("source", "1", "jar", temp);
        source.getModel().addDependency(dependency("branch", "1.0", "compile"));
        DefaultDependencyNode root = new DefaultDependencyNode(
                new org.eclipse.aether.artifact.DefaultArtifact("example", "source", "", "pom", "1"));
        DefaultDependencyNode branch = new DefaultDependencyNode(new org.eclipse.aether.graph.Dependency(
                new org.eclipse.aether.artifact.DefaultArtifact("example", "branch", "", "jar", "1.0"), "compile"));
        DefaultDependencyNode losing = new DefaultDependencyNode(new org.eclipse.aether.graph.Dependency(
                new org.eclipse.aether.artifact.DefaultArtifact("example", "shared", "", "jar", "2.0"), "compile"));
        DefaultDependencyNode winner = new DefaultDependencyNode(new org.eclipse.aether.graph.Dependency(
                new org.eclipse.aether.artifact.DefaultArtifact("example", "shared", "", "jar", "1.0"), "compile"));
        losing.setData("conflict.winner", winner);
        branch.setChildren(List.of(losing));
        root.setChildren(List.of(branch));
        DependencyResolutionResult result = new DependencyResolutionResult() {
            @Override
            public DependencyNode getDependencyGraph() {
                return root;
            }

            @Override
            public List<org.eclipse.aether.graph.Dependency> getDependencies() {
                return List.of();
            }

            @Override
            public List<org.eclipse.aether.graph.Dependency> getResolvedDependencies() {
                return List.of();
            }

            @Override
            public List<org.eclipse.aether.graph.Dependency> getUnresolvedDependencies() {
                return List.of();
            }

            @Override
            public List<Exception> getCollectionErrors() {
                return List.of();
            }

            @Override
            public List<Exception> getResolutionErrors(org.eclipse.aether.graph.Dependency dependency) {
                return List.of();
            }
        };
        ProjectDependenciesResolver fakeResolver = request -> result;
        var versions = new MavenDependencyResolver(fakeResolver, scannerArtifactHandlerManager())
                .resolve(List.of(source), new DefaultRepositorySystemSession(), ignored -> { });
        ResolvedDependencyVersion mediated = versions.get(new ModuleId("example", "source", "1")).stream()
                .filter(version -> !version.direct())
                .findFirst().orElseThrow();
        assertEquals("2.0", mediated.requestedVersion());
        assertEquals("1.0", mediated.selectedVersion());
        assertTrue(mediated.conflict());

        ArchitectureGraph graph = new ArchitectureGraph();
        ModuleId sourceId = new ModuleId("example", "source", "1");
        graph.addModule(new ArchitectureModule(sourceId, "jar", "source", "", null));
        graph.addExternalDependency(new ExternalDependency(sourceId,
                new ArtifactIdentity("example", "shared", "jar", "jar", ""),
                "compile", "2.0", "1.0", false, true));
        String unavailable = new MermaidGenerator().generate(graph, false, true, true);
        assertTrue(unavailable.contains("requested 2.0; selected 1.0; CONFLICT"));

        graph.addExternalDependency(new ExternalDependency(sourceId,
                new ArtifactIdentity("example", "unresolved", "jar", "jar", ""),
                "compile", "1.0", null, false, false));
        String withMissingData = new MermaidGenerator().generate(graph, false, true, true);
        assertTrue(withMissingData.contains("requested 1.0; selected unavailable"));
        assertFalse(withMissingData.contains("requested 1.0; selected 1.0"));
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
        assertEquals(1, diagram.split("n0 -.->\\|requested 1\\| n1", -1).length - 1);
        assertTrue(diagram.contains("n1 -.->|requested 1| n0"));
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
        return new ProjectScanner(scannerArtifactHandlerManager());
    }

    private ArtifactTypeRegistry testArtifactTypeRegistry() {
        return type -> "test-jar".equals(type)
                ? new DefaultArtifactType("test-jar", "jar", "tests", Map.of())
                : null;
    }

    private ArtifactHandlerManager scannerArtifactHandlerManager() {
        return new ArtifactHandlerManager() {
            @Override
            public ArtifactHandler getArtifactHandler(String type) {
                DefaultArtifactHandler handler = new DefaultArtifactHandler(type);
                if ("test-jar".equals(type)) {
                    handler.setExtension("jar");
                }
                return handler;
            }

            @Override
            public void addHandlers(java.util.Map<String, ArtifactHandler> handlers) {
            }
        };
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
