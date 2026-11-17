# Maven Architecture Plugin Roadmap

The plugin currently produces Mermaid build-level and full reactor diagrams
from Maven's effective project models. The next step is to make those
diagrams more useful for real projects, then use the same graph to check
architecture rules in CI without requiring anyone to generate a diagram.

Release targets are provisional, not commitments. GitHub issues track
implementation details, acceptance criteria, and prerequisites.

## 0.2 - Better visualization

Make dependency views useful beyond the selected reactor while preserving
existing defaults and keeping visualization filters out of build ordering.

- [Render external dependencies][external] with Maven coordinates.
- [Visualize and filter Maven scopes][scope].
- [Group reactor modules][grouping] by parent, coordinates, directory, or custom mappings.
- [Report the dependency cycle][cycles] when build levels cannot be generated.
- [Add a dependency-only diagram][dependency-view].

## 0.3 - Architecture analysis

- [Analyze transitive dependencies and alternate paths][transitive].
- [Display versions and report convergence, conflicts, and mediation][convergence].
- [Measure coupling and dependency depth][metrics].
- [Report circular dependencies and optionally diagram them][cycle-report].
- [Export the complete graph as versioned JSON][json].
- [Expand reactor integration coverage][reactor-tests] for profiles, dependency management, BOMs, classifiers, optional dependencies, cycles, and deterministic output.

## 0.4 - Architecture enforcement

- [Configure XML or YAML rules with wildcard allowlists and denylists][rules].
- [Enforce dependency direction between layers][layers].
- [Validate in Maven and CI with fail and warning modes][ci].
- [Check fitness thresholds and compare metrics against a baseline][fitness].

## 0.5 - Extended ecosystem

- Generate [PlantUML][plantuml] and [Graphviz DOT][dot] from the same graph.
- Generate an [interactive offline HTML report][html] and [embeddable SVG][svg].
- [Verify Maven 4 compatibility][maven4] alongside Maven 3.
- [Benchmark large reactors and avoid unchanged graph processing][performance].
- [Automate documentation and Maven Central releases][releases].

## Post-0.5 backlog

- Add focused views for [Maven hierarchy][hierarchy], [shared third-party dependencies][external-view], and [module ownership][ownership].
- [Extend build levels with estimated execution order and downstream blockers][build-execution].
- [Detect potentially unused dependencies using source or bytecode evidence][unused].
- [Filter modules and show configurable module details][filters-details].
- [Generate multiple outputs using named configurations and presets][profiles].
- [Improve command-line validation and add verbose summaries][cli].

## Design guardrails

- Keep `model/` independent of Maven APIs and output formats. Graph nodes,
  edges, scopes, versions, and metadata should have the same meaning in
  Mermaid, PlantUML, JSON, HTML, and validation.
- Keep Maven extraction in `scanner/` and graph algorithms in `analyzer/`.
  Use `generator/` for output formatting, `writer/` for persistence, and
  `mojo/` for Maven goals and configuration. Keep rule evaluation separate
  from rendering.
- Distinguish effective declarations from resolved artifacts and actual
  source or bytecode usage. Don't infer convergence or unused dependencies
  when the necessary evidence is unavailable.

For the highest-value work across releases, prioritize external
dependencies and module grouping first, then JSON export and metrics as
analysis foundations, followed by graph-based architecture rules for CI.

[external]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/3
[scope]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/4
[grouping]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/5
[cycles]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/6
[dependency-view]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/7
[transitive]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/8
[convergence]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/9
[metrics]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/10
[cycle-report]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/11
[json]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/12
[reactor-tests]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/13
[rules]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/14
[layers]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/15
[ci]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/16
[fitness]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/17
[plantuml]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/18
[dot]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/19
[html]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/20
[svg]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/21
[maven4]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/22
[performance]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/23
[releases]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/24
[hierarchy]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/25
[external-view]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/26
[ownership]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/27
[build-execution]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/28
[unused]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/29
[filters-details]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/30
[profiles]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/31
[cli]: https://github.com/mjhaugsdal/maven-architect-plugin/issues/32
