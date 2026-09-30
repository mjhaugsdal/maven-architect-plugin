package io.github.mjhaugsdal.architecture.model;

/** Metadata for one project in the selected reactor. */
public record ArchitectureModule(ModuleId id, String packaging, String name, String directory, ModuleId parent) {}
