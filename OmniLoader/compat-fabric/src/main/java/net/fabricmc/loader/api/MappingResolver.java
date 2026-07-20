package net.fabricmc.loader.api;

import java.util.Collection;

/**
 * Translates names between mapping namespaces at runtime.
 *
 * <p>Mods use this for reflection: a name compiled against intermediary has to become the name
 * the running game actually uses. OmniLoader's runtime namespace is Mojang official (LDR-3), so
 * this resolves intermediary→official using the same mapping artifact the AOT remap pass used —
 * deliberately the same source, because a mod reflecting on a name the transformer mapped
 * differently would fail in a way that looks like the mod is wrong.
 */
public interface MappingResolver {

    /** Namespaces this resolver knows. */
    Collection<String> getNamespaces();

    /** The namespace the running game is in. */
    String getCurrentRuntimeNamespace();

    /** Maps a class name from {@code namespace} into the runtime namespace. */
    String mapClassName(String namespace, String className);

    /** Maps a runtime class name back into {@code namespace}. */
    String unmapClassName(String targetNamespace, String className);

    /** Maps a field name declared on {@code owner}. */
    String mapFieldName(String namespace, String owner, String name, String descriptor);

    /** Maps a method name declared on {@code owner}. */
    String mapMethodName(String namespace, String owner, String name, String descriptor);
}
