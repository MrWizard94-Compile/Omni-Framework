package org.omniframework.loader.core.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A directory of loose classes — the development-loop root.
 *
 * <p>Paths are resolved and then checked to still be inside the root, so a resource name
 * containing {@code ..} cannot read a file outside it. Class names come from mod metadata and
 * from bytecode, both of which are untrusted input (SOUL §9); a loader that will read any path
 * a class name asks for is an arbitrary-file-read primitive.
 */
public final class DirectoryResourceRoot implements ResourceRoot {

    private final Path root;
    private final Set<String> packages;

    private DirectoryResourceRoot(Path root, Set<String> packages) {
        this.root = root;
        this.packages = Set.copyOf(packages);
    }

    /**
     * Opens a directory and indexes the packages it defines classes in.
     *
     * @throws IOException if the path is not a readable directory
     */
    public static DirectoryResourceRoot open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        Path root = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("not a directory: " + root);
        }
        Set<String> packages = new HashSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".class"))
                    .forEach(file -> {
                        Path relative = root.relativize(file).getParent();
                        packages.add(relative == null ? "" : relative.toString().replace('\\', '/'));
                    });
        }
        return new DirectoryResourceRoot(root, packages);
    }

    @Override
    public Path location() {
        return root;
    }

    @Override
    public Optional<byte[]> read(String path) {
        Optional<Path> resolved = resolveInside(path);
        if (resolved.isEmpty() || !Files.isRegularFile(resolved.get())) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(resolved.get()));
        } catch (IOException unreadable) {
            throw new CorruptResourceException(root, path, unreadable);
        }
    }

    @Override
    public boolean contains(String path) {
        return resolveInside(path).filter(Files::isRegularFile).isPresent();
    }

    @Override
    public Set<String> packages() {
        return packages;
    }

    @Override
    public void close() {
        // Nothing is held open; the method exists so callers can treat every root alike.
    }

    /** Resolves a resource path, rejecting anything that escapes the root. */
    private Optional<Path> resolveInside(String path) {
        Objects.requireNonNull(path, "path");
        if (path.isEmpty()) {
            return Optional.empty();
        }
        Path candidate;
        try {
            candidate = root.resolve(path).normalize();
        } catch (RuntimeException invalid) {
            // e.g. an illegal character for this filesystem.
            return Optional.empty();
        }
        return candidate.startsWith(root) ? Optional.of(candidate) : Optional.empty();
    }

    @Override
    public String toString() {
        return root.toString();
    }
}
