package org.omniframework.loader.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.omniframework.api.ModInitializer;
import org.omniframework.loader.core.ClassTransformer;
import org.omniframework.loader.core.OmniTransformingClassLoader;
import org.omniframework.loader.core.Partition;
import org.omniframework.loader.core.PartitionRegistry;
import org.omniframework.loader.core.resource.JarResourceRoot;

/**
 * The property the classloader-parent correction (ADR-0021) exists to guarantee: a mod class
 * loaded through the transforming loader shares the API's identity, while the application
 * classpath stays sealed out.
 *
 * <p>This is the test that would have caught the gap B1 surfaced. It builds a real class that
 * {@code implements org.omniframework.api.ModInitializer}, loads it through the transforming
 * loader, and asserts the loaded class is assignable to the <em>same</em> {@link ModInitializer}
 * this test holds — then instantiates it and calls it, which is exactly what the entrypoint
 * dispatcher does at boot. Before the fix, the mod class could not even be defined: the parent
 * had no {@code org.omniframework.api} to resolve its interface against.
 */
class ApiIdentityBootTest {

    private static final String MOD_CLASS = "net/example/ExampleInit";
    private static final String SIGNAL = "omni.integration.apiIdentity.ran";

    /** A class implementing omni-api's ModInitializer, whose onInitialize sets a signal. */
    private static byte[] modInitializerClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object",
                new String[] {"org/omniframework/api/ModInitializer"});

        MethodVisitor ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor init =
                writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn(SIGNAL);
        init.visitLdcInsn("yes");
        init.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Path modJar(Path dir) throws IOException {
        Path jar = dir.resolve("apimod.jar");
        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(MOD_CLASS + ".class"));
            zip.write(modInitializerClass());
            zip.closeEntry();
        }
        return jar;
    }

    @Test
    @DisplayName("a mod implementing the API loads through the loader with shared identity")
    void modImplementingApiSharesIdentity(@TempDir Path dir) throws Exception {
        System.clearProperty(SIGNAL);
        PartitionRegistry registry = new PartitionRegistry();
        registry.add(Partition.mod("apimod", "omni", JarResourceRoot.open(modJar(dir))));

        // apiSource = this test's classloader, which has omni-api on it — exactly as the real
        // bootstrap's source has omni-api.
        try (var loader = new OmniTransformingClassLoader(
                registry, ClassTransformer.identity(), getClass().getClassLoader())) {

            Class<?> loaded = Class.forName(MOD_CLASS.replace('/', '.'), false, loader);

            // The heart of it: the interface the mod class implements must be the SAME class
            // object this test holds. Before ADR-0021 the mod class could not be defined at all;
            // a naive fix that gave the loader its own copy of the API would define it but fail
            // here, because the two ModInitializers would be different classes.
            assertTrue(ModInitializer.class.isAssignableFrom(loaded),
                    "the mod's class is not assignable to the ModInitializer this test holds — "
                            + "the API is not shared, it is duplicated");
            assertSame(ModInitializer.class,
                    loaded.getInterfaces()[0],
                    "the mod's implemented interface is a different class object than the API");

            // And it actually runs, which is what the entrypoint dispatcher does at boot.
            ModInitializer instance = (ModInitializer) loaded.getDeclaredConstructor().newInstance();
            instance.onInitialize();
            assertEquals("yes", System.getProperty(SIGNAL), "the mod's onInitialize did not run");
        } finally {
            System.clearProperty(SIGNAL);
        }
    }

    @Test
    @DisplayName("the application classpath is still sealed out")
    void applicationClasspathStillSealed(@TempDir Path dir) throws Exception {
        // The correction shares the API and nothing more. A non-API class on the app classpath —
        // this very test, or a loader internal — must remain unreachable.
        PartitionRegistry registry = new PartitionRegistry();
        registry.add(Partition.mod("apimod", "omni", JarResourceRoot.open(modJar(dir))));

        try (var loader = new OmniTransformingClassLoader(
                registry, ClassTransformer.identity(), getClass().getClassLoader())) {

            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(ApiIdentityBootTest.class.getName(), false, loader),
                    "a test class on the app classpath must not be reachable");
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(
                            "org.omniframework.loader.core.OmniTransformingClassLoader", false, loader),
                    "loader internals must not be reachable — only the API is shared");
            assertFalse(ModInitializer.class.getName().startsWith("org.omniframework.loader."),
                    "sanity: the API lives under org.omniframework.api, which is what makes the "
                            + "narrow share correct");
        }
    }
}
