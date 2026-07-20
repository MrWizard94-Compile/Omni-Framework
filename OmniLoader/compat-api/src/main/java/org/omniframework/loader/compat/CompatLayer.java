package org.omniframework.loader.compat;

import java.util.List;
import java.util.Optional;
import org.omniframework.loader.resolve.ResolverRule;

/**
 * One hosted ecosystem, running on OmniLoader (CMP-1, ARCHITECTURE §7).
 *
 * <h2>Why seven contracts rather than one "load this mod" method</h2>
 *
 * <p>The corpus lesson from Sinytra Connector is that hosting another loader's mods is not one
 * problem but seven, and that they fail independently. A layer can import metadata perfectly and
 * still break because its entrypoints fire at the wrong moment; it can remap bytecode correctly
 * and still break because the guest's API object answers a question wrongly. Bundling them into
 * one interface makes it impossible to say which half works, and impossible to ship the half
 * that does.
 *
 * <p>So each contract is separately implementable, separately testable, and separately
 * <em>optional</em>: a metadata-level layer like Fabric's needs no jar rewriting at all and
 * returns empty from {@link #transformContributor()}, while a translation layer needs all seven.
 * The loader must never assume a layer provides a contract it did not claim.
 *
 * <h2>What a layer must not do</h2>
 *
 * <p>A layer describes what it needs; it does not reach into the loader to take it. Nothing here
 * hands out the classloader, the transform cache, or the resolver's internals. That boundary is
 * what lets a layer be tested without a booting game, and what stops the two shipped layers
 * growing dependencies on each other's internals — the failure mode that makes a compat system
 * unmaintainable once it has more than one member.
 */
public interface CompatLayer {

    /** Stable id, used in diagnostics and in cache keys ({@code layerId} per ADR-0010). */
    String id();

    /**
     * The layer's version, folded into the transform cache key.
     *
     * <p>Bump it whenever the layer's behaviour changes, or every cached jar it produced stays a
     * hit and the change reaches nobody — the same trap as a pass version in ARCHITECTURE §6.
     */
    String version();

    /** Decides which jars belong to this ecosystem (contract 1). */
    EcosystemClaimer claimer();

    /** Converts a claimed jar's manifest into the canonical model (contract 2). */
    MetadataImporter importer();

    /**
     * Resolver rules for the guest's dependency dialect (contract 3).
     *
     * <p>Empty when the guest's semantics are already expressible in the canonical model.
     */
    default List<ResolverRule> resolverRules() {
        return List.of();
    }

    /**
     * AOT passes this ecosystem's jars need (contract 4).
     *
     * <p>Empty for a metadata-level layer. Fabric-family mods still need intermediary→official
     * remapping, but that is the pipeline's own pass configured with the layer's mappings, not a
     * pass the layer supplies.
     */
    default Optional<TransformContributor> transformContributor() {
        return Optional.empty();
    }

    /**
     * The guest loader's own API, reimplemented over OmniLoader (contract 5).
     *
     * <p>This is where a layer's real cost lives. A guest mod calls its own loader's API and
     * expects its own loader's answers; every method that exists is one the layer must answer
     * correctly, and every method that does not is a {@code NoSuchMethodError} in a mod that
     * looked like it loaded.
     */
    default Optional<ApiFacade> apiFacade() {
        return Optional.empty();
    }

    /**
     * The layer's behavioural patches (contract 6).
     *
     * <p>Tracked and size-reported because CMP-5 makes shim count a health metric: a compat
     * layer that only grows is one that is losing.
     */
    default Optional<ShimSet> shims() {
        return Optional.empty();
    }

    /** Maps the guest's entrypoint and event timing onto Omni's phases (contract 7). */
    default Optional<LifecycleBridge> lifecycleBridge() {
        return Optional.empty();
    }
}
