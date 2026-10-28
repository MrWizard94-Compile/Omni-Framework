package org.omniframework.loader.core.entrypoint;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
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
     * Default adapter matching Fabric Loader's Java notation:
     *
     * <ul>
     *   <li>{@code com.example.Mod} — no-arg construct; class must implement {@code type}
     *   <li>{@code com.example.Mod::init} — static or instance method bound to a single-abstract
     *       method interface (e.g. {@code ModInitializer.onInitialize})
     *   <li>{@code com.example.Mod::INSTANCE} — public static field (Kotlin {@code object})
     * </ul>
     *
     * <p>Fabric-API modules declare dozens of {@code Class::method} entrypoints. Treating the
     * whole string as a class name produces "the class does not exist" for every one of them.
     */
    static LanguageAdapter java() {
        return new LanguageAdapter() {
            @Override
            public <T> T create(Entrypoint entrypoint, Class<T> type, ClassLoader loader) {
                String value = entrypoint.value();
                int sep = value.indexOf("::");
                if (sep < 0) {
                    return constructClass(entrypoint, value, type, loader);
                }
                String className = value.substring(0, sep);
                String member = value.substring(sep + 2);
                if (className.isBlank() || member.isBlank()) {
                    throw new EntrypointException(entrypoint,
                            "method/field reference must be ClassName::member");
                }
                Class<?> found = loadClass(entrypoint, className, loader);
                // Prefer field (Kotlin INSTANCE) when names collide with methods.
                Field field = findPublicStaticField(found, member);
                if (field != null) {
                    return readField(entrypoint, field, type);
                }
                return bindMethod(entrypoint, found, member, type, loader);
            }
        };
    }

    private static <T> T constructClass(
            Entrypoint entrypoint, String className, Class<T> type, ClassLoader loader) {
        Class<?> found = loadClass(entrypoint, className, loader);
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
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite
                    ? ite.getTargetException()
                    : e;
            throw new EntrypointException(entrypoint,
                    "its constructor threw " + cause, cause);
        }
    }

    private static Class<?> loadClass(Entrypoint entrypoint, String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            throw new EntrypointException(entrypoint,
                    "the class does not exist in this mod's jar", e);
        }
    }

    private static Field findPublicStaticField(Class<?> owner, String name) {
        try {
            Field field = owner.getField(name);
            if (Modifier.isStatic(field.getModifiers())) {
                return field;
            }
        } catch (NoSuchFieldException ignored) {
            // fall through to method binding
        }
        return null;
    }

    private static <T> T readField(Entrypoint entrypoint, Field field, Class<T> type) {
        try {
            Object value = field.get(null);
            if (value == null) {
                throw new EntrypointException(entrypoint,
                        "static field '" + field.getName() + "' is null");
            }
            if (!type.isInstance(value)) {
                throw new EntrypointException(entrypoint,
                        "static field '" + field.getName() + "' is " + value.getClass().getName()
                                + ", not " + type.getName());
            }
            return type.cast(value);
        } catch (IllegalAccessException e) {
            throw new EntrypointException(entrypoint,
                    "cannot read static field '" + field.getName() + "'", e);
        }
    }

    /**
     * Binds {@code owner::method} to a functional interface {@code type}.
     *
     * <p>Supports the shapes Fabric uses: a static no-arg method, or an instance no-arg method
     * on a freshly constructed instance. The interface must expose exactly one abstract method
     * (SAM), typically {@code onInitialize()} / {@code onInitializeClient()}.
     */
    private static <T> T bindMethod(
            Entrypoint entrypoint,
            Class<?> owner,
            String methodName,
            Class<T> type,
            ClassLoader loader) {
        Method sam = singleAbstractMethod(type);
        if (sam == null) {
            throw new EntrypointException(entrypoint,
                    "method references require a single-abstract-method interface, not "
                            + type.getName());
        }
        if (sam.getParameterCount() != 0) {
            throw new EntrypointException(entrypoint,
                    "only no-argument entrypoint methods are supported (SAM "
                            + sam.getName() + " has " + sam.getParameterCount() + " parameters)");
        }

        MethodHandle handle;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            // Static no-arg first (Fabric API modules: CommonPacketsImpl::init).
            try {
                handle = lookup.findStatic(owner, methodName, MethodType.methodType(void.class));
            } catch (NoSuchMethodException | IllegalAccessException staticMiss) {
                // Instance no-arg: construct then bind.
                Constructor<?> ctor = owner.getDeclaredConstructor();
                Object instance = ctor.newInstance();
                handle = lookup.findVirtual(owner, methodName, MethodType.methodType(void.class))
                        .bindTo(instance);
            }
        } catch (NoSuchMethodException e) {
            throw new EntrypointException(entrypoint,
                    "no public no-argument method '" + methodName + "' on " + owner.getName(), e);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite
                    ? ite.getTargetException()
                    : e;
            throw new EntrypointException(entrypoint,
                    "cannot bind method '" + methodName + "': " + cause, cause);
        }

        MethodHandle invoker = handle;
        Object proxy = Proxy.newProxyInstance(
                loader,
                new Class<?>[] {type},
                (proxyInstance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> entrypoint.value();
                            case "hashCode" -> System.identityHashCode(proxyInstance);
                            case "equals" -> proxyInstance == args[0];
                            default -> null;
                        };
                    }
                    if (method.getName().equals(sam.getName()) && method.getParameterCount() == 0) {
                        invoker.invoke();
                        return null;
                    }
                    throw new UnsupportedOperationException(
                            "entrypoint proxy does not implement " + method);
                });
        return type.cast(proxy);
    }

    private static Method singleAbstractMethod(Class<?> type) {
        if (!type.isInterface()) {
            return null;
        }
        Method found = null;
        for (Method method : type.getMethods()) {
            if (method.isDefault() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
            if (found != null) {
                return null; // not a SAM
            }
            found = method;
        }
        return found;
    }
}
