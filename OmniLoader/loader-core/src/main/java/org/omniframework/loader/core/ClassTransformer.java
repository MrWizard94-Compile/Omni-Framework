package org.omniframework.loader.core;

import java.util.List;
import java.util.Objects;

/**
 * The seam where bytecode is rewritten on its way into the JVM.
 *
 * <p>`loader-core` defines this and nothing more. The real pipeline — AOT remapping, access
 * merging, environment stripping — is `loader-transform`'s job (LDR-2), and Mixin application is
 * `loader-mixin`'s. Keeping the seam here and the implementations there is what lets the
 * classloader be understood, and tested, without any of that machinery existing.
 *
 * <p>A loader with no transformers registered runs the game unmodified. That is not a
 * placeholder state — it is the correct behaviour for vanilla, and it means the classloading
 * path is exercised in isolation before anything rewrites bytes.
 */
@FunctionalInterface
public interface ClassTransformer {

    /**
     * Transforms a class, or returns the input unchanged.
     *
     * <p>Implementations must be pure with respect to their inputs and safe to call from
     * several threads at once — classloading is parallel, and two threads can ask for different
     * classes simultaneously.
     *
     * @param binaryName the class being loaded, e.g. {@code net.example.Init}
     * @param partition where the class came from, so a transformer can skip what it does not own
     * @param bytes the class file as read
     * @return the bytes to define, which may be {@code bytes} itself
     */
    byte[] transform(String binaryName, Partition partition, byte[] bytes);

    /** The transformer that changes nothing — a vanilla launch. */
    static ClassTransformer identity() {
        return (binaryName, partition, bytes) -> bytes;
    }

    /**
     * Runs several transformers in order, each seeing the previous one's output.
     *
     * <p>Order is the caller's responsibility and it matters: remapping before Mixin means
     * mixins see official names, which is the whole reason ARCHITECTURE §6 fixes the pipeline
     * order rather than leaving it to registration accident.
     */
    static ClassTransformer chain(List<ClassTransformer> transformers) {
        List<ClassTransformer> ordered = List.copyOf(Objects.requireNonNull(transformers, "transformers"));
        if (ordered.isEmpty()) {
            return identity();
        }
        if (ordered.size() == 1) {
            return ordered.get(0);
        }
        return (binaryName, partition, bytes) -> {
            byte[] current = bytes;
            for (ClassTransformer transformer : ordered) {
                current = transformer.transform(binaryName, partition, current);
                Objects.requireNonNull(current, () -> "transformer returned null for " + binaryName);
            }
            return current;
        };
    }
}
