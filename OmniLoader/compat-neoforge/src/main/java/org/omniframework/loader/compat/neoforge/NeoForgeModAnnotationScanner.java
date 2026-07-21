package org.omniframework.loader.compat.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Finds classes annotated with {@code @Mod} / {@code @Mod("id")} in a jar (NeoForge/Forge).
 *
 * <p>FML normally discovers these via its language provider. Omni needs the class name as an
 * entrypoint value so the shared dispatcher can construct it. Scanning annotations with ASM
 * avoids loading the class (and its game dependencies) at discovery time.
 */
public final class NeoForgeModAnnotationScanner {

    private static final String MOD_DESC = "Lnet/neoforged/fml/common/Mod;";
    private static final String FORGE_MOD_DESC = "Lnet/minecraftforge/fml/common/Mod;";

    private NeoForgeModAnnotationScanner() {}

    /** Internal class names (slash-separated) of @Mod types in the jar. */
    public static List<String> findModClasses(Path jar) throws IOException {
        List<String> found = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.contains("module-info")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    Optional<String> internal = readIfMod(bytes);
                    internal.ifPresent(found::add);
                }
            }
        }
        return List.copyOf(found);
    }

    private static Optional<String> readIfMod(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        final String[] internalName = {null};
        final boolean[] isMod = {false};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            String signature,
                            String superName,
                            String[] interfaces) {
                        internalName[0] = name;
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (MOD_DESC.equals(descriptor) || FORGE_MOD_DESC.equals(descriptor)) {
                            isMod[0] = true;
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return isMod[0] ? Optional.ofNullable(internalName[0]) : Optional.empty();
    }
}
