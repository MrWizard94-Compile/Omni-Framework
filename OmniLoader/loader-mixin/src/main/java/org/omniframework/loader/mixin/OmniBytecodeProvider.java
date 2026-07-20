package org.omniframework.loader.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.service.IClassBytecodeProvider;

/**
 * Supplies Mixin with the bytecode of classes it is about to modify — "cache-or-live"
 * (ARCHITECTURE §8).
 *
 * <h2>Which bytes Mixin must see</h2>
 *
 * <p>This is the subtle part of the whole integration. By the time Mixin asks for a class, the
 * AOT pipeline may already have rewritten it: remapped to official names, access-widened, members
 * stripped. Mixin must be shown the <em>post-pipeline</em> bytes, because those are what will
 * actually be defined, and its injection points are resolved against member names and signatures.
 *
 * <p>Handing it the original jar bytes instead would break in a way that reads as an author
 * error: injection points would be resolved against intermediary names that no longer exist, and
 * Mixin would report "target method not found" naming a method that is right there in the source.
 * So lookups go through the transformed artifact first, and fall back to the raw source only for
 * classes the pipeline never touched.
 *
 * <p>Nodes are re-read per request rather than cached. Mixin mutates the {@link ClassNode} it is
 * given — that is how it applies a mixin — so handing out a shared instance would let one mod's
 * modifications leak into what the next mod sees.
 */
public final class OmniBytecodeProvider implements IClassBytecodeProvider {

    private final Function<String, Optional<byte[]>> transformed;
    private final Function<String, Optional<byte[]>> original;
    private final AtomicLong fromCache = new AtomicLong();
    private final AtomicLong fromSource = new AtomicLong();

    /**
     * Builds a provider over two lookups.
     *
     * @param transformed post-pipeline bytes by internal name, empty if the pipeline produced none
     * @param original raw bytes by internal name, for classes the pipeline never touched
     */
    public OmniBytecodeProvider(
            Function<String, Optional<byte[]>> transformed,
            Function<String, Optional<byte[]>> original) {
        this.transformed = Objects.requireNonNull(transformed, "transformed");
        this.original = Objects.requireNonNull(original, "original");
    }

    /** A provider backed by a classloader's resources — the fallback for a plain boot. */
    public static OmniBytecodeProvider ofClassLoader(ClassLoader loader) {
        Objects.requireNonNull(loader, "loader");
        Function<String, Optional<byte[]>> lookup = internalName -> {
            try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
                return in == null ? Optional.empty() : Optional.of(in.readAllBytes());
            } catch (IOException e) {
                return Optional.empty();
            }
        };
        return new OmniBytecodeProvider(ignored -> Optional.empty(), lookup);
    }

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers)
            throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
            throws ClassNotFoundException, IOException {
        String internalName = internal(name);

        // `runTransformers == false` is Mixin asking for the class as it exists on disk, which
        // it uses for hierarchy questions where the pipeline's edits are irrelevant. Honouring
        // the flag matters: answering with transformed bytes there can make a superclass look
        // like it has members it did not originally declare.
        Optional<byte[]> bytes = runTransformers
                ? transformed.apply(internalName).map(this::countCache)
                : Optional.empty();
        if (bytes.isEmpty()) {
            bytes = original.apply(internalName).map(this::countSource);
        }
        byte[] content = bytes.orElseThrow(() -> new ClassNotFoundException(
                name + " is not available to Mixin — neither the transform cache nor the "
                        + "classloader could supply its bytecode"));

        ClassNode node = new ClassNode();
        new ClassReader(content).accept(node, readerFlags);
        return node;
    }

    private byte[] countCache(byte[] content) {
        fromCache.incrementAndGet();
        return content;
    }

    private byte[] countSource(byte[] content) {
        fromSource.incrementAndGet();
        return content;
    }

    /** How many lookups were answered from transformed artifacts. */
    public long servedFromCache() {
        return fromCache.get();
    }

    /** How many lookups fell back to untransformed sources. */
    public long servedFromSource() {
        return fromSource.get();
    }

    /** Mixin passes dotted binary names; jar entries and the pipeline use internal names. */
    private static String internal(String name) {
        return Objects.requireNonNull(name, "name").replace('.', '/');
    }
}
