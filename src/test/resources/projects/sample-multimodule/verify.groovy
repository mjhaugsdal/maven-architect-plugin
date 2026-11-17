File repository = new File(basedir.parentFile.parentFile, "it-repository")
def mavenExecutable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "mvn.cmd" : "mvn"
def runGoal = { List<String> properties, String goal = "diagram" ->
    def command = [mavenExecutable, "-B", "-o", "-Dmaven.repo.local=${repository.absolutePath}"] + properties +
            ["io.github.mjhaugsdal:maven-architecture-plugin:${pluginVersion}:${goal}"]
    def process = new ProcessBuilder(command.collect { it.toString() }).directory(basedir).redirectErrorStream(true).start()
    String log = process.inputStream.getText("UTF-8")
    assert process.waitFor() == 0 : log
}

File levelsOutput = new File(basedir, "target/architecture/architecture.mmd")
assert levelsOutput.isFile()
assert levelsOutput.getText("UTF-8") == """flowchart TD
    subgraph L0["Level 0"]
        direction LR
        n0["common"]
    end
    subgraph L1["Level 1"]
        direction LR
        n1["domain"]
    end
    subgraph L2["Level 2"]
        direction LR
        n2["api"]
        n3["infrastructure"]
    end
    subgraph L3["Level 3"]
        direction LR
        n4["application"]
    end
    L0 --> L1
    L1 --> L2
    L2 --> L3
"""

runGoal(["-Darchitecture.diagramType=full", "-Darchitecture.outputFile=target/architecture/full.mmd"])
File output = new File(basedir, "target/architecture/full.mmd")
assert output.isFile()
String diagram = output.getText("UTF-8")
assert diagram.startsWith("flowchart TD\n")
def nodes = [:]
diagram.eachMatch(/(?m)^\s*(n\d+)\["([^"]+)"\]$/) { match, id, label -> nodes[label] = id }
assert nodes.keySet() == ["sample-multimodule", "common", "domain", "infrastructure", "api", "application"] as Set
["common", "domain", "infrastructure", "api", "application"].each { child ->
    assert diagram.contains("${nodes['sample-multimodule']} --> ${nodes[child]}")
    assert diagram.contains("${nodes['sample-multimodule']} -->|inherits| ${nodes[child]}")
}
["domain:common", "infrastructure:domain", "infrastructure:common", "api:domain",
 "application:api", "application:infrastructure"].each { pair ->
    def (from, to) = pair.split(":")
    assert diagram.contains("${nodes[from]} -.-> ${nodes[to]}")
}
assert !diagram.contains("${nodes['application']} -.-> ${nodes['common']}")
assert !diagram.contains("external")

runGoal(["-Darchitecture.jsonOutputFile=target/architecture/complete.json"], "json")
File jsonOutput = new File(basedir, "target/architecture/complete.json")
assert jsonOutput.isFile()
def graph = new groovy.json.JsonSlurper().parse(jsonOutput)
assert graph.schemaVersion == 1
assert graph.nodes.count { it.kind == "module" } == 6
assert graph.nodes.any { it.kind == "external" && it.groupId == "org.unrelated" && it.artifactId == "external" }
assert graph.nodes.every { !it.containsKey("directory") }
def moduleIds = graph.nodes.findAll { it.kind == "module" }.collectEntries {
    [(it.artifactId): it.id]
}
assert graph.edges.any { it.kind == "aggregation" && it.from == moduleIds["sample-multimodule"] }
assert graph.edges.any { it.kind == "inheritance" && it.to == moduleIds["application"] }
assert graph.edges.any {
    it.kind == "dependency" && it.from == moduleIds["application"] && it.to == moduleIds["common"] &&
            it.scope == "test" && it.version == "1.0" && it.type == "test-jar" && it.classifier == "tests"
}
assert graph.edges.any { it.kind == "dependency" && it.scope == "runtime" }

runGoal(["-Darchitecture.diagramType=full", "-Darchitecture.includeTestDependencies=true",
         "-Darchitecture.outputFile=target/architecture/with-tests.mmd"])
File testOutput = new File(basedir, "target/architecture/with-tests.mmd")
assert testOutput.isFile()
assert testOutput.getText("UTF-8").contains("${nodes['application']} -.-> ${nodes['common']}")
