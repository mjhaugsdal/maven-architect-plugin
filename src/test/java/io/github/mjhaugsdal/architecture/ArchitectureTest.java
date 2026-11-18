package io.github.mjhaugsdal.architecture;

import io.github.mjhaugsdal.architecture.analyzer.CycleAnalysis;
import io.github.mjhaugsdal.architecture.analyzer.DependencyCycleAnalyzer;
import io.github.mjhaugsdal.architecture.analyzer.DependencyPathFinder;
import io.github.mjhaugsdal.architecture.generator.BuildLevelGenerator;
import io.github.mjhaugsdal.architecture.generator.CircularDependencyDiagramGenerator;
import io.github.mjhaugsdal.architecture.generator.CircularDependencyReportGenerator;
import io.github.mjhaugsdal.architecture.generator.JsonGenerator;
import io.github.mjhaugsdal.architecture.generator.MermaidGenerator;
import io.github.mjhaugsdal.architecture.model.ArchitectureGraph;
import io.github.mjhaugsdal.architecture.model.ArchitectureModule;
import io.github.mjhaugsdal.architecture.model.ArtifactIdentity;
import io.github.mjhaugsdal.architecture.model.ExternalDependency;
import io.github.mjhaugsdal.architecture.model.ExternalArtifact;
import io.github.mjhaugsdal.architecture.model.ArtifactDependency;
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
        target.addAttachedArtifact(new DefaultArtifact("example", "target", "1.1", "compile", "jar", "tests",
                new DefaultArtifactHandler("jar")));
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
    void completeScannerKeepsReactorAndExternalDependencyMetadata() {
        MavenProject source = project("source", "1", "jar", temp);
        MavenProject target = project("target", "2", "jar", temp.resolve("target"));
        Dependency reactorDependency = dependency("target", "2", "provided");
        reactorDependency.setType("test-jar");
        reactorDependency.setClassifier("tests");
        source.getModel().addDependency(reactorDependency);
        Dependency externalDependency = dependency("external", "3", "system");
        externalDependency.setGroupId("outside");
        externalDependency.setType("zip");
        externalDependency.setClassifier("sources");
        source.getModel().addDependency(externalDependency);

        ArchitectureGraph graph = scanner().scanComplete(List.of(target, source));

        assertEquals(2, graph.artifactDependencies().size());
        assertTrue(graph.artifactDependencies().contains(new ArtifactDependency(
                new ModuleId("example", "source", "1"), new ModuleId("example", "target", "2"),
                "example", "target", "2", "test-jar", "tests", "provided")));
        assertTrue(graph.artifactDependencies().contains(new ArtifactDependency(
                new ModuleId("example", "source", "1"), null,
                "outside", "external", "3", "zip", "sources", "system")));
        assertTrue(graph.dependencies().isEmpty());
    }

    @Test
    void jsonExportIsStableCompleteAndOmitsMachinePaths() {
        ModuleId parent = new ModuleId("example", "parent", "1");
        ModuleId source = new ModuleId("example", "source", "1");
        ModuleId target = new ModuleId("example", "target", "2");
        ArchitectureGraph first = new ArchitectureGraph();
        first.addModule(new ArchitectureModule(source, "jar", "Source \"module\"", temp.toString(), parent));
        first.addModule(new ArchitectureModule(parent, "pom", null, temp.toString(), null));
        first.addModule(new ArchitectureModule(target, "jar", "Target", temp.toString(), parent));
        first.addRelationship(new ModuleRelationship(parent, source, ModuleRelationship.Type.AGGREGATION));
        first.addRelationship(new ModuleRelationship(parent, source, ModuleRelationship.Type.INHERITANCE));
        first.addArtifactDependency(new ArtifactDependency(source, target, "example", "target", "2",
                "test-jar", "tests", "provided"));
        first.addArtifactDependency(new ArtifactDependency(source, null, "outside", "library", "3",
                "zip", null, "runtime"));

        ArchitectureGraph shuffled = new ArchitectureGraph();
        shuffled.addModule(new ArchitectureModule(target, "jar", "Target", "/different/path", parent));
        shuffled.addModule(new ArchitectureModule(parent, "pom", null, "/different/path", null));
        shuffled.addModule(new ArchitectureModule(source, "jar", "Source \"module\"", "/different/path", parent));
        shuffled.addArtifactDependency(new ArtifactDependency(source, null, "outside", "library", "3",
                "zip", null, "runtime"));
        shuffled.addArtifactDependency(new ArtifactDependency(source, target, "example", "target", "2",
                "test-jar", "tests", "provided"));
        shuffled.addRelationship(new ModuleRelationship(parent, source, ModuleRelationship.Type.INHERITANCE));
        shuffled.addRelationship(new ModuleRelationship(parent, source, ModuleRelationship.Type.AGGREGATION));

        String json = new JsonGenerator().generate(first);
        assertEquals(json, new JsonGenerator().generate(shuffled));
        assertTrue(json.startsWith("{\n  \"schemaVersion\": 1,\n"));
        assertTrue(json.contains("\"kind\": \"external\", \"groupId\": \"outside\""));
        assertTrue(json.contains("\"kind\": \"inheritance\""));
        assertTrue(json.contains("\"kind\": \"aggregation\""));
        assertTrue(json.contains("\"kind\": \"dependency\", \"scope\": \"provided\", \"version\": \"2\", "
                + "\"type\": \"test-jar\", \"classifier\": \"tests\""));
        assertTrue(json.contains("\"kind\": \"dependency\", \"scope\": \"runtime\", \"version\": \"3\", "
                + "\"type\": \"zip\", \"classifier\": null"));
        assertTrue(json.contains("\"name\": \"Source \\\"module\\\"\""));
        assertFalse(json.contains(temp.toString()));
        assertFalse(json.contains("/different/path"));
        assertFalse(json.contains("\"directory\""));
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

    @Test
    void cycleReportFindsIndependentCyclesAndSelfLoopsButOmitsAcyclicModules() {
        ArchitectureGraph graph = new ArchitectureGraph();
        ModuleId a = module(graph, "a");
        ModuleId b = module(graph, "b");
        ModuleId c = module(graph, "c");
        ModuleId d = module(graph, "d");
        ModuleId e = module(graph, "e");
        ModuleId f = module(graph, "f");
        ModuleId g = module(graph, "g");
        ModuleId h = module(graph, "h");
        graph.addDependency(new ModuleDependency(a, b, "compile"));
        graph.addDependency(new ModuleDependency(b, a, "compile"));
        graph.addDependency(new ModuleDependency(c, d, "compile"));
        graph.addDependency(new ModuleDependency(d, e, "compile"));
        graph.addDependency(new ModuleDependency(e, c, "compile"));
        graph.addDependency(new ModuleDependency(f, f, "compile"));
        graph.addDependency(new ModuleDependency(g, h, "compile"));

        DependencyCycleAnalyzer analyzer = new DependencyCycleAnalyzer();
        CycleAnalysis analysis = analyzer.analyze(graph);
        String report = new CircularDependencyReportGenerator().generate(analysis);
        String diagram = new CircularDependencyDiagramGenerator().generate(graph, analysis);

        assertEquals(3, analysis.cycles().size());
        assertEquals(List.of(a, b), analysis.cycles().get(0).modules());
        assertEquals(List.of(c, d, e), analysis.cycles().get(1).modules());
        assertEquals(List.of(f), analysis.cycles().get(2).modules());
        assertEquals(List.of(f, f), analysis.cycles().get(2).path());
        for (var cycle : analysis.cycles()) {
            assertEquals(cycle.path().get(0), cycle.path().get(cycle.path().size() - 1));
            assertTrue(cycle.path().containsAll(cycle.modules()));
            for (int index = 1; index < cycle.path().size(); index++) {
                ModuleId from = cycle.path().get(index - 1);
                ModuleId to = cycle.path().get(index);
                assertTrue(graph.dependencies().stream()
                        .anyMatch(dependency -> dependency.from().equals(from) && dependency.to().equals(to)));
            }
        }
        assertTrue(report.contains("g:a:1, g:b:1"));
        assertTrue(report.contains("g:c:1, g:d:1, g:e:1"));
        assertTrue(report.contains("g:f:1"));
        assertFalse(report.contains("g:g:1"));
        assertFalse(report.contains("g:h:1"));
        assertTrue(diagram.contains("[\"a\"]"));
        assertTrue(diagram.contains("[\"e\"]"));
        assertFalse(diagram.contains("[\"g\"]"));
        assertFalse(diagram.contains("[\"h\"]"));
        assertTrue(diagram.contains("n5 -.-> n5"));
        assertEquals(diagram, new CircularDependencyDiagramGenerator().generate(graph, analysis));
        assertEquals(analysis.diagnosticMessage(),
                assertThrows(IllegalArgumentException.class, () -> new BuildLevelGenerator().generate(graph))
                        .getMessage());
        assertEquals(List.of(a, b), new DependencyPathFinder().findPath(graph, a, b).orElseThrow());
    }

    @Test
    void cycleReportHandlesAnAcyclicGraph() {
        ArchitectureGraph graph = new ArchitectureGraph();
        ModuleId a = module(graph, "a");
        ModuleId b = module(graph, "b");
        graph.addDependency(new ModuleDependency(a, b, "compile"));
        CycleAnalysis analysis = new DependencyCycleAnalyzer().analyze(graph);
        assertFalse(analysis.hasCycles());
        assertEquals("No circular dependencies found.\n",
                new CircularDependencyReportGenerator().generate(analysis));
        assertEquals("flowchart TD\n", new CircularDependencyDiagramGenerator().generate(graph, analysis));
        assertTrue(new DependencyPathFinder().findPath(graph, b, a).isEmpty());
    }

    private ModuleId module(ArchitectureGraph graph, String artifact) {
        ModuleId id = new ModuleId("g", artifact, "1");
        graph.addModule(new ArchitectureModule(id, "jar", null, "", null));
        return id;
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
    void shuffledGraphInputProducesStableDiagramAndBuildLevels() {
        ModuleId alpha = new ModuleId("g", "alpha", "1");
        ModuleId beta = new ModuleId("g", "beta", "1");
        ModuleId gamma = new ModuleId("g", "gamma", "1");
        ArchitectureGraph first = new ArchitectureGraph();
        first.addModule(new ArchitectureModule(alpha, "jar", null, "", null));
        first.addModule(new ArchitectureModule(beta, "jar", null, "", null));
        first.addModule(new ArchitectureModule(gamma, "jar", null, "", null));
        first.addDependency(new ModuleDependency(beta, alpha, "compile"));
        first.addDependency(new ModuleDependency(gamma, alpha, "runtime"));

        ArchitectureGraph shuffled = new ArchitectureGraph();
        shuffled.addModule(new ArchitectureModule(gamma, "jar", null, "", null));
        shuffled.addModule(new ArchitectureModule(beta, "jar", null, "", null));
        shuffled.addModule(new ArchitectureModule(alpha, "jar", null, "", null));
        shuffled.addDependency(new ModuleDependency(gamma, alpha, "runtime"));
        shuffled.addDependency(new ModuleDependency(beta, alpha, "compile"));

        MermaidGenerator generator = new MermaidGenerator();
        assertEquals(generator.generate(first, true, true), generator.generate(shuffled, true, true));
        BuildLevelGenerator levels = new BuildLevelGenerator();
        assertEquals(levels.generate(first), levels.generate(shuffled));
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
        assertTrue(new DiagramWriter().write(output, content));
        var modified = Files.getLastModifiedTime(output);
        assertFalse(new DiagramWriter().write(output, content));
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
