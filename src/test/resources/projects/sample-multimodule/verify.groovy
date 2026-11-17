File repository = new File(basedir.parentFile.parentFile, "it-repository")
def mavenExecutable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "mvn.cmd" : "mvn"
def runGoal = { List<String> properties ->
    def command = [mavenExecutable, "-B", "-o", "-Dmaven.repo.local=${repository.absolutePath}"] + properties +
            ["io.github.mjhaugsdal:maven-architecture-plugin:${pluginVersion}:diagram"]
    def process = new ProcessBuilder(command.collect { it.toString() }).directory(basedir).redirectErrorStream(true).start()
    String log = process.inputStream.getText("UTF-8")
    assert process.waitFor() == 0 : log
    log
}

def failGoal = { List<String> properties ->
    def command = [mavenExecutable, "-B", "-o", "-Dmaven.repo.local=${repository.absolutePath}"] + properties +
            ["io.github.mjhaugsdal:maven-architecture-plugin:${pluginVersion}:diagram"]
    def process = new ProcessBuilder(command.collect { it.toString() }).directory(basedir).redirectErrorStream(true).start()
    String log = process.inputStream.getText("UTF-8")
    assert process.waitFor() != 0 : log
    log
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
String defaultLog = runGoal([])
assert defaultLog.contains("Mermaid architecture diagram:")
assert !defaultLog.contains("Architecture diagram summary:")
assert !defaultLog.contains("filters:")

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

runGoal(["-Darchitecture.diagramType=full", "-Darchitecture.includeTestDependencies=true",
         "-Darchitecture.outputFile=target/architecture/with-tests.mmd"])
File testOutput = new File(basedir, "target/architecture/with-tests.mmd")
assert testOutput.isFile()
assert testOutput.getText("UTF-8").contains("${nodes['application']} -.-> ${nodes['common']}")

List<String> verboseOptions = ["-Darchitecture.diagramType=full", "-Darchitecture.includeTestDependencies=true",
        "-Darchitecture.verbose=true", "-Darchitecture.outputFile=target/architecture/verbose.mmd"]
String verboseLog = runGoal(verboseOptions)
assert verboseLog.contains("Architecture diagram summary: 6 selected projects, 6 rendered projects, 17 edges")
assert verboseLog.contains("hierarchy=included, dependencies=compile/runtime/test")
assert verboseLog.contains("output: ${new File(basedir, 'target/architecture/verbose.mmd').absolutePath} (written)")
String unchangedLog = runGoal(verboseOptions)
assert unchangedLog.contains("output: ${new File(basedir, 'target/architecture/verbose.mmd').absolutePath} (unchanged)")

String buildLevelsLog = runGoal(["-Darchitecture.diagramType=build-levels", "-Darchitecture.verbose=true",
        "-Darchitecture.outputFile=target/architecture/verbose-build-levels.mmd"])
assert buildLevelsLog.contains("6 selected projects, 5 rendered projects, 3 edges")
assert buildLevelsLog.contains("dependencies=all scopes and types, standalone aggregators=omitted")

String invalidTypeLog = failGoal(["-Darchitecture.diagramType=unknown"])
assert invalidTypeLog.contains("valid values are 'build-levels' and 'full'")
String conflictingOptionsLog = failGoal(["-Darchitecture.includeTestDependencies=true"])
assert conflictingOptionsLog.contains("only apply to diagramType='full'")
String unsupportedOptionLog = failGoal(["-Darchitecture.includeExternalDependencies=true"])
assert unsupportedOptionLog.contains("the valid value is false")
