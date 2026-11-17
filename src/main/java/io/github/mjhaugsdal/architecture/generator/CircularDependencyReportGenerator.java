package io.github.mjhaugsdal.architecture.generator;

import io.github.mjhaugsdal.architecture.analyzer.CycleAnalysis;

/** Formats cycle analysis results for a Maven build log. */
public final class CircularDependencyReportGenerator {
    public String generate(CycleAnalysis analysis) {
        return analysis.diagnosticMessage() + "\n";
    }
}
