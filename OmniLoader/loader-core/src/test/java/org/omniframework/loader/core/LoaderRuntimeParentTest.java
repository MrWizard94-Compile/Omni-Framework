package org.omniframework.loader.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The runtime parent's routing: JDK from the platform, the shared API from the source, and
 * everything else refused. This is the seal that keeps the application classpath out of the mod
 * environment while still letting mods share the API's identity.
 */
class LoaderRuntimeParentTest {

    /** A source that records what it was asked for and answers with a stand-in class. */
    private static final class RecordingSource extends ClassLoader {
        final AtomicInteger calls = new AtomicInteger();
        String lastRequested;

        RecordingSource() {
            super(null);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            calls.incrementAndGet();
            lastRequested = name;
            // The identity does not matter to these tests, only that the source was consulted;
            // String is a convenient always-loadable stand-in.
            return String.class;
        }
    }

    private static LoaderRuntimeParent parent(RecordingSource source) {
        return new LoaderRuntimeParent(source, List.of("shared.api."));
    }

    @Test
    @DisplayName("a shared-package class is delegated to the source")
    void sharedClassGoesToSource() throws ClassNotFoundException {
        RecordingSource source = new RecordingSource();

        Class<?> loaded = parent(source).loadClass("shared.api.Thing");

        assertSame(String.class, loaded, "the source's class must be returned unchanged");
        assertEquals("shared.api.Thing", source.lastRequested);
        assertEquals(1, source.calls.get());
    }

    @Test
    @DisplayName("a non-shared class is refused, and the source is never consulted")
    void unsharedClassIsSealed() {
        // The seal: the application classpath behind the source is reachable ONLY for the
        // allowlisted API packages. Anything else must not even reach the source.
        RecordingSource source = new RecordingSource();
        LoaderRuntimeParent parent = parent(source);

        assertThrows(ClassNotFoundException.class,
                () -> parent.loadClass("com.example.launcher.SecretDependency"));
        assertThrows(ClassNotFoundException.class,
                () -> parent.loadClass("org.omniframework.loader.core.Internal"),
                "loader internals are not shared — mods link the API, not the implementation");
        assertEquals(0, source.calls.get(), "a sealed name must never reach the source");
    }

    @Test
    @DisplayName("JDK classes resolve through the platform parent, not the source")
    void jdkClassesComeFromPlatform() throws ClassNotFoundException {
        // A JDK class is found by the platform parent before findClass runs, so the source —
        // which might have its own copy — is never asked. One String class, the platform's.
        RecordingSource source = new RecordingSource();
        LoaderRuntimeParent parent = parent(source);

        assertSame(String.class, parent.loadClass("java.lang.String"));
        assertSame(List.class, parent.loadClass("java.util.List"));
        assertEquals(0, source.calls.get(), "JDK classes must not be routed to the source");
    }
}
