package io.github.mjhaugsdal.architecture.analyzer;

import io.github.mjhaugsdal.architecture.model.ModuleId;

/** Dependency metrics for one module. */
public record ModuleMetrics(ModuleId module, int fanIn, int fanOut, int couplingScore, int dependencyDepth) {}
