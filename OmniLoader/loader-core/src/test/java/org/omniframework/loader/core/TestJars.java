package org.omniframework.loader.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Builds real jars containing real compiled classes, for tests that must exercise actual
 * classloading.
 *
 * <p>Classes are compiled at test time with the JDK's own compiler rather than checked in as
 * bytecode fixtures. Fixture bytecode rots silently: it keeps loading long after the source it
 * claims to represent has changed, and the day it matters is the day a classloading test passes
 * against a class nobody has read in a year.
 */
final class TestJars {

    private TestJars() {
        // Static helpers only.
    }

    /** A jar builder: add sources, get a jar with their compiled classes. */
    static final class Builder {
        private final Map<String, String> sources = new LinkedHashMap<>();
        private final Map<String, byte[]> extras = new LinkedHashMap<>();

        /** Adds a Java source; {@code binaryName} must match the declared class. */
        Builder source(String binaryName, String source) {
            sources.put(binaryName, source);
            return this;
        }

        /** Adds a non-class entry, e.g. a manifest or a config file. */
        Builder resource(String path, String contents) {
            extras.put(path, contents.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        /** Compiles and writes the jar. */
        Path writeTo(Path jar) throws IOException {
            Map<String, byte[]> classes = InMemoryCompiler.compile(sources);
            Files.createDirectories(jar.getParent());
            try (OutputStream out = Files.newOutputStream(jar);
                    JarOutputStream jarOut = new JarOutputStream(out)) {
                for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                    write(jarOut, entry.getKey().replace('.', '/') + ".class", entry.getValue());
                }
                for (Map.Entry<String, byte[]> entry : extras.entrySet()) {
                    write(jarOut, entry.getKey(), entry.getValue());
                }
            }
            return jar;
        }

        /** Compiles and writes loose classes into a directory. */
        Path writeToDirectory(Path directory) throws IOException {
            Map<String, byte[]> classes = InMemoryCompiler.compile(sources);
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                Path file = directory.resolve(entry.getKey().replace('.', '/') + ".class");
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
            for (Map.Entry<String, byte[]> entry : extras.entrySet()) {
                Path file = directory.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
            return directory;
        }

        private static void write(JarOutputStream jar, String path, byte[] bytes) throws IOException {
            ZipEntry entry = new ZipEntry(path);
            // A fixed time keeps generated jars byte-identical between runs.
            entry.setTime(0L);
            jar.putNextEntry(entry);
            jar.write(bytes);
            jar.closeEntry();
        }
    }

    /** Starts a new jar. */
    static Builder builder() {
        return new Builder();
    }

}
