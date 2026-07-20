package org.omniframework.loader.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles Java source to bytecode in memory, using the JDK's own compiler.
 *
 * <p>Test-only. It exists so classloading tests can be written against source a reader can
 * actually read, instead of against opaque checked-in class files.
 */
final class InMemoryCompiler {

    private InMemoryCompiler() {
        // Static helper.
    }

    /**
     * Compiles the given sources.
     *
     * @param sources binary class name → source text
     * @return binary class name → class bytes, including any nested classes produced
     * @throws IllegalStateException if compilation fails, with the diagnostics in the message
     */
    static Map<String, byte[]> compile(Map<String, String> sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "no system Java compiler — tests must run on a JDK, not a JRE");
        }
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, source) -> units.add(new SourceFile(name, source)));

        Map<String, byte[]> output = new LinkedHashMap<>();
        StringWriterCollector diagnostics = new StringWriterCollector();
        try (StandardJavaFileManager standard =
                compiler.getStandardFileManager(diagnostics, null, null)) {
            JavaFileManager manager = new ClassCollector(standard, output);
            boolean ok = compiler
                    .getTask(null, manager, diagnostics, List.of("-Xlint:none"), null, units)
                    .call();
            if (!ok) {
                throw new IllegalStateException("test source failed to compile:\n" + diagnostics);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("could not run the compiler", failure);
        }
        return output;
    }

    /** A source file held in memory. */
    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        SourceFile(String binaryName, String source) {
            super(URI.create("string:///" + binaryName.replace('.', '/') + ".java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    /** Captures compiled classes instead of writing them to disk. */
    private static final class ClassCollector extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, byte[]> output;

        ClassCollector(JavaFileManager delegate, Map<String, byte[]> output) {
            super(delegate);
            this.output = output;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
            return new SimpleJavaFileObject(
                    URI.create("bytes:///" + className.replace('.', '/') + ".class"), kind) {
                @Override
                public OutputStream openOutputStream() {
                    return new ByteArrayOutputStream() {
                        @Override
                        public void close() {
                            output.put(className, toByteArray());
                        }
                    };
                }
            };
        }
    }

    /** Collects diagnostics into a readable block for failure messages. */
    private static final class StringWriterCollector
            implements javax.tools.DiagnosticListener<JavaFileObject> {
        private final StringBuilder text = new StringBuilder();

        @Override
        public void report(javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
            text.append(diagnostic).append(System.lineSeparator());
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
