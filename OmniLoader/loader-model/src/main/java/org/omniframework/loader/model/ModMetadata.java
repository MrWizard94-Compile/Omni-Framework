package org.omniframework.loader.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The canonical mod model — the single shape every ecosystem imports INTO (LDR-6,
 * ARCHITECTURE §5).
 *
 * <p>This type is the reason the compat layers can exist. A Fabric {@code fabric.mod.json}, a
 * NeoForge {@code neoforge.mods.toml}, and a native {@code omni.mod.json} are three dialects
 * describing the same concepts; each gets a {@code MetadataImporter} that produces one of
 * these, and every stage downstream — resolution, transformation, entrypoint dispatch — sees
 * only this. Generalising sinytra's metadata-translation lesson: translate once, at the edge,
 * into a model rich enough that nothing downstream needs to ask which ecosystem a mod came from.
 *
 * <p>"Rich enough" is doing real work in that sentence. The model must carry concepts that only
 * <em>some</em> ecosystems have, or translation becomes lossy in ways that surface much later
 * as mysterious behaviour. {@link #provides()} exists for Fabric; {@link #environment()} for
 * Fabric's side-stripping; {@link #accessFile()} for both access wideners and access
 * transformers; {@link #extensions()} so an importer can preserve anything it could not map
 * rather than discarding it. What an importer cannot represent, it reports as a typed warning —
 * it never silently drops.
 *
 * <p>Instances are immutable. Build with {@link Builder}.
 */
public record ModMetadata(
        String id,
        Optional<String> group,
        ModVersion version,
        Optional<String> name,
        Optional<String> description,
        List<String> authors,
        Optional<String> license,
        Map<String, String> contact,
        Environment environment,
        List<Entrypoint> entrypoints,
        List<Dependency> dependencies,
        List<ProvidedMod> provides,
        List<String> nestedJars,
        List<String> mixinConfigs,
        Optional<String> accessFile,
        Map<String, Object> extensions) {

    /** Canonical constructor; validates and defensively copies every collection. */
    public ModMetadata {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a mod must have an id");
        }
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(license, "license");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(accessFile, "accessFile");
        authors = List.copyOf(Objects.requireNonNull(authors, "authors"));
        // LinkedHashMap rather than Map.copyOf so manifest order survives: these are
        // rendered to users, and Map.copyOf's iteration order is unspecified.
        contact = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(Objects.requireNonNull(contact, "contact")));
        entrypoints = List.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        provides = List.copyOf(Objects.requireNonNull(provides, "provides"));
        nestedJars = List.copyOf(Objects.requireNonNull(nestedJars, "nestedJars"));
        mixinConfigs = List.copyOf(Objects.requireNonNull(mixinConfigs, "mixinConfigs"));
        extensions = java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(Objects.requireNonNull(extensions, "extensions")));
    }

    /** The display name, falling back to the id when the manifest gave none. */
    public String displayName() {
        return name.orElse(id);
    }

    /** Every entrypoint declared for {@code phase}, in manifest order. */
    public List<Entrypoint> entrypointsFor(String phase) {
        Objects.requireNonNull(phase, "phase");
        return entrypoints.stream().filter(entry -> entry.phase().equals(phase)).toList();
    }

    /** Every dependency of one kind. */
    public List<Dependency> dependenciesOf(Dependency.Kind kind) {
        Objects.requireNonNull(kind, "kind");
        return dependencies.stream().filter(dependency -> dependency.kind() == kind).toList();
    }

    /** Whether this mod loads on the given side (LDR-9). */
    public boolean loadsOn(Environment side) {
        return environment.loadsOn(side);
    }

    /**
     * The value stored under a namespaced extension key, e.g. {@code "omni:ported-from"}.
     *
     * <p>Namespacing is required rather than conventional: extensions are the one place where
     * unrelated tools write into the same map, and an unnamespaced key is a collision waiting
     * to happen.
     */
    public Optional<Object> extension(String namespacedKey) {
        return Optional.ofNullable(extensions.get(namespacedKey));
    }

    /** Starts building metadata for {@code id} at {@code version}. */
    public static Builder builder(String id, ModVersion version) {
        return new Builder(id, version);
    }

    @Override
    public String toString() {
        return id + " " + version;
    }

    /**
     * Mutable builder for {@link ModMetadata}.
     *
     * <p>Every field except id and version has a sane default, because a minimal manifest —
     * id, version, one entrypoint — must stay minimal to write.
     */
    public static final class Builder {
        private final String id;
        private final ModVersion version;
        private String group;
        private String name;
        private String description;
        private final List<String> authors = new java.util.ArrayList<>();
        private String license;
        private final Map<String, String> contact = new java.util.LinkedHashMap<>();
        private Environment environment = Environment.BOTH;
        private final List<Entrypoint> entrypoints = new java.util.ArrayList<>();
        private final List<Dependency> dependencies = new java.util.ArrayList<>();
        private final List<ProvidedMod> provides = new java.util.ArrayList<>();
        private final List<String> nestedJars = new java.util.ArrayList<>();
        private final List<String> mixinConfigs = new java.util.ArrayList<>();
        private String accessFile;
        private final Map<String, Object> extensions = new java.util.LinkedHashMap<>();

        private Builder(String id, ModVersion version) {
            this.id = Objects.requireNonNull(id, "id");
            this.version = Objects.requireNonNull(version, "version");
        }

        /** Sets the maven-style group. */
        public Builder group(String value) {
            this.group = value;
            return this;
        }

        /** Sets the human-readable name. */
        public Builder name(String value) {
            this.name = value;
            return this;
        }

        /** Sets the description. */
        public Builder description(String value) {
            this.description = value;
            return this;
        }

        /** Adds an author. */
        public Builder author(String value) {
            this.authors.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Sets the SPDX license identifier. */
        public Builder license(String value) {
            this.license = value;
            return this;
        }

        /** Adds a contact entry ({@code homepage}, {@code issues}, {@code sources}, …). */
        public Builder contact(String key, String value) {
            this.contact.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Sets the side this mod loads on. */
        public Builder environment(Environment value) {
            this.environment = Objects.requireNonNull(value, "value");
            return this;
        }

        /** Adds an entrypoint. */
        public Builder entrypoint(Entrypoint value) {
            this.entrypoints.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Adds a dependency. */
        public Builder dependency(Dependency value) {
            this.dependencies.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Adds a provided identity. */
        public Builder provides(ProvidedMod value) {
            this.provides.add(Objects.requireNonNull(value, "value"));
            return this;
        }

        /** Adds a nested jar path, relative to the jar root. */
        public Builder nestedJar(String path) {
            this.nestedJars.add(Objects.requireNonNull(path, "path"));
            return this;
        }

        /** Adds a mixin configuration file name. */
        public Builder mixinConfig(String path) {
            this.mixinConfigs.add(Objects.requireNonNull(path, "path"));
            return this;
        }

        /** Sets the access-modification file (access-widener dialect). */
        public Builder accessFile(String path) {
            this.accessFile = path;
            return this;
        }

        /** Stores a namespaced extension value. */
        public Builder extension(String namespacedKey, Object value) {
            this.extensions.put(Objects.requireNonNull(namespacedKey, "namespacedKey"), value);
            return this;
        }

        /** Builds the immutable metadata. */
        public ModMetadata build() {
            return new ModMetadata(
                    id,
                    Optional.ofNullable(group),
                    version,
                    Optional.ofNullable(name),
                    Optional.ofNullable(description),
                    authors,
                    Optional.ofNullable(license),
                    contact,
                    environment,
                    entrypoints,
                    dependencies,
                    provides,
                    nestedJars,
                    mixinConfigs,
                    Optional.ofNullable(accessFile),
                    extensions);
        }
    }
}
