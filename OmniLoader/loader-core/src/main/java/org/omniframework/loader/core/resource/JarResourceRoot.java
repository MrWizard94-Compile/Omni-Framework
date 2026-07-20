package org.omniframework.loader.core.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * A jar on disk.
 *
 * <p>The jar is opened once and held open for the loader's lifetime. That is deliberate: opening
 * and closing per class read would be slower and, worse, would let a jar be swapped underneath a
 * partially-loaded mod — a class of bug where half a mod comes from one build and half from
 * another, producing failures that are impossible to reproduce.
 *
 * <p>{@link JarFile} is documented as thread-safe for reads, which is what {@link ResourceRoot}
 * requires.
 */
public final class JarResourceRoot implements ResourceRoot {

    private final Path location;
    private final JarFile jar;
    private final Set<String> packages;

    private JarResourceRoot(Path location, JarFile jar, Set<String> packages) {
        this.location = location;
        this.jar = jar;
        this.packages = Set.copyOf(packages);
    }

    /**
     * Opens a jar and indexes the packages it defines classes in.
     *
     * @throws IOException if the file is missing, unreadable, or not a valid archive
     */
    public static JarResourceRoot open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        JarFile jar = new JarFile(path.toFile(), false, JarFile.OPEN_READ);
        try {
            Set<String> packages = new HashSet<>();
            jar.stream()
                    .map(JarEntry::getName)
                    .filter(name -> name.endsWith(".class"))
                    .forEach(name -> {
                        int lastSlash = name.lastIndexOf('/');
                        packages.add(lastSlash < 0 ? "" : name.substring(0, lastSlash));
                    });
            return new JarResourceRoot(path, jar, packages);
        } catch (RuntimeException failure) {
            // Do not leak the open handle if indexing blows up.
            try {
                jar.close();
            } catch (IOException ignored) {
                // The original failure is the interesting one.
            }
            throw failure;
        }
    }

    @Override
    public Path location() {
        return location;
    }

    @Override
    public Optional<byte[]> read(String path) {
        JarEntry entry = jar.getJarEntry(Objects.requireNonNull(path, "path"));
        if (entry == null || entry.isDirectory()) {
            return Optional.empty();
        }
        try (InputStream stream = jar.getInputStream(entry)) {
            return Optional.of(stream.readAllBytes());
        } catch (IOException unreadable) {
            // A jar that reports an entry it cannot then read is corrupt. Returning empty
            // would present as "class not found", which sends the user hunting for a missing
            // dependency instead of a damaged download.
            throw new CorruptResourceException(location, path, unreadable);
        }
    }

    @Override
    public boolean contains(String path) {
        JarEntry entry = jar.getJarEntry(Objects.requireNonNull(path, "path"));
        return entry != null && !entry.isDirectory();
    }

    @Override
    public Set<String> packages() {
        return packages;
    }

    /** The jar's manifest, when it has one — the source of {@code Implementation-Version}. */
    public Optional<Manifest> manifest() {
        try {
            return Optional.ofNullable(jar.getManifest());
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    @Override
    public void close() throws IOException {
        jar.close();
    }

    @Override
    public String toString() {
        return location.toString();
    }
}
