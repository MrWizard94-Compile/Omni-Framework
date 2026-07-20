package org.omniframework.loader.core.entrypoint;

import org.omniframework.loader.model.Entrypoint;

/**
 * Turns a declared entrypoint into an instance of the type the caller wants (LDR-7).
 *
 * <h2>Why this is pluggable rather than "call the no-arg constructor"</h2>
 *
 * <p>A plain Java mod's entrypoint is a class with a default constructor, and
 * {@link #java()} handles exactly that. But a Kotlin mod's entrypoint is idiomatically an
 * {@code object}, reached through its {@code INSTANCE} field rather than by construction, and a
 * Scala mod's is different again. Without adapters those ecosystems are second-class: their
 * authors write a Java shim class whose only job is to forward one call.
 *
 * <p>The corpus is clear that this is worth having up front — retrofitting adapters after mods
 * exist means every existing manifest has to be reinterpreted. So the seam is here from the
 * start, and the default is one implementation among several rather than a special case.
 *
 * <p>Adapters are named in the manifest ({@code "adapter": "kotlin"}), and an entrypoint that
 * names an adapter the loader does not have must fail with that name in the message — a mod
 * asking for a language the pack cannot load is a fixable situation, but only if the report says
 * which language.
 */
@FunctionalInterface
public interface LanguageAdapter {

    /**
     * Creates the entrypoint instance.
     *
     * @param entrypoint the declaration, for its value and for error messages
     * @param type the interface the caller expects
     * @param loader the classloader the mod's classes live in
     * @param <T> the expected type
     * @return the instance, never null
     * @throws EntrypointException if the class is missing, is not of {@code type}, or cannot be
     *     constructed — always naming the mod and the declared value, because "entrypoint failed"
     *     with no name is unactionable in a 300-mod pack
     */
    <T> T create(Entrypoint entrypoint, Class<T> type, ClassLoader loader);

    /**
     * The default adapter: load the class, call its no-argument constructor, cast.
     *
     * <p>Refuses a class that is not assignable to the requested type <em>before</em>
     * constructing it. Constructing first and failing the cast afterwards would run the mod's
     * constructor — arbitrary code with side effects — for an entrypoint that was never going to
     * be usable.
     */
    static LanguageAdapter java() {
        return new LanguageAdapter() {
            @Override
            public <T> T create(Entrypoint entrypoint, Class<T> type, ClassLoader loader) {
                Class<?> found;
                try {
                    found = Class.forName(entrypoint.value(), false, loader);
                } catch (ClassNotFoundException e) {
                    throw new EntrypointException(entrypoint,
                            "the class does not exist in this mod's jar", e);
                }
                if (!type.isAssignableFrom(found)) {
                    throw new EntrypointException(entrypoint,
                            "it is declared for phase '" + entrypoint.phase() + "' but does not "
                                    + "implement " + type.getName());
                }
                try {
                    Object instance = found.getDeclaredConstructor().newInstance();
                    return type.cast(instance);
                } catch (NoSuchMethodException e) {
                    throw new EntrypointException(entrypoint,
                            "it has no public no-argument constructor", e);
                } catch (ReflectiveOperationException e) {
                    // Unwrap: an exception thrown by the mod's own constructor is the useful
                    // one, and leaving it wrapped in InvocationTargetException buries it.
                    Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite
                            ? ite.getTargetException()
                            : e;
                    throw new EntrypointException(entrypoint,
                            "its constructor threw " + cause, cause);
                }
            }
        };
    }
}
