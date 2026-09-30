File output = new File(basedir, "target/architecture/architecture.mmd")
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

File repository = new File(basedir.parentFile.parentFile, "it-repository")
def mavenExecutable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "mvn.cmd" : "mvn"
def command = [mavenExecutable, "-B", "-o", "-Dmaven.repo.local=${repository.absolutePath}",
               "-Darchitecture.includeTestDependencies=true",
               "-Darchitecture.outputFile=target/architecture/with-tests.mmd",
               "io.github.mjhaugsdal:maven-architecture-plugin:0.0.1-SNAPSHOT:diagram"]
def process = new ProcessBuilder(command.collect { it.toString() }).directory(basedir).redirectErrorStream(true).start()
String log = process.inputStream.getText("UTF-8")
assert process.waitFor() == 0 : log
File testOutput = new File(basedir, "target/architecture/with-tests.mmd")
assert testOutput.isFile()
assert testOutput.getText("UTF-8").contains("${nodes['application']} -.-> ${nodes['common']}")
