File repository = new File(basedir.parentFile.parentFile, "it-repository")
def mavenExecutable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "mvn.cmd" : "mvn"
def runMaven = { List<String> arguments ->
    def command = [mavenExecutable, "-B", "-o", "-Dmaven.repo.local=${repository.absolutePath}"] +
            arguments.collect { it.toString() }
    def process = new ProcessBuilder(command.collect { it.toString() }).directory(basedir).redirectErrorStream(true).start()
    String output = process.inputStream.getText("UTF-8")
    [exitCode: process.waitFor(), output: output]
}
def runDiagram = { List<String> properties ->
    def result = runMaven(properties + ["io.github.mjhaugsdal:maven-architecture-plugin:${pluginVersion}:diagram"])
    assert result.exitCode == 0 : result.output
    result
}
def findNode = { String label, String diagram ->
    def match = (diagram =~ /(?m)^\s*(n\d+)\["${java.util.regex.Pattern.quote(label)}"\]$/)
    assert match.find() : "No node for ${label} in:\n${diagram}"
    match.group(1)
}
def containsEdge = { String diagram, String from, String to ->
    diagram.contains("${from} -.-> ${to}")
}

runDiagram(["-Darchitecture.diagramType=full", "-Darchitecture.outputFile=target/architecture/default.mmd"])
File defaultOutput = new File(basedir, "target/architecture/default.mmd")
assert defaultOutput.isFile()
String defaultDiagram = defaultOutput.getText("UTF-8")
assert defaultDiagram.contains('["inner"]')
assert defaultDiagram.contains('["target"]')
assert !defaultDiagram.contains('["profiled"]')
String target = findNode("target", defaultDiagram)
assert containsEdge(defaultDiagram, findNode("managed-consumer", defaultDiagram), target)
assert containsEdge(defaultDiagram, findNode("optional-consumer", defaultDiagram), target)
assert !containsEdge(defaultDiagram, findNode("classified-consumer", defaultDiagram), target)
assert !containsEdge(defaultDiagram, findNode("unmatched-consumer", defaultDiagram), target)

runDiagram(["-Pwith-profile", "-Darchitecture.diagramType=full",
           "-Darchitecture.outputFile=target/architecture/profile.mmd"])
String profileDiagram = new File(basedir, "target/architecture/profile.mmd").getText("UTF-8")
assert profileDiagram.contains('["profiled"]')
assert containsEdge(profileDiagram, findNode("profiled", profileDiagram), findNode("target", profileDiagram))

runDiagram(["-pl", "modules/inner/managed-consumer", "-Darchitecture.diagramType=full",
           "-Darchitecture.outputFile=target/architecture/partial.mmd"])
String partialDiagram = new File(basedir, "modules/inner/managed-consumer/target/architecture/partial.mmd")
        .getText("UTF-8")
assert partialDiagram.contains('["managed-consumer"]')
assert !partialDiagram.contains('["target"]')
assert !partialDiagram.contains(" -.-> ")

runDiagram(["-Darchitecture.outputFile=target/architecture/build-order.mmd"])
String buildOrder = new File(basedir, "target/architecture/build-order.mmd").getText("UTF-8")
def levels = [:]
int currentLevel = -1
buildOrder.eachLine { line ->
    def levelMatch = (line =~ /subgraph L(\d+)/)
    if (levelMatch.find()) {
        currentLevel = levelMatch.group(1) as int
    }
    def nodeMatch = (line =~ /^\s*n\d+\["([^"]+)"\]$/)
    if (nodeMatch.find()) {
        levels[nodeMatch.group(1)] = currentLevel
    }
}
assert levels["target"] < levels["classified-consumer"]
assert levels["target"] < levels["unmatched-consumer"]

def cycle = runMaven(["-Pcycle", "validate"])
assert cycle.exitCode != 0 : "Maven unexpectedly accepted the cyclic reactor"
assert cycle.output.toLowerCase(java.util.Locale.ROOT).contains("cyclic")
