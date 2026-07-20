package org.omniframework.loader.transform.mapping;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A one-directional rename of classes, fields, and methods.
 *
 * <p>OmniLoader's runtime namespace is Mojang official names (LDR-3, D2). Fabric-family mods are
 * compiled against <em>intermediary</em> names, so every such jar must be rewritten before it can
 * resolve against the game. This type holds the rename; {@code Remapper} applies it.
 *
 * <h2>Members are keyed by owner AND descriptor, not by name</h2>
 *
 * <p>A name alone is ambiguous in the JVM in two ways that both occur constantly in Minecraft:
 * different classes reuse the same field name, and one class can declare several methods with the
 * same name and different signatures. Keying on {@code owner + name + descriptor} is what makes
 * a rename land on the member the mapping author meant rather than on whichever member happened
 * to match first.
 *
 * <p>Instances are immutable and safe to share between the threads transforming different jars in
 * parallel.
 */
public final class MappingSet {

    private final String sourceNamespace;
    private final String targetNamespace;
    private final Map<String, String> classes;
    private final Map<MemberKey, String> fields;
    private final Map<MemberKey, String> methods;

    private MappingSet(
            String sourceNamespace,
            String targetNamespace,
            Map<String, String> classes,
            Map<MemberKey, String> fields,
            Map<MemberKey, String> methods) {
        this.sourceNamespace = sourceNamespace;
        this.targetNamespace = targetNamespace;
        this.classes = Collections.unmodifiableMap(classes);
        this.fields = Collections.unmodifiableMap(fields);
        this.methods = Collections.unmodifiableMap(methods);
    }

    /** A mapping set that renames nothing — used for native jars, which are already official. */
    public static MappingSet identity() {
        return new MappingSet("official", "official", Map.of(), Map.of(), Map.of());
    }

    /** Whether this set renames anything at all. */
    public boolean isIdentity() {
        return classes.isEmpty() && fields.isEmpty() && methods.isEmpty();
    }

    /** The namespace names are recorded so a mismatched artifact can be caught, not guessed at. */
    public String sourceNamespace() {
        return sourceNamespace;
    }

    /** The namespace this set renames into. */
    public String targetNamespace() {
        return targetNamespace;
    }

    /** The new internal name for a class, if it is mapped. */
    public Optional<String> mapClass(String internalName) {
        return Optional.ofNullable(classes.get(internalName));
    }

    /** The new name for a field declared directly on {@code owner}, if mapped. */
    public Optional<String> mapField(String owner, String name, String descriptor) {
        return Optional.ofNullable(fields.get(new MemberKey(owner, name, descriptor)));
    }

    /** The new name for a method declared directly on {@code owner}, if mapped. */
    public Optional<String> mapMethod(String owner, String name, String descriptor) {
        return Optional.ofNullable(methods.get(new MemberKey(owner, name, descriptor)));
    }

    /** How many classes this set renames. */
    public int classCount() {
        return classes.size();
    }

    /** How many fields this set renames. */
    public int fieldCount() {
        return fields.size();
    }

    /** How many methods this set renames. */
    public int methodCount() {
        return methods.size();
    }

    /** A one-line summary for logs and cache metadata. */
    public String describe() {
        return sourceNamespace + "→" + targetNamespace + " (" + classes.size() + " classes, "
                + fields.size() + " fields, " + methods.size() + " methods)";
    }

    /** The identity of one member: which class declares it, its name, and its descriptor. */
    record MemberKey(String owner, String name, String descriptor) {
        MemberKey {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
        }
    }

    /** Accumulates a mapping set. Not thread-safe; build once, then share. */
    public static final class Builder {

        private final String sourceNamespace;
        private final String targetNamespace;
        private final Map<String, String> classes = new HashMap<>();
        private final Map<MemberKey, String> fields = new HashMap<>();
        private final Map<MemberKey, String> methods = new HashMap<>();

        /** Starts a set that renames from {@code source} into {@code target}. */
        public Builder(String sourceNamespace, String targetNamespace) {
            this.sourceNamespace = Objects.requireNonNull(sourceNamespace, "sourceNamespace");
            this.targetNamespace = Objects.requireNonNull(targetNamespace, "targetNamespace");
        }

        /** Renames a class by internal name ({@code a/b/C}). */
        public Builder mapClass(String from, String to) {
            classes.put(Objects.requireNonNull(from, "from"), Objects.requireNonNull(to, "to"));
            return this;
        }

        /** Renames a field declared on {@code owner}. */
        public Builder mapField(String owner, String name, String descriptor, String to) {
            fields.put(new MemberKey(owner, name, descriptor), Objects.requireNonNull(to, "to"));
            return this;
        }

        /** Renames a method declared on {@code owner}. */
        public Builder mapMethod(String owner, String name, String descriptor, String to) {
            methods.put(new MemberKey(owner, name, descriptor), Objects.requireNonNull(to, "to"));
            return this;
        }

        /** Freezes into an immutable set. */
        public MappingSet build() {
            return new MappingSet(sourceNamespace, targetNamespace,
                    new HashMap<>(classes), new HashMap<>(fields), new HashMap<>(methods));
        }
    }
}
