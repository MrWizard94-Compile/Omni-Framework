package org.omniframework.loader.transform;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Generates real class files for the transform tests.
 *
 * <p>The passes rewrite bytecode, so testing them against hand-written byte arrays would prove
 * nothing — the interesting failures (frames that no longer verify, a rename that misses an
 * inherited member) only appear in classes the JVM will actually load. Everything here produces
 * genuinely loadable classes, and {@link Loader} loads them so a test can assert on behaviour
 * rather than on bytes.
 */
final class Bytecode {

    private Bytecode() {}

    /** A class with a single {@code int value()} method returning a constant. */
    static byte[] valueClass(String internalName, int value) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultConstructor(writer, "java/lang/Object");
        MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A base class declaring {@code int inherited()}. */
    static byte[] baseClass(String internalName, String methodName, int value) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultConstructor(writer, "java/lang/Object");
        MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()I", null, null);
        method.visitCode();
        method.visitLdcInsn(value);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A subclass of {@code superName} that adds nothing. */
    static byte[] subclass(String internalName, String superName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, superName, null);
        defaultConstructor(writer, superName);
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * A class whose {@code int call()} invokes {@code methodName} on {@code targetType}.
     *
     * <p>This is the shape that catches hierarchy-unaware remapping: the call site names
     * {@code targetType}, but the method may be declared on its superclass.
     */
    static byte[] callerClass(
            String internalName, String targetType, String methodName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultConstructor(writer, "java/lang/Object");
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "call", "()I", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, targetType);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, targetType, "<init>", "()V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, targetType, methodName, "()I", false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * A class with a branch, so the writer must merge two types into a frame.
     *
     * <p>Frame merging is what forces {@code getCommonSuperClass} to be answered from the
     * hierarchy index rather than by loading classes — see {@code RemapPass}.
     */
    static byte[] branchingClass(String internalName, String typeA, String typeB) {
        // The fixture hits the same wall the pass does: COMPUTE_FRAMES must merge typeA and
        // typeB, and ASM's default answer is to load them — which fails, because they are
        // generated classes that exist only as byte arrays. Both branches are returned as
        // Object here, so Object is the correct merge.
        ClassWriter writer = new ObjectMergingWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultConstructor(writer, "java/lang/Object");
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "pick", "(Z)Ljava/lang/Object;", null, null);
        method.visitCode();
        org.objectweb.asm.Label otherwise = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        method.visitVarInsn(Opcodes.ILOAD, 1);
        method.visitJumpInsn(Opcodes.IFEQ, otherwise);
        method.visitTypeInsn(Opcodes.NEW, typeA);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, typeA, "<init>", "()V", false);
        method.visitJumpInsn(Opcodes.GOTO, done);
        method.visitLabel(otherwise);
        method.visitTypeInsn(Opcodes.NEW, typeB);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, typeB, "<init>", "()V", false);
        method.visitLabel(done);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class with a private field and method, plus a final class flag, for access tests. */
    static byte[] restrictedClass(String internalName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                internalName, null, "java/lang/Object", null);
        defaultConstructor(writer, "java/lang/Object");
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "secret", "I", null, null)
                .visitEnd();
        MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_PRIVATE, "hidden", "()I", null, null);
        method.visitCode();
        method.visitLdcInsn(7);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** A class carrying a side annotation on the class, a field, and a method. */
    static byte[] sidedClass(
            String internalName, String annotationDescriptor, String classSide,
            String fieldSide, String methodSide) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        if (classSide != null) {
            annotate(writer.visitAnnotation(annotationDescriptor, true), annotationDescriptor,
                    classSide);
        }
        defaultConstructor(writer, "java/lang/Object");

        var field = writer.visitField(Opcodes.ACC_PUBLIC, "sidedField", "I", null, null);
        if (fieldSide != null) {
            annotate(field.visitAnnotation(annotationDescriptor, true), annotationDescriptor,
                    fieldSide);
        }
        field.visitEnd();

        MethodVisitor method =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "sidedMethod", "()I", null, null);
        if (methodSide != null) {
            annotate(method.visitAnnotation(annotationDescriptor, true), annotationDescriptor,
                    methodSide);
        }
        method.visitCode();
        method.visitLdcInsn(1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        MethodVisitor common =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "commonMethod", "()I", null, null);
        common.visitCode();
        common.visitLdcInsn(2);
        common.visitInsn(Opcodes.IRETURN);
        common.visitMaxs(0, 0);
        common.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void annotate(
            org.objectweb.asm.AnnotationVisitor annotation, String descriptor, String side) {
        annotation.visitEnum("value", descriptor, side);
        annotation.visitEnd();
    }

    private static void defaultConstructor(ClassWriter writer, String superName) {
        MethodVisitor constructor =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
    }

    /** Writes a jar containing the given entries. */
    static Path jarOf(Path file, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return file;
    }

    /** Convenience: a jar of classes keyed by internal name. */
    static Path classJar(Path file, Map<String, byte[]> classes) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        classes.forEach((name, bytes) -> entries.put(name + ".class", bytes));
        return jarOf(file, entries);
    }

    /** The binary name ({@code a.b.C}) for an internal name ({@code a/b/C}). */
    static String binaryName(String internalName) {
        return Type.getObjectType(internalName).getClassName();
    }

    /**
     * A writer that merges any two reference types to {@code Object} without loading them.
     *
     * <p>Only correct for fixtures whose merge sites are typed as {@code Object}, which is all
     * of them here. The production pass answers properly from the hierarchy index.
     */
    private static final class ObjectMergingWriter extends ClassWriter {

        ObjectMergingWriter(int flags) {
            super(flags);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return type1.equals(type2) ? type1 : "java/lang/Object";
        }
    }

    /** Loads generated classes so tests can assert on behaviour, not bytes. */
    static final class Loader extends ClassLoader {

        private final Map<String, byte[]> classes = new LinkedHashMap<>();

        Loader() {
            super(Loader.class.getClassLoader());
        }

        /** Registers a class by internal name. */
        Loader with(String internalName, byte[] bytes) {
            classes.put(binaryName(internalName), bytes);
            return this;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                throw new ClassNotFoundException(name);
            }
            return defineClass(name, bytes, 0, bytes.length);
        }

        /** Loads a class by internal name. */
        Class<?> load(String internalName) throws ClassNotFoundException {
            return loadClass(binaryName(internalName));
        }
    }
}
