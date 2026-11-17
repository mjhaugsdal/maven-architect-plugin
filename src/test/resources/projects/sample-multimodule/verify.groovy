File repository = new File(basedir.parentFile.parentFile, "it-repository")
def mavenExecutable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "mvn.cmd" : "mvn"
def runGoal = { List<String> properties ->
    def command = [mavenExecutable, "-B", "-o", "-Dmaven.repo.local=${repository.absolutePath}"] + properties +
            ["io.github.mjhaugsdal:maven-architecture-plugin:${pluginVersion}:diagram"]
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

runGoal(["-Darchitecture.diagramType=full", "-Darchitecture.includeTestDependencies=true",
         "-Darchitecture.outputFile=target/architecture/with-tests.mmd"])
File testOutput = new File(basedir, "target/architecture/with-tests.mmd")
assert testOutput.isFile()
assert testOutput.getText("UTF-8").contains("${nodes['application']} -.-> ${nodes['common']}")

["parent", "artifactId", "groupId", "directory"].each { strategy ->
    runGoal(["-Darchitecture.diagramType=full", "-Darchitecture.groupBy=${strategy}",
             "-Darchitecture.outputFile=target/architecture/${strategy}.mmd"])
    String grouped = new File(basedir, "target/architecture/${strategy}.mmd").getText("UTF-8")
    assert grouped.contains("subgraph G0[")
    diagram.readLines().findAll { it.trim().startsWith("n") }.each { line ->
        assert grouped.readLines().any { it.trim() == line.trim() }
    }
}

runGoal(["-Darchitecture.groupBy=groupId", "-Darchitecture.outputFile=target/architecture/grouped-levels.mmd"])
assert new File(basedir, "target/architecture/grouped-levels.mmd").getText("UTF-8")
        .contains('subgraph L0G0["example"]')

File pom = new File(basedir, "pom.xml")
pom.setText(pom.getText("UTF-8").replace("</project>", """
    <build>
        <plugins>
            <plugin>
                <groupId>io.github.mjhaugsdal</groupId>
                <artifactId>maven-architecture-plugin</artifactId>
                <version>${pluginVersion}</version>
                <configuration>
                    <customGroups>
                        <Backend>
                            <module>example:domain</module>
                            <module>example:application:1.0</module>
                        </Backend>
                        <Adapters>
                            <module>example:api</module>
                            <module>example:infrastructure</module>
                        </Adapters>
                    </customGroups>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
"""), "UTF-8")
runGoal(["-Darchitecture.diagramType=full", "-Darchitecture.groupBy=custom",
         "-Darchitecture.outputFile=target/architecture/custom.mmd"])
String custom = new File(basedir, "target/architecture/custom.mmd").getText("UTF-8")
assert custom.contains('subgraph G0["Adapters"]')
assert custom.contains('subgraph G1["Backend"]')
diagram.readLines().findAll { it.trim().startsWith("n") }.each { line ->
    assert custom.readLines().any { it.trim() == line.trim() }
}
return true
