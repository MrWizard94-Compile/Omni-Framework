package org.omniframework.loader.transform.pass;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.omniframework.loader.transform.TransformException;

/**
 * A request to make a game member more accessible.
 *
 * <p>Mods routinely need to touch fields and methods the game declares private. Both hosted
 * ecosystems have a file format for asking: Fabric's access widener and NeoForge's access
 * transformer. ARCHITECTURE §5 makes the native format an access-widener-compatible dialect, and
 * §6 merges all three into one applied set before any class is written.
 *
 * <h2>Widening is one-way</h2>
 *
 * <p>Access is only ever loosened, never tightened. If two mods ask for different levels on the
 * same member, the wider request wins rather than the last one parsed. Order-dependent access
 * would mean a pack that works until a mod is renamed, and a member that ends up
 * <em>less</em> accessible than a mod was compiled against fails at runtime with
 * {@code IllegalAccessError}.
 */
public record AccessChange(Target target, String owner, String name, String descriptor, Kind kind) {

    /** Canonical constructor. */
    public AccessChange {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
    }

    /** What a change applies to. */
    public enum Target {
        /** The class itself. */
        CLASS,
        /** A field on the class. */
        FIELD,
        /** A method on the class. */
        METHOD
    }

    /** How the access is loosened. */
    public enum Kind {
        /** Make public. */
        ACCESSIBLE,
        /** Remove {@code final}. */
        EXTENDABLE,
        /** Both: make public and remove {@code final}. */
        MUTABLE
    }

    /** A change targeting a whole class. */
    public static AccessChange ofClass(String owner, Kind kind) {
        return new AccessChange(Target.CLASS, owner, "", "", kind);
    }

    /** A change targeting one field. */
    public static AccessChange ofField(String owner, String name, String descriptor, Kind kind) {
        return new AccessChange(Target.FIELD, owner, name, descriptor, kind);
    }

    /** A change targeting one method. */
    public static AccessChange ofMethod(String owner, String name, String descriptor, Kind kind) {
        return new AccessChange(Target.METHOD, owner, name, descriptor, kind);
    }

    /** Applies this change to an access flag word. */
    public int apply(int access) {
        int result = access;
        if (kind == Kind.ACCESSIBLE || kind == Kind.MUTABLE) {
            result = (result & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        }
        if (kind == Kind.EXTENDABLE || kind == Kind.MUTABLE) {
            result &= ~Opcodes.ACC_FINAL;
        }
        return result;
    }

    /** A description for the audit. */
    public String describe() {
        String what = switch (target) {
            case CLASS -> owner;
            case FIELD -> owner + "." + name + ":" + descriptor;
            case METHOD -> owner + "." + name + descriptor;
        };
        return kind.name().toLowerCase(Locale.ROOT) + " " + what;
    }

    /**
     * Parses an access-widener v1/v2 document.
     *
     * <p>Format, one directive per line:
     *
     * <pre>{@code
     * accessWidener v2 named
     * accessible method net/minecraft/Foo bar ()V
     * extendable class net/minecraft/Baz
     * mutable field net/minecraft/Foo qux I
     * }</pre>
     *
     * <p>Comments start with {@code #}. Unknown directives are an error rather than a warning: a
     * silently ignored access request produces an {@code IllegalAccessError} deep in a mod at
     * runtime, which is far harder to trace back than a refusal at load.
     */
    public static List<AccessChange> parseWidener(String document) {
        Objects.requireNonNull(document, "document");
        List<AccessChange> changes = new ArrayList<>();
        String[] lines = document.split("\r?\n", -1);
        boolean sawHeader = false;
        for (int i = 0; i < lines.length; i++) {
            String line = stripComment(lines[i]).trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> parts = List.of(line.split("\\s+"));
            if (!sawHeader) {
                if (!"accessWidener".equals(parts.get(0))) {
                    throw new TransformException(
                            "access widener must begin with an 'accessWidener' header, got: " + line);
                }
                sawHeader = true;
                continue;
            }
            changes.add(parseDirective(parts, i + 1));
        }
        if (!sawHeader) {
            throw new TransformException("access widener is empty");
        }
        return changes;
    }

    private static AccessChange parseDirective(List<String> parts, int lineNumber) {
        if (parts.size() < 3) {
            throw new TransformException("line " + lineNumber + ": incomplete access directive");
        }
        Kind kind = parseKind(parts.get(0), lineNumber);
        String target = parts.get(1);
        String owner = parts.get(2);
        return switch (target) {
            case "class" -> ofClass(owner, kind);
            case "field" -> {
                requireArity(parts, 5, lineNumber, "field <owner> <name> <descriptor>");
                yield ofField(owner, parts.get(3), parts.get(4), kind);
            }
            case "method" -> {
                requireArity(parts, 5, lineNumber, "method <owner> <name> <descriptor>");
                yield ofMethod(owner, parts.get(3), parts.get(4), kind);
            }
            default -> throw new TransformException(
                    "line " + lineNumber + ": unknown access target '" + target
                            + "' (expected class, field, or method)");
        };
    }

    private static Kind parseKind(String token, int lineNumber) {
        return switch (token) {
            case "accessible" -> Kind.ACCESSIBLE;
            case "extendable" -> Kind.EXTENDABLE;
            case "mutable" -> Kind.MUTABLE;
            default -> throw new TransformException(
                    "line " + lineNumber + ": unknown access kind '" + token
                            + "' (expected accessible, extendable, or mutable)");
        };
    }

    private static void requireArity(List<String> parts, int arity, int lineNumber, String shape) {
        if (parts.size() < arity) {
            throw new TransformException("line " + lineNumber + ": expected " + shape);
        }
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    /**
     * Merges changes so each member carries the widest request made against it.
     *
     * <p>See the class note on why this is a merge and not a last-one-wins overwrite.
     */
    public static List<AccessChange> merge(List<AccessChange> changes) {
        Objects.requireNonNull(changes, "changes");
        List<AccessChange> merged = new ArrayList<>();
        Set<String> handled = new LinkedHashSet<>();
        for (AccessChange change : changes) {
            String identity = change.target() + "|" + change.owner() + "|" + change.name()
                    + "|" + change.descriptor();
            if (!handled.add(identity)) {
                continue;
            }
            Kind widest = changes.stream()
                    .filter(other -> identity.equals(other.target() + "|" + other.owner() + "|"
                            + other.name() + "|" + other.descriptor()))
                    .map(AccessChange::kind)
                    .reduce(change.kind(), AccessChange::widest);
            merged.add(new AccessChange(
                    change.target(), change.owner(), change.name(), change.descriptor(), widest));
        }
        return merged;
    }

    /** The looser of two kinds; {@code MUTABLE} subsumes both others. */
    static Kind widest(Kind left, Kind right) {
        if (left == right) {
            return left;
        }
        // accessible + extendable together are exactly mutable, and mutable absorbs either.
        return Kind.MUTABLE;
    }

    /** Whether this change targets the given class. */
    public boolean appliesToClass(String internalName) {
        return target == Target.CLASS && owner.equals(internalName);
    }

    /** Whether this change targets the given field. */
    public boolean appliesToField(String internalName, String fieldName, String fieldDescriptor) {
        return target == Target.FIELD && owner.equals(internalName)
                && name.equals(fieldName) && descriptor.equals(fieldDescriptor);
    }

    /** Whether this change targets the given method. */
    public boolean appliesToMethod(String internalName, String methodName, String methodDescriptor) {
        return target == Target.METHOD && owner.equals(internalName)
                && name.equals(methodName) && descriptor.equals(methodDescriptor);
    }

    /** The first change in {@code changes} applying to a class, if any. */
    public static Optional<AccessChange> forClass(List<AccessChange> changes, String internalName) {
        return changes.stream().filter(change -> change.appliesToClass(internalName)).findFirst();
    }
}
