package org.omniframework.loader.compat;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides whether a jar belongs to this ecosystem (contract 1, ARCHITECTURE §7).
 *
 * <p>Claiming is by <b>fingerprint</b> — the presence and shape of the guest's own manifest —
 * never by filename or by guessing from the mod's id. A jar named for one loader routinely
 * contains another's manifest, and multi-loader mods legitimately carry both.
 *
 * <h2>Two layers claiming the same jar is a real situation, not an error</h2>
 *
 * <p>Roughly one mod in thirty ships a single jar carrying both {@code fabric.mod.json} and
 * {@code META-INF/mods.toml} — measured on the director's own 109-mod pack, 2026-07-20. Both
 * layers will claim it, and both are right.
 *
 * <p>The loader resolves that by {@link #priority()}, never by asking a layer to guess whether
 * someone else wants the jar. A claimer that tried to be polite about it would produce a jar
 * nobody claims the moment the other layer's heuristics changed.
 */
@FunctionalInterface
public interface EcosystemClaimer {

    /**
     * Inspects a candidate jar.
     *
     * @param jar the jar to inspect; implementations must not modify it
     * @return a claim if this ecosystem recognises the jar, empty otherwise
     */
    Optional<Claim> claim(Path jar);

    /**
     * How strongly this layer wants a contested jar. Higher wins; ties are a loader error.
     *
     * <p>Default sits above nothing in particular: a layer with an opinion should state one.
     */
    default int priority() {
        return 0;
    }

    /** What a layer found, and where. */
    record Claim(String manifestPath, String detectedId, String detectedVersion) {

        /** Canonical constructor. */
        public Claim {
            java.util.Objects.requireNonNull(manifestPath, "manifestPath");
            java.util.Objects.requireNonNull(detectedId, "detectedId");
            java.util.Objects.requireNonNull(detectedVersion, "detectedVersion");
        }

        /** A one-line rendering for diagnostics. */
        public String describe() {
            return detectedId + " " + detectedVersion + " (from " + manifestPath + ")";
        }
    }
}
