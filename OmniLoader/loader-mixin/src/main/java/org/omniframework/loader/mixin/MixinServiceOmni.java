package org.omniframework.loader.mixin;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment.CompatibilityLevel;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

/**
 * OmniLoader's implementation of Mixin's service SPI (LDR-8, ARCHITECTURE §8).
 *
 * <p>This is the single integration point ADR-0009 calls for: everything Mixin knows about the
 * host — how to find a class, how to read its bytecode, what is already loaded, where to record
 * what it did — arrives through here. Keeping it to one class is what makes a future runtime swap
 * contained rather than a search through the codebase.
 *
 * <h2>Wiring happens before Mixin is touched</h2>
 *
 * <p>Mixin discovers this class by {@code ServiceLoader} and constructs it with a no-argument
 * constructor, so it cannot be handed its collaborators at construction. They are installed by
 * {@link #install} before anything asks Mixin to do work, and {@link #isValid()} reports false
 * until then — which is how Mixin is told to refuse rather than proceed against a half-built
 * host.
 *
 * <h2>The compatibility level is raised deliberately</h2>
 *
 * <p>{@link #getMaxCompatibilityLevel()} is not a formality. Mixin's own
 * {@code CompatibilityLevel.MAX_SUPPORTED} defaults to {@code JAVA_13} and {@code DEFAULT} to
 * {@code JAVA_6}; they are values the host is expected to raise, and this method is the channel
 * for doing it. Leaving them alone on a Java 21 game clamps every mod to Java 13 semantics and
 * drops anything undeclared to Java 6 — silently, because a lower level is still "valid". See
 * {@link CompatibilityLevels}.
 */
public final class MixinServiceOmni extends MixinServiceAbstract {

    /** The service name Mixin reports in diagnostics. */
    public static final String NAME = "OmniLoader";

    private static volatile Wiring wiring;

    /** Everything the service needs from the host, installed before Mixin runs. */
    public record Wiring(
            IClassProvider classProvider,
            IClassBytecodeProvider bytecodeProvider,
            OmniClassTracker classTracker,
            OmniAuditTrail auditTrail,
            CompatibilityLevels levels) {

        /** Canonical constructor. */
        public Wiring {
            Objects.requireNonNull(classProvider, "classProvider");
            Objects.requireNonNull(bytecodeProvider, "bytecodeProvider");
            Objects.requireNonNull(classTracker, "classTracker");
            Objects.requireNonNull(auditTrail, "auditTrail");
            Objects.requireNonNull(levels, "levels");
        }
    }

    /** Required by {@code ServiceLoader}. */
    public MixinServiceOmni() {
        // Collaborators arrive through install(); see the class note.
    }

    /**
     * Installs the host's collaborators. Must be called before Mixin is asked to do anything.
     *
     * <p>Static because {@code ServiceLoader} owns the instance's construction and Mixin may
     * create it at a moment the loader does not control. The alternative — discovering the
     * wiring lazily from a global registry — moves the same coupling somewhere less visible.
     */
    public static void install(Wiring installed) {
        wiring = Objects.requireNonNull(installed, "installed");
    }

    /** Clears the wiring. For tests, which must not leak state between cases. */
    public static void reset() {
        wiring = null;
    }

    /** The wiring, or null before {@link #install}. */
    static Wiring wiring() {
        return wiring;
    }

    private static Wiring require() {
        Wiring current = wiring;
        if (current == null) {
            // Failing loudly beats returning a null collaborator: Mixin would dereference it
            // several frames later, and the resulting NPE would name none of this.
            throw new IllegalStateException(
                    "MixinServiceOmni was used before install() — the loader must wire the "
                            + "service before Mixin bootstraps");
        }
        return current;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isValid() {
        return wiring != null;
    }

    @Override
    public IClassProvider getClassProvider() {
        return require().classProvider();
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return require().bytecodeProvider();
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        // OmniLoader has no legacy transformer chain for Mixin to interoperate with: all
        // ahead-of-time work is done by loader-transform before Mixin ever sees a class
        // (ARCHITECTURE §6). Returning null is Mixin's documented "no such provider", and is
        // honest — an empty provider would imply a chain exists but is currently empty.
        return null;
    }

    @Override
    public IClassTracker getClassTracker() {
        return require().classTracker();
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return require().auditTrail();
    }

    @Override
    public Collection<String> getPlatformAgents() {
        // Platform agents adapt Mixin to a host launcher's container model. OmniLoader supplies
        // its containers directly through getPrimaryContainer(), so none is needed.
        return List.of();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual(NAME);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return getClass().getClassLoader().getResourceAsStream(name);
    }

    @Override
    public Phase getInitialPhase() {
        // PREINIT: mixins must be registered before the game's own classes start loading, since
        // a class already defined can no longer be modified (see OmniClassTracker).
        return Phase.PREINIT;
    }

    @Override
    public CompatibilityLevel getMinCompatibilityLevel() {
        return CompatibilityLevel.JAVA_8;
    }

    /**
     * Builds Mixin's transformer, for the loader to wrap in a {@link MixinClassTransformer}.
     *
     * <p>Mixin hands its internals to the service through {@code offer()} during
     * initialisation, and {@code getInternal} — protected on the abstract base — is the only way
     * back to them. Exposing exactly this one factory keeps the loader from needing access to
     * Mixin's internals generally.
     *
     * @throws IllegalStateException if called before Mixin has initialised and offered its
     *     factory, which would otherwise surface as a null dereference inside the caller
     */
    public org.spongepowered.asm.mixin.transformer.IMixinTransformer createTransformer() {
        var factory = getInternal(
                org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory.class);
        if (factory == null) {
            throw new IllegalStateException(
                    "Mixin has not offered its transformer factory yet — MixinBootstrap.init() "
                            + "must run before the loader asks for a transformer");
        }
        return factory.createTransformer();
    }

    @Override
    public CompatibilityLevel getMaxCompatibilityLevel() {
        Wiring current = wiring;
        // Before wiring, report Mixin's own ceiling rather than throwing: Mixin queries this
        // during its own static init, which can run before the loader gets a chance to install.
        return current == null ? CompatibilityLevel.MAX_SUPPORTED : current.levels().ceiling();
    }
}
