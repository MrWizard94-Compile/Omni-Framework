package org.omniframework.loader.transform.pass;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * Applies the merged access changes to classes (ARCHITECTURE §6, "access modification merge").
 *
 * <p>The changes are supplied already merged — see {@link AccessChange#merge} for why a merge
 * rather than a sequence of overwrites. This pass only rewrites flag words.
 *
 * <h2>Why the pass runs after remapping</h2>
 *
 * <p>Access files name members in the ecosystem's own namespace, and by the time this pass runs
 * the classes have been rewritten into official names. The changes must therefore already be
 * expressed in official names too. Constructing this pass with changes in the wrong namespace
 * produces no error and no effect — every lookup simply misses — so the caller owns that
 * translation, and {@link #changeCount()} exists to make "my widener did nothing" visible.
 */
public final class AccessMergePass implements TransformPass {

    /** Bump when flag computation changes. */
    public static final String VERSION = "1";

    private final List<AccessChange> changes;

    /** Builds a pass applying {@code changes}, which must already be merged and in-namespace. */
    public AccessMergePass(List<AccessChange> changes) {
        this.changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }

    @Override
    public String id() {
        return "access-merge";
    }

    @Override
    public String version() {
        return VERSION;
    }

    /** How many changes this pass carries — zero means it will do nothing to any class. */
    public int changeCount() {
        return changes.size();
    }

    @Override
    public Optional<Outcome> transform(JarResource resource, PassContext context) {
        if (!resource.isClass() || changes.isEmpty()) {
            return Optional.empty();
        }
        String internalName = resource.className();
        List<AccessChange> relevant = changes.stream()
                .filter(change -> change.owner().equals(internalName))
                .toList();
        if (relevant.isEmpty()) {
            return Optional.empty();
        }

        ClassReader reader = new ClassReader(resource.content());
        ClassWriter writer = new ClassWriter(0);
        List<String> applied = new ArrayList<>();
        reader.accept(new AccessVisitor(writer, internalName, relevant, applied), 0);
        if (applied.isEmpty()) {
            // The class is named by a change but the specific member is not present. That is
            // worth surfacing: it usually means an access file written against a different game
            // version, which will fail confusingly later.
            return Optional.empty();
        }
        return Optional.of(Outcome.of(writer.toByteArray(), applied));
    }

    /** Rewrites access flags on the class and any of its members that are targeted. */
    private static final class AccessVisitor extends ClassVisitor {

        private final String internalName;
        private final List<AccessChange> changes;
        private final List<String> applied;

        AccessVisitor(
                ClassVisitor delegate,
                String internalName,
                List<AccessChange> changes,
                List<String> applied) {
            super(Opcodes.ASM9, delegate);
            this.internalName = internalName;
            this.changes = changes;
            this.applied = applied;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            int updated = access;
            for (AccessChange change : changes) {
                if (change.appliesToClass(internalName)) {
                    updated = change.apply(updated);
                    applied.add(change.describe());
                }
            }
            super.visit(version, updated, name, signature, superName, interfaces);
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object value) {
            int updated = access;
            for (AccessChange change : changes) {
                if (change.appliesToField(internalName, name, descriptor)) {
                    updated = change.apply(updated);
                    applied.add(change.describe());
                }
            }
            return super.visitField(updated, name, descriptor, signature, value);
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            int updated = access;
            for (AccessChange change : changes) {
                if (change.appliesToMethod(internalName, name, descriptor)) {
                    updated = change.apply(updated);
                    applied.add(change.describe());
                }
            }
            // A private method made public must also stop being invoked with INVOKESPECIAL by
            // its own class, but ASM leaves existing call sites alone and the JVM accepts
            // INVOKESPECIAL to a public method in the same class, so no call-site rewrite is
            // needed here. Cross-class calls were already compiled against the widened form.
            return super.visitMethod(updated, name, descriptor, signature, exceptions);
        }
    }
}
