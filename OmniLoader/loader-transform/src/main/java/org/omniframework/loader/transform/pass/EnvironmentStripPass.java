package org.omniframework.loader.transform.pass;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.omniframework.loader.transform.JarResource;
import org.omniframework.loader.transform.PassContext;
import org.omniframework.loader.transform.TransformPass;

/**
 * Removes members that belong to the other side (ARCHITECTURE §6, "environment stripping").
 *
 * <p>A dedicated server has no rendering classes. A mod that declares a method client-only and
 * calls into rendering from it would, on a server, fail with {@code NoClassDefFoundError} the
 * moment that method is verified — not when it is called. Stripping such members before the class
 * is ever loaded is what makes a single jar work on both sides.
 *
 * <p>The annotation recognised is any whose descriptor ends in {@code Environment} or
 * {@code OnlyIn} with a value naming a side. Both hosted ecosystems spell this differently and
 * both are honoured, because the alternative is a per-ecosystem pass that does the same work.
 *
 * <h2>What is deliberately NOT done</h2>
 *
 * <p>Call sites referring to a stripped member are left alone. Rewriting them would mean deciding
 * what a call to a now-absent method should do, and every available answer (throw, no-op, return
 * a default) silently changes program behaviour. A mod that calls its own client-only method from
 * common code has a bug the author must fix; hiding it here would convert a loud failure into a
 * quiet wrong answer.
 *
 * <p>A class annotated for the other side is dropped from the jar entirely, via
 * {@link Outcome#removal}. Stripping its members but keeping the class would be the worst of both
 * worlds — the class still ships, and the first thing to touch it fails on types that are not
 * there.
 *
 * <p>This pass holds no state. An earlier version reported whole-class strips through a mutable
 * field, which raced as soon as two jars were transformed in parallel through the same pipeline.
 */
public final class EnvironmentStripPass implements TransformPass {

    /** Bump when stripping rules change. */
    public static final String VERSION = "1";

    private static final Set<String> ANNOTATION_SUFFIXES =
            Set.of("Environment;", "OnlyIn;", "EnvType;", "Dist;");

    @Override
    public String id() {
        return "environment-strip";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Optional<Outcome> transform(JarResource resource, PassContext context) {
        if (!resource.isClass()) {
            return Optional.empty();
        }
        String keep = context.side().lowerName();
        String drop = context.side().opposite().lowerName();

        ClassReader reader = new ClassReader(resource.content());
        SideScanner scanner = new SideScanner(drop, keep);
        reader.accept(scanner, ClassReader.SKIP_CODE);
        if (scanner.classIsForOtherSide()) {
            return Optional.of(Outcome.removal(
                    "removed: declared " + drop + "-only, building for " + keep));
        }
        if (scanner.membersToStrip().isEmpty()) {
            return Optional.empty();
        }

        ClassWriter writer = new ClassWriter(0);
        List<String> removed = new ArrayList<>();
        reader.accept(new StrippingVisitor(writer, scanner.membersToStrip(), removed), 0);
        return Optional.of(Outcome.of(writer.toByteArray(), removed));
    }

    /** Identifies which members carry an annotation naming the other side. */
    private static final class SideScanner extends ClassVisitor {

        private final String dropSide;
        private final String keepSide;
        private final Set<String> membersToStrip = new LinkedHashSet<>();
        private boolean classForOtherSide;

        SideScanner(String dropSide, String keepSide) {
            super(Opcodes.ASM9);
            this.dropSide = dropSide;
            this.keepSide = keepSide;
        }

        boolean classIsForOtherSide() {
            return classForOtherSide;
        }

        Set<String> membersToStrip() {
            return membersToStrip;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!isSideAnnotation(descriptor)) {
                return null;
            }
            return new SideValueVisitor(dropSide, keepSide, () -> classForOtherSide = true);
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object value) {
            String key = memberKey(name, descriptor);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    if (!isSideAnnotation(annotation)) {
                        return null;
                    }
                    return new SideValueVisitor(dropSide, keepSide, () -> membersToStrip.add(key));
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            String key = memberKey(name, descriptor);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    if (!isSideAnnotation(annotation)) {
                        return null;
                    }
                    return new SideValueVisitor(dropSide, keepSide, () -> membersToStrip.add(key));
                }
            };
        }

        private static boolean isSideAnnotation(String descriptor) {
            return ANNOTATION_SUFFIXES.stream().anyMatch(descriptor::endsWith);
        }
    }

    /**
     * Reads the side named by an annotation value.
     *
     * <p>The value arrives as an enum constant whose name is {@code CLIENT} or {@code SERVER}
     * (Fabric) or {@code DEDICATED_SERVER} (NeoForge). Matching is case-insensitive and by
     * containment, because the exact constant names differ between ecosystems while the intent
     * does not.
     */
    private static final class SideValueVisitor extends AnnotationVisitor {

        private final String dropSide;
        private final String keepSide;
        private final Runnable onOtherSide;

        SideValueVisitor(String dropSide, String keepSide, Runnable onOtherSide) {
            super(Opcodes.ASM9);
            this.dropSide = dropSide;
            this.keepSide = keepSide;
            this.onOtherSide = onOtherSide;
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            String normalised = value.toLowerCase(Locale.ROOT);
            if (normalised.contains(dropSide) && !normalised.contains(keepSide)) {
                onOtherSide.run();
            }
        }
    }

    /** Omits the members the scanner marked. */
    private static final class StrippingVisitor extends ClassVisitor {

        private final Set<String> toStrip;
        private final List<String> removed;

        StrippingVisitor(ClassVisitor delegate, Set<String> toStrip, List<String> removed) {
            super(Opcodes.ASM9, delegate);
            this.toStrip = toStrip;
            this.removed = removed;
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object value) {
            if (toStrip.contains(memberKey(name, descriptor))) {
                removed.add("stripped field " + name + ":" + descriptor);
                return null;
            }
            return super.visitField(access, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            if (toStrip.contains(memberKey(name, descriptor))) {
                removed.add("stripped method " + name + descriptor);
                return null;
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }

    private static String memberKey(String name, String descriptor) {
        return name + descriptor;
    }
}
