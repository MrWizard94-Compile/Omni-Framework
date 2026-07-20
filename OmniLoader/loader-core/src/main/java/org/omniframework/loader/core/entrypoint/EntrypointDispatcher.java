package org.omniframework.loader.core.entrypoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.omniframework.loader.model.Entrypoint;
import org.omniframework.loader.model.ModMetadata;

/**
 * Instantiates and runs declared entrypoints, in load order (LDR-7).
 *
 * <h2>Lazy, and why that is not just an optimisation</h2>
 *
 * <p>Entrypoints are created only when a phase is actually requested. A client-only mod's
 * {@code client} entrypoint is never constructed on a server, so its static initialisers never
 * run and its rendering imports are never resolved — which is the difference between a server
 * that starts and one that dies on {@code NoClassDefFoundError} for a class it was never going to
 * use.
 *
 * <p>The corpus is unanimous here: annotation scanning would force every class of every mod to be
 * read at boot, and declared-plus-lazy is what keeps startup proportional to what is actually
 * used rather than to how much is installed.
 *
 * <h2>One failing mod is reported, not hidden — and does not silently maim the rest</h2>
 *
 * <p>When an entrypoint throws, the dispatcher records it and keeps going, then reports every
 * failure together at the end. Stopping at the first would mean a user fixes one mod, relaunches,
 * and discovers the next — a loop that costs a full game start per broken mod.
 *
 * <p>But "keep going" is not "carry on regardless": {@link #run} throws once the phase completes
 * if anything failed, carrying every failure. A phase that half-ran is not a working game, and
 * pretending otherwise produces bug reports about a mod that never initialised.
 */
public final class EntrypointDispatcher {

    private final Map<String, LanguageAdapter> adapters = new LinkedHashMap<>();
    private final List<Registered> registered = new ArrayList<>();

    /** One mod's declaration, kept with the loader that can see its classes. */
    private record Registered(ModMetadata metadata, Entrypoint entrypoint, ClassLoader loader) { }

    /** Builds a dispatcher with the default Java adapter installed. */
    public EntrypointDispatcher() {
        adapters.put(DEFAULT_ADAPTER, LanguageAdapter.java());
    }

    /** The adapter name used when a manifest names none. */
    public static final String DEFAULT_ADAPTER = "java";

    /**
     * Installs an adapter under {@code name}.
     *
     * <p>Replacing the default is allowed and occasionally wanted — a compat layer may need
     * different construction semantics — so this does not refuse an existing name.
     */
    public void registerAdapter(String name, LanguageAdapter adapter) {
        adapters.put(
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(adapter, "adapter"));
    }

    /** Whether an adapter is installed under {@code name}. */
    public boolean hasAdapter(String name) {
        return adapters.containsKey(name);
    }

    /**
     * Registers every entrypoint a mod declares.
     *
     * <p>Call order is load order, and it is preserved: a mod that resolved earlier initialises
     * earlier, which is the contract the resolver's ordering pass exists to establish.
     */
    public void register(ModMetadata metadata, ClassLoader loader) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(loader, "loader");
        for (Entrypoint entrypoint : metadata.entrypoints()) {
            registered.add(new Registered(metadata, entrypoint, loader));
        }
    }

    /** How many entrypoints are registered for a phase. */
    public int countFor(String phase) {
        Objects.requireNonNull(phase, "phase");
        return (int) registered.stream()
                .filter(entry -> entry.entrypoint().phase().equals(phase))
                .count();
    }

    /**
     * Creates every entrypoint declared for {@code phase}, without running anything.
     *
     * <p>Separate from {@link #run} because a caller sometimes wants the instances themselves —
     * a compat layer bridging to a guest ecosystem's own event system needs the object, not a
     * callback.
     *
     * @throws EntrypointException if any instance cannot be created
     */
    public <T> List<T> instantiate(String phase, Class<T> type) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(type, "type");
        List<T> instances = new ArrayList<>();
        for (Registered entry : registered) {
            if (!entry.entrypoint().phase().equals(phase)) {
                continue;
            }
            instances.add(create(entry, type));
        }
        return instances;
    }

    /**
     * Creates and invokes every entrypoint for {@code phase}.
     *
     * @param action what to do with each instance — typically calling its initialise method
     * @throws EntrypointPhaseException if any entrypoint failed, carrying all of them
     */
    public <T> void run(String phase, Class<T> type, BiConsumer<ModMetadata, T> action) {
        Objects.requireNonNull(action, "action");
        List<EntrypointException> failures = new ArrayList<>();

        for (Registered entry : registered) {
            if (!entry.entrypoint().phase().equals(phase)) {
                continue;
            }
            try {
                T instance = create(entry, type);
                action.accept(entry.metadata(), instance);
            } catch (EntrypointException e) {
                failures.add(e);
            } catch (RuntimeException | LinkageError e) {
                // A mod's own initialiser throwing is the common case, and it must be
                // attributed to that mod rather than surfacing as an anonymous stack trace.
                failures.add(new EntrypointException(entry.entrypoint(),
                        "the mod '" + entry.metadata().id() + "' threw during initialisation", e));
            }
        }

        if (!failures.isEmpty()) {
            throw new EntrypointPhaseException(phase, failures);
        }
    }

    private <T> T create(Registered entry, Class<T> type) {
        String adapterName = entry.entrypoint().adapter().orElse(DEFAULT_ADAPTER);
        LanguageAdapter adapter = adapters.get(adapterName);
        if (adapter == null) {
            throw new EntrypointException(entry.entrypoint(),
                    "no language adapter named '" + adapterName + "' is installed — the mod '"
                            + entry.metadata().id() + "' is written for a language this pack "
                            + "cannot load. Available: " + String.join(", ", adapters.keySet()));
        }
        return adapter.create(entry.entrypoint(), type, entry.loader());
    }
}
