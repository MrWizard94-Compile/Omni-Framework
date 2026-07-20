package org.omniframework.loader.transform.pass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.omniframework.loader.transform.JarResource;
import org.omniframework.loader.transform.PassContext;
import org.omniframework.loader.transform.TransformException;
import org.omniframework.loader.transform.TransformPass;
import org.omniframework.loader.transform.mapping.ClassIndex;
import org.omniframework.loader.transform.mapping.HierarchyRemapper;
import org.omniframework.loader.transform.mapping.MappingSet;

/**
 * Rewrites a class into the official namespace (LDR-3, D2).
 *
 * <p>OmniLoader runs on Mojang official names. Fabric-family mods are compiled against
 * intermediary names, so without this pass every game reference in such a jar resolves against
 * nothing. Native Omni mods are already official-named and are handed an identity mapping, which
 * this pass detects and skips outright rather than rewriting a jar into itself.
 *
 * <h2>Frames are recomputed, not copied</h2>
 *
 * <p>Stack map frames name types. Renaming a type without updating the frames that mention it
 * produces a class that fails verification at load with {@code VerifyError} — and the error names
 * the <em>frame</em>, not the rename, so the cause is far from the symptom.
 *
 * <p>{@link ClassWriter#COMPUTE_FRAMES} recomputes them, but to do so it must answer "what is the
 * common supertype of A and B", and its default implementation answers by loading both classes
 * with the JVM. During transformation those classes are not loadable — that is the entire point
 * of transforming them — so {@link #getCommonSuperClass} is overridden to answer from the
 * hierarchy index instead. Without that override this pass throws
 * {@code TypeNotPresentException} on the first non-trivial branch merge.
 */
public final class RemapPass implements TransformPass {

    /** Bump when the remapping algorithm changes output for unchanged input. */
    public static final String VERSION = "1";

    @Override
    public String id() {
        return "remap-official";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Optional<Outcome> transform(JarResource resource, PassContext context) {
        if (!resource.isClass() || context.mappings().isIdentity()) {
            return Optional.empty();
        }
        MappingSet mappings = context.mappings();
        ClassIndex hierarchy = context.hierarchy();
        CountingRemapper remapper = new CountingRemapper(mappings, hierarchy);

        byte[] output;
        try {
            ClassReader reader = new ClassReader(resource.content());
            ClassWriter writer = new HierarchyAwareWriter(hierarchy, ClassWriter.COMPUTE_FRAMES);
            reader.accept(new ClassRemapper(writer, remapper), ClassReader.EXPAND_FRAMES);
            output = writer.toByteArray();
        } catch (RuntimeException e) {
            throw new TransformException(
                    "cannot remap " + resource.name() + " in " + context.jarName(), e);
        }

        if (!remapper.changedAnything()) {
            // The class mentions nothing that is mapped. Returning empty keeps it out of the
            // audit and leaves the original bytes in the jar rather than a re-encoded copy.
            return Optional.empty();
        }
        return Optional.of(Outcome.of(output, remapper.notes()));
    }

    /** A remapper that records what it renamed, so the audit can say more than "changed". */
    private static final class CountingRemapper extends HierarchyRemapper {

        private int classRenames;
        private int fieldRenames;
        private int methodRenames;
        private String originalName;
        private String mappedName;

        CountingRemapper(MappingSet mappings, ClassIndex hierarchy) {
            super(mappings, hierarchy);
        }

        @Override
        public String map(String internalName) {
            String mapped = super.map(internalName);
            if (mapped != null && !mapped.equals(internalName)) {
                classRenames++;
                if (originalName == null) {
                    // The first type the reader visits is the class being written.
                    originalName = internalName;
                    mappedName = mapped;
                }
            }
            return mapped;
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            String mapped = super.mapFieldName(owner, name, descriptor);
            if (!mapped.equals(name)) {
                fieldRenames++;
            }
            return mapped;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            String mapped = super.mapMethodName(owner, name, descriptor);
            if (!mapped.equals(name)) {
                methodRenames++;
            }
            return mapped;
        }

        boolean changedAnything() {
            return classRenames > 0 || fieldRenames > 0 || methodRenames > 0;
        }

        List<String> notes() {
            List<String> notes = new ArrayList<>();
            if (originalName != null) {
                notes.add("renamed " + originalName + " → " + mappedName);
            }
            notes.add(classRenames + " type references, " + fieldRenames + " fields, "
                    + methodRenames + " methods remapped");
            return notes;
        }
    }

    /**
     * A {@link ClassWriter} that answers supertype questions from the index, not the JVM.
     *
     * <p>See the class note: the default implementation loads classes, which cannot work for
     * classes that are mid-transformation.
     */
    private static final class HierarchyAwareWriter extends ClassWriter {

        private static final String OBJECT = "java/lang/Object";

        private final ClassIndex hierarchy;

        HierarchyAwareWriter(ClassIndex hierarchy, int flags) {
            super(flags);
            this.hierarchy = hierarchy;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) {
                return type1;
            }
            if (hierarchy.isAssignableFrom(type1, type2)) {
                return type1;
            }
            if (hierarchy.isAssignableFrom(type2, type1)) {
                return type2;
            }
            for (String ancestor : hierarchy.ancestorsOf(type1)) {
                if (hierarchy.isAssignableFrom(ancestor, type2) && !isInterface(ancestor)) {
                    return ancestor;
                }
            }
            // Object is always a correct answer, if a loose one: a widened frame type verifies,
            // it just permits more than strictly necessary. Guessing wrong in the other
            // direction would produce a VerifyError, so the conservative answer is the right
            // default when the index cannot see far enough.
            return OBJECT;
        }

        private boolean isInterface(String internalName) {
            return hierarchy.get(internalName).map(ClassIndex.Entry::isInterface).orElse(false);
        }
    }
}
