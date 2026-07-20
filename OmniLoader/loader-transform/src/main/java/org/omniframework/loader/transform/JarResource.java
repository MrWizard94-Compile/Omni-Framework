package org.omniframework.loader.transform;

import java.util.Objects;

/**
 * One entry in a jar, as the pipeline sees it.
 *
 * <p>Content is held in memory. Mod jars are tens of megabytes and the pipeline is already
 * rewriting them; streaming would add complexity for a working set that is small next to the
 * game's own heap.
 */
public record JarResource(String name, byte[] content) {

    private static final String CLASS_SUFFIX = ".class";

    /** Canonical constructor; defensively copies so a pass cannot mutate a shared array. */
    public JarResource {
        Objects.requireNonNull(name, "name");
        content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }

    /** The content without copying — for readers that will not modify it. */
    byte[] contentDirect() {
        return content;
    }

    /** Whether this entry is a class file. */
    public boolean isClass() {
        return name.endsWith(CLASS_SUFFIX);
    }

    /**
     * The internal class name ({@code a/b/C}), if this is a class file.
     *
     * @throws IllegalStateException if this is not a class file
     */
    public String className() {
        if (!isClass()) {
            throw new IllegalStateException(name + " is not a class file");
        }
        return name.substring(0, name.length() - CLASS_SUFFIX.length());
    }

    /** How many bytes this entry holds. */
    public int size() {
        return content.length;
    }

    /** This entry with different content, keeping the name. */
    public JarResource withContent(byte[] replacement) {
        return new JarResource(name, replacement);
    }

    // Records generate identity-based equals/hashCode for array components, which would make
    // two resources with identical bytes compare unequal. Content equality is what callers and
    // tests mean, so both are defined explicitly.

    @Override
    public boolean equals(Object other) {
        return other instanceof JarResource resource
                && name.equals(resource.name)
                && java.util.Arrays.equals(content, resource.content);
    }

    @Override
    public int hashCode() {
        return 31 * name.hashCode() + java.util.Arrays.hashCode(content);
    }

    @Override
    public String toString() {
        return "JarResource[" + name + ", " + content.length + " bytes]";
    }
}
