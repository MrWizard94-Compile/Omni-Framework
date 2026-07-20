package org.omniframework.loader.model;

import java.util.Objects;
import java.util.Optional;

/**
 * One dependency edge, in the five kinds the canonical model recognises (LDR-6).
 *
 * <p>The separation of <em>satisfaction</em> from <em>ordering</em> is the important design
 * point, and it is why {@link LoadOrder} is a separate field rather than a sixth kind. Two mods
 * can require each other's presence without either caring who initialises first; equally, a mod
 * can demand it loads {@link LoadOrder#AFTER} another without requiring that mod to exist at
 * all. Collapsing the two — as some loaders do — makes "load me after X if X is present"
 * inexpressible, which is precisely the common case.
 */
public record Dependency(
        Kind kind,
        String modId,
        VersionPredicate versions,
        LoadOrder order,
        Optional<String> reason) {

    /** Canonical constructor; validates. */
    public Dependency {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(reason, "reason");
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("a dependency must name a mod id");
        }
    }

    /** The five dependency kinds. */
    public enum Kind {
        /** Must be present and matching, or this mod does not load. */
        DEPENDS("depends", true),
        /** Should be present; absence is a warning, never a failure. */
        RECOMMENDS("recommends", false),
        /** Mentioned for discovery only; absence is silent. */
        SUGGESTS("suggests", false),
        /** If present and matching, <em>this</em> mod does not load. */
        BREAKS("breaks", true),
        /** If present and matching, the two cannot load together. */
        CONFLICTS("conflicts", true);

        private final String manifestKey;
        private final boolean blocking;

        Kind(String manifestKey, boolean blocking) {
            this.manifestKey = manifestKey;
            this.blocking = blocking;
        }

        /** The manifest key this kind is declared under. */
        public String manifestKey() {
            return manifestKey;
        }

        /** Whether violating this kind prevents loading. */
        public boolean isBlocking() {
            return blocking;
        }

        /** Whether this kind expresses incompatibility rather than requirement. */
        public boolean isNegative() {
            return this == BREAKS || this == CONFLICTS;
        }
    }

    /** Ordering constraint relative to the named mod, independent of requirement. */
    public enum LoadOrder {
        /** No constraint; the resolver may order these freely. */
        NONE("none"),
        /** This mod loads before the named mod, if that mod is present. */
        BEFORE("before"),
        /** This mod loads after the named mod, if that mod is present. */
        AFTER("after");

        private final String manifestValue;

        LoadOrder(String manifestValue) {
            this.manifestValue = manifestValue;
        }

        /** The exact string a manifest uses. */
        public String manifestValue() {
            return manifestValue;
        }

        /** Parses a manifest value, or empty when unrecognised. */
        public static Optional<LoadOrder> parse(String text) {
            if (text == null) {
                return Optional.empty();
            }
            for (LoadOrder candidate : values()) {
                if (candidate.manifestValue.equals(text)) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        }
    }

    /** Whether a present mod at {@code version} satisfies this dependency's version range. */
    public boolean matches(ModVersion version) {
        return versions.matches(version);
    }

    @Override
    public String toString() {
        return kind.manifestKey() + " " + modId + " " + versions
                + (order == LoadOrder.NONE ? "" : " (" + order.manifestValue() + ")");
    }
}
