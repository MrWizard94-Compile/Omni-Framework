package org.omniframework.loader.core.entrypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.model.Entrypoint;
import org.omniframework.loader.model.ModMetadata;
import org.omniframework.loader.model.ModVersion;

/** Declared, lazily instantiated entrypoints (LDR-7). */
class EntrypointDispatcherTest {

    /** What a mod's entrypoint implements, standing in for omni-api's ModInitializer. */
    public interface Initializer {
        void onInitialize();
    }

    /** Records construction and invocation order across all instances. */
    public static final List<String> EVENTS = new ArrayList<>();

    /** A well-behaved entrypoint. */
    public static final class Good implements Initializer {
        public Good() {
            EVENTS.add("construct:Good");
        }

        @Override
        public void onInitialize() {
            EVENTS.add("init:Good");
        }
    }

    /** Static method entrypoint — Fabric API style {@code Class::method}. */
    public static final class StaticInit {
        public static void init() {
            EVENTS.add("init:StaticInit");
        }
    }

    /** A second one, to prove ordering. */
    public static final class Second implements Initializer {
        public Second() {
            EVENTS.add("construct:Second");
        }

        @Override
        public void onInitialize() {
            EVENTS.add("init:Second");
        }
    }

    /** Throws from its initialiser — the common mod bug. */
    public static final class Throwing implements Initializer {
        @Override
        public void onInitialize() {
            throw new IllegalStateException("mod is broken");
        }
    }

    /**
     * Throws {@link AssertionError} from the constructor — same Error class as an unapplied
     * Mixin {@code @Invoker} stub (not RuntimeException / LinkageError).
     */
    public static final class AssertionFromCtor implements Initializer {
        public AssertionFromCtor() {
            throw new AssertionError("mixin invoker not applied");
        }

        @Override
        public void onInitialize() {
            // never reached
        }
    }

    /** Has no no-argument constructor. */
    public static final class NeedsArgument implements Initializer {
        public NeedsArgument(String required) {
            // Deliberately unconstructible by the default adapter.
        }

        @Override
        public void onInitialize() {
            // Never reached.
        }
    }

    /** Does not implement the requested type. */
    public static final class Unrelated {
        public Unrelated() {
            EVENTS.add("construct:Unrelated");
        }
    }

    private static ModMetadata mod(String id, Entrypoint... entrypoints) {
        ModMetadata.Builder builder = ModMetadata.builder(id, ModVersion.parse("1.0.0")).name(id);
        for (Entrypoint entrypoint : entrypoints) {
            builder.entrypoint(entrypoint);
        }
        return builder.build();
    }

    private static Entrypoint main(Class<?> type) {
        return Entrypoint.java("main", type.getName());
    }

    private EntrypointDispatcher dispatcher() {
        EVENTS.clear();
        return new EntrypointDispatcher();
    }

    @Test
    @DisplayName("an entrypoint is constructed and invoked")
    void runsEntrypoint() {
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(mod("good", main(Good.class)), getClass().getClassLoader());

        dispatcher.run("main", Initializer.class, (metadata, instance) -> instance.onInitialize());

        assertEquals(List.of("construct:Good", "init:Good"), EVENTS);
    }

    @Test
    @DisplayName("Class::method entrypoints bind static methods to the SAM interface")
    void runsStaticMethodReference() {
        // Fabric-API modules declare main as CommonPacketsImpl::init — Class.forName on the
        // whole string fails with "class does not exist".
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(
                mod(
                        "fabric-networking",
                        Entrypoint.java("main", StaticInit.class.getName() + "::init")),
                getClass().getClassLoader());

        dispatcher.run("main", Initializer.class, (metadata, instance) -> instance.onInitialize());

        assertEquals(List.of("init:StaticInit"), EVENTS);
    }

    @Test
    @DisplayName("entrypoints run in registration order, which is load order")
    void preservesLoadOrder() {
        // The resolver's whole ordering pass exists to establish this sequence; losing it here
        // would make that work pointless.
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(mod("first", main(Good.class)), getClass().getClassLoader());
        dispatcher.register(mod("second", main(Second.class)), getClass().getClassLoader());

        dispatcher.run("main", Initializer.class, (metadata, instance) -> instance.onInitialize());

        assertEquals(
                List.of("construct:Good", "init:Good", "construct:Second", "init:Second"), EVENTS);
    }

    @Test
    @DisplayName("beforeCreate runs for each mod immediately before that mod is constructed")
    void beforeCreateRunsBeforeEachConstruction() {
        // NeoForge hosting needs activate(modId) before @Mod construction; if beforeCreate only
        // ran once (or after create), multi-mod packs would share one active context.
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(mod("first", main(Good.class)), getClass().getClassLoader());
        dispatcher.register(mod("second", main(Second.class)), getClass().getClassLoader());

        dispatcher.run(
                "main",
                Initializer.class,
                metadata -> EVENTS.add("before:" + metadata.id()),
                (metadata, instance) -> instance.onInitialize());

        assertEquals(
                List.of(
                        "before:first",
                        "construct:Good",
                        "init:Good",
                        "before:second",
                        "construct:Second",
                        "init:Second"),
                EVENTS);
    }

    @Test
    @DisplayName("a phase nobody declared runs nothing, silently")
    void unusedPhaseIsNoOp() {
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(mod("good", main(Good.class)), getClass().getClassLoader());

        dispatcher.run("client", Initializer.class, (metadata, instance) -> instance.onInitialize());

        assertTrue(EVENTS.isEmpty(),
                "a client entrypoint must not be constructed when the client phase never runs");
        assertEquals(0, dispatcher.countFor("client"));
        assertEquals(1, dispatcher.countFor("main"));
    }

    @Test
    @DisplayName("entrypoints for an unrequested phase are never even constructed")
    void lazyConstruction() {
        // This is the property that keeps a client-only class from loading on a server. A
        // dispatcher that constructed eagerly would resolve rendering imports and die.
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(
                mod("sided", Entrypoint.java("client", Good.class.getName())),
                getClass().getClassLoader());

        dispatcher.run("main", Initializer.class, (metadata, instance) -> instance.onInitialize());

        assertTrue(EVENTS.isEmpty(), "constructing eagerly defeats the point of declared phases");
    }

    @Test
    @DisplayName("a mod that throws is named, and the others still run")
    void oneFailureDoesNotStopTheRest() {
        // Stopping at the first would mean fix, relaunch, discover the next — a full game start
        // per broken mod.
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(mod("broken", main(Throwing.class)), getClass().getClassLoader());
        dispatcher.register(mod("fine", main(Good.class)), getClass().getClassLoader());

        EntrypointPhaseException thrown = assertThrows(EntrypointPhaseException.class,
                () -> dispatcher.run("main", Initializer.class,
                        (metadata, instance) -> instance.onInitialize()));

        assertTrue(EVENTS.contains("init:Good"), "a working mod must still initialise");
        assertEquals(1, thrown.failures().size());
        assertTrue(thrown.getMessage().contains("broken"), thrown.getMessage());
        assertEquals("main", thrown.phase());
    }

    @Test
    @DisplayName("AssertionError from construction is attributed and does not abort the phase early")
    void assertionErrorFromInvokerStubIsCollected() {
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(
                mod("naturespirit", main(AssertionFromCtor.class)), getClass().getClassLoader());
        dispatcher.register(mod("fine", main(Good.class)), getClass().getClassLoader());

        EntrypointPhaseException thrown = assertThrows(EntrypointPhaseException.class,
                () -> dispatcher.run("main", Initializer.class,
                        (metadata, instance) -> instance.onInitialize()));

        assertTrue(EVENTS.contains("init:Good"), "later mods must still run after AssertionError");
        assertEquals(1, thrown.failures().size());
        assertTrue(
                thrown.getMessage().contains("naturespirit")
                        || thrown.getMessage().contains("AssertionFromCtor")
                        || thrown.getMessage().contains("AssertionError"),
                thrown.getMessage());
        Throwable cause = thrown.failures().get(0).getCause();
        // LanguageAdapter wraps constructor throw; walk to AssertionError.
        while (cause != null && !(cause instanceof AssertionError)) {
            cause = cause.getCause();
        }
        assertTrue(cause instanceof AssertionError, "cause should be the invoker AssertionError");
    }

    @Test
    @DisplayName("every failure is reported together, not just the first")
    void reportsAllFailures() {
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(mod("broken1", main(Throwing.class)), getClass().getClassLoader());
        dispatcher.register(mod("broken2", main(Throwing.class)), getClass().getClassLoader());

        EntrypointPhaseException thrown = assertThrows(EntrypointPhaseException.class,
                () -> dispatcher.run("main", Initializer.class,
                        (metadata, instance) -> instance.onInitialize()));

        assertEquals(2, thrown.failures().size());
        assertTrue(thrown.getMessage().startsWith("2 mods failed"), thrown.getMessage());
        assertEquals(2, thrown.getSuppressed().length,
                "failures are attached rather than chained — there is no single cause when "
                        + "several mods failed independently");
    }

    @Test
    @DisplayName("a missing entrypoint class names the mod and the declared value")
    void missingClassIsNamed() {
        // "Entrypoint failed" with no name is unactionable in a 300-mod pack.
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(
                mod("typo", Entrypoint.java("main", "net.example.DoesNotExist")),
                getClass().getClassLoader());

        EntrypointPhaseException thrown = assertThrows(EntrypointPhaseException.class,
                () -> dispatcher.run("main", Initializer.class,
                        (metadata, instance) -> instance.onInitialize()));

        assertTrue(thrown.getMessage().contains("net.example.DoesNotExist"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("does not exist"), thrown.getMessage());
    }

    @Test
    @DisplayName("a class of the wrong type is refused BEFORE being constructed")
    void wrongTypeIsNotConstructed() {
        // Constructing first and failing the cast afterwards would run arbitrary mod code for
        // an entrypoint that was never going to be usable.
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(mod("wrong", main(Unrelated.class)), getClass().getClassLoader());

        EntrypointPhaseException thrown = assertThrows(EntrypointPhaseException.class,
                () -> dispatcher.run("main", Initializer.class,
                        (metadata, instance) -> instance.onInitialize()));

        assertFalse(EVENTS.contains("construct:Unrelated"),
                "the mod's constructor must not run for an unusable entrypoint");
        assertTrue(thrown.getMessage().contains("does not implement"), thrown.getMessage());
    }

    @Test
    @DisplayName("a class with no no-arg constructor says exactly that")
    void missingConstructorIsNamed() {
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(mod("noctor", main(NeedsArgument.class)), getClass().getClassLoader());

        EntrypointPhaseException thrown = assertThrows(EntrypointPhaseException.class,
                () -> dispatcher.run("main", Initializer.class,
                        (metadata, instance) -> instance.onInitialize()));

        assertTrue(thrown.getMessage().contains("no-argument constructor"), thrown.getMessage());
    }

    @Test
    @DisplayName("an unknown language adapter names the adapter and what is available")
    void unknownAdapterIsNamed() {
        // A mod written for a language the pack cannot load is fixable — but only if the report
        // says which language.
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(
                mod("kotlinmod", new Entrypoint("main", Good.class.getName(), Optional.of("kotlin"))),
                getClass().getClassLoader());

        EntrypointPhaseException thrown = assertThrows(EntrypointPhaseException.class,
                () -> dispatcher.run("main", Initializer.class,
                        (metadata, instance) -> instance.onInitialize()));

        assertTrue(thrown.getMessage().contains("kotlin"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("java"),
                () -> "the message should say what IS available: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a registered adapter is used instead of the default")
    void customAdapterIsUsed() {
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.registerAdapter("kotlin", new LanguageAdapter() {
            @Override
            public <T> T create(Entrypoint entrypoint, Class<T> type, ClassLoader loader) {
                EVENTS.add("adapter:kotlin");
                return type.cast((Initializer) () -> EVENTS.add("init:viaAdapter"));
            }
        });
        dispatcher.register(
                mod("kotlinmod", new Entrypoint("main", "net.example.Obj", Optional.of("kotlin"))),
                getClass().getClassLoader());

        dispatcher.run("main", Initializer.class, (metadata, instance) -> instance.onInitialize());

        assertEquals(List.of("adapter:kotlin", "init:viaAdapter"), EVENTS);
        assertTrue(dispatcher.hasAdapter("kotlin"));
    }

    @Test
    @DisplayName("instantiate returns the instances without running them")
    void instantiateDoesNotInvoke() {
        // A compat layer bridging to a guest event system needs the object, not a callback.
        EntrypointDispatcher dispatcher = dispatcher();
        dispatcher.register(mod("good", main(Good.class)), getClass().getClassLoader());

        List<Initializer> instances = dispatcher.instantiate("main", Initializer.class);

        assertEquals(1, instances.size());
        assertEquals(List.of("construct:Good"), EVENTS, "instantiate must not invoke");
    }
}
