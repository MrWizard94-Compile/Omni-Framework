package org.omniframework.loader.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.transform.mapping.ClassIndex;
import org.omniframework.loader.transform.mapping.MappingSet;
import org.omniframework.loader.transform.pass.AccessChange;
import org.omniframework.loader.transform.pass.AccessMergePass;
import org.omniframework.loader.transform.pass.EnvironmentStripPass;
import org.omniframework.loader.transform.pass.RefmapRewritePass;

/** The three passes that are not remapping, tested through their observable effects. */
class PassBehaviourTest {

    private static PassContext context(
            MappingSet mappings, PassContext.GameSideTarget side, IssueCollector issues) {
        return new PassContext("test.jar", "testmod", mappings, new ClassIndex(), side, issues);
    }

    private static PassContext clientContext() {
        return context(MappingSet.identity(), PassContext.GameSideTarget.CLIENT,
                new IssueCollector());
    }

    // ------------------------------------------------------------ access merge

    @Nested
    @DisplayName("access merge")
    class Access {

        @Test
        @DisplayName("a private member becomes public and the class loses final")
        void widensMembers() throws Exception {
            byte[] original = Bytecode.restrictedClass("game/Restricted");
            AccessMergePass pass = new AccessMergePass(List.of(
                    AccessChange.ofClass("game/Restricted", AccessChange.Kind.EXTENDABLE),
                    AccessChange.ofField("game/Restricted", "secret", "I",
                            AccessChange.Kind.MUTABLE),
                    AccessChange.ofMethod("game/Restricted", "hidden", "()I",
                            AccessChange.Kind.ACCESSIBLE)));

            byte[] widened = pass.transform(
                    new JarResource("game/Restricted.class", original), clientContext())
                    .orElseThrow().content();

            Class<?> loaded = new Bytecode.Loader()
                    .with("game/Restricted", widened).load("game/Restricted");
            assertFalse(Modifier.isFinal(loaded.getModifiers()), "extendable must remove final");
            assertTrue(Modifier.isPublic(loaded.getDeclaredMethod("hidden").getModifiers()),
                    "accessible must make the method public");
            var field = loaded.getDeclaredField("secret");
            assertTrue(Modifier.isPublic(field.getModifiers()), "mutable must make it public");
            assertFalse(Modifier.isFinal(field.getModifiers()), "mutable must remove final");
        }

        @Test
        @DisplayName("the widest request wins regardless of the order they were declared")
        void mergesToWidest() {
            // Order-dependent access would mean a pack that breaks when a mod is renamed, and a
            // member less accessible than a mod was compiled against fails with
            // IllegalAccessError at runtime.
            List<AccessChange> merged = AccessChange.merge(List.of(
                    AccessChange.ofField("game/C", "f", "I", AccessChange.Kind.ACCESSIBLE),
                    AccessChange.ofField("game/C", "f", "I", AccessChange.Kind.EXTENDABLE)));

            assertEquals(1, merged.size(), "the same member should appear once");
            assertEquals(AccessChange.Kind.MUTABLE, merged.get(0).kind());

            List<AccessChange> reversed = AccessChange.merge(List.of(
                    AccessChange.ofField("game/C", "f", "I", AccessChange.Kind.EXTENDABLE),
                    AccessChange.ofField("game/C", "f", "I", AccessChange.Kind.ACCESSIBLE)));
            assertEquals(merged, reversed, "merge order must not change the outcome");
        }

        @Test
        @DisplayName("a class named by no change is untouched")
        void unrelatedClassUntouched() {
            AccessMergePass pass = new AccessMergePass(List.of(
                    AccessChange.ofClass("game/Other", AccessChange.Kind.ACCESSIBLE)));

            assertTrue(pass.transform(
                    new JarResource("game/Restricted.class",
                            Bytecode.restrictedClass("game/Restricted")),
                    clientContext()).isEmpty());
        }

        @Test
        @DisplayName("an access widener parses, and a malformed one is refused loudly")
        void parsesWidener() {
            List<AccessChange> changes = AccessChange.parseWidener("""
                    accessWidener v2 named
                    # a comment
                    accessible method net/minecraft/Foo bar ()V
                    extendable class net/minecraft/Baz
                    mutable field net/minecraft/Foo qux I
                    """);

            assertEquals(3, changes.size());
            assertTrue(changes.contains(AccessChange.ofMethod(
                    "net/minecraft/Foo", "bar", "()V", AccessChange.Kind.ACCESSIBLE)));
            assertTrue(changes.contains(AccessChange.ofClass(
                    "net/minecraft/Baz", AccessChange.Kind.EXTENDABLE)));

            // A silently ignored directive surfaces as IllegalAccessError deep inside a mod at
            // runtime, which is far harder to trace than a refusal here.
            assertThrows(TransformException.class, () -> AccessChange.parseWidener("""
                    accessWidener v2 named
                    invisible method net/minecraft/Foo bar ()V
                    """));
            assertThrows(TransformException.class,
                    () -> AccessChange.parseWidener("accessible class net/minecraft/Foo"));
        }
    }

    // ------------------------------------------------------ environment strip

    @Nested
    @DisplayName("environment strip")
    class Strip {

        private static final String FABRIC = "Lnet/fabricmc/api/Environment;";

        @Test
        @DisplayName("members for the other side are removed and common ones survive")
        void stripsOtherSide() throws Exception {
            byte[] original = Bytecode.sidedClass(
                    "mod/Sided", FABRIC, null, "CLIENT", "CLIENT");
            EnvironmentStripPass pass = new EnvironmentStripPass();
            PassContext server = context(MappingSet.identity(),
                    PassContext.GameSideTarget.SERVER, new IssueCollector());
            pass.beginJar(List.of(), server);

            byte[] stripped = pass.transform(
                    new JarResource("mod/Sided.class", original), server).orElseThrow().content();

            Class<?> loaded = new Bytecode.Loader().with("mod/Sided", stripped).load("mod/Sided");
            assertTrue(Arrays.stream(loaded.getDeclaredMethods())
                            .noneMatch(method -> method.getName().equals("sidedMethod")),
                    "a client-only method must not survive onto a server");
            assertTrue(Arrays.stream(loaded.getDeclaredFields())
                            .noneMatch(field -> field.getName().equals("sidedField")),
                    "a client-only field must not survive onto a server");
            assertEquals(2, loaded.getDeclaredMethod("commonMethod")
                    .invoke(loaded.getDeclaredConstructor().newInstance()),
                    "common members must be untouched and still work");
        }

        @Test
        @DisplayName("members for this side are kept")
        void keepsOwnSide() {
            byte[] original = Bytecode.sidedClass("mod/Sided", FABRIC, null, "CLIENT", "CLIENT");
            EnvironmentStripPass pass = new EnvironmentStripPass();
            PassContext client = clientContext();
            pass.beginJar(List.of(), client);

            assertTrue(pass.transform(new JarResource("mod/Sided.class", original), client)
                            .isEmpty(),
                    "building for the client must leave client members alone");
        }

        @Test
        @DisplayName("a whole class for the other side is marked for removal, with a reason")
        void reportsWholeClass() {
            byte[] original = Bytecode.sidedClass("mod/ClientOnly", FABRIC, "CLIENT", null, null);
            EnvironmentStripPass pass = new EnvironmentStripPass();
            PassContext server = context(MappingSet.identity(),
                    PassContext.GameSideTarget.SERVER, new IssueCollector());

            TransformPass.Outcome outcome = pass.transform(
                    new JarResource("mod/ClientOnly.class", original), server).orElseThrow();

            assertTrue(outcome.removed(),
                    "the entry must be dropped, not emitted as a stripped-empty class");
            assertTrue(outcome.notes().stream().anyMatch(note -> note.contains("client")),
                    () -> "the audit should explain the absence: " + outcome.notes());
        }

        @Test
        @DisplayName("the pass holds no state, so parallel jars cannot interfere")
        void isStateless() {
            // The pipeline shares one pass instance across concurrently transforming jars.
            // An earlier version recorded whole-class strips in a field, which raced.
            EnvironmentStripPass pass = new EnvironmentStripPass();
            PassContext server = context(MappingSet.identity(),
                    PassContext.GameSideTarget.SERVER, new IssueCollector());

            JarResource clientOnly = new JarResource("mod/ClientOnly.class",
                    Bytecode.sidedClass("mod/ClientOnly", FABRIC, "CLIENT", null, null));
            JarResource common = new JarResource("mod/Common.class",
                    Bytecode.valueClass("mod/Common", 1));

            assertTrue(pass.transform(clientOnly, server).orElseThrow().removed());
            assertTrue(pass.transform(common, server).isEmpty(),
                    "a later unrelated class must not inherit the previous one's verdict");
            assertTrue(pass.transform(clientOnly, server).orElseThrow().removed(),
                    "and the verdict must be reproducible, not consumed");
        }

        @Test
        @DisplayName("the NeoForge spelling of the annotation is honoured too")
        void handlesOtherEcosystemSpelling() {
            byte[] original = Bytecode.sidedClass(
                    "mod/Sided", "Lnet/neoforged/api/distmarker/OnlyIn;", null, null, "CLIENT");
            EnvironmentStripPass pass = new EnvironmentStripPass();
            PassContext server = context(MappingSet.identity(),
                    PassContext.GameSideTarget.SERVER, new IssueCollector());
            pass.beginJar(List.of(), server);

            Optional<TransformPass.Outcome> result =
                    pass.transform(new JarResource("mod/Sided.class", original), server);

            assertTrue(result.isPresent(),
                    "both ecosystems spell this differently; the intent is identical");
            assertTrue(result.get().notes().stream()
                    .anyMatch(note -> note.contains("sidedMethod")), result.get().notes()::toString);
        }
    }

    // ----------------------------------------------------------- refmap rewrite

    @Nested
    @DisplayName("refmap rewrite")
    class Refmap {

        private static final MappingSet MAPPINGS = new MappingSet.Builder("intermediary", "official")
                .mapClass("net/minecraft/class_1", "net/minecraft/world/entity/Entity")
                .mapClass("net/minecraft/class_2", "net/minecraft/world/level/Level")
                .mapMethod("net/minecraft/class_1", "method_1", "()V", "tick")
                .mapField("net/minecraft/class_1", "field_1", "I", "health")
                .build();

        private static String rewrite(String json) {
            PassContext ctx = context(MAPPINGS, PassContext.GameSideTarget.CLIENT,
                    new IssueCollector());
            return new RefmapRewritePass()
                    .transform(new JarResource("mod.refmap.json",
                            json.getBytes(StandardCharsets.UTF_8)), ctx)
                    .map(outcome -> new String(outcome.content(), StandardCharsets.UTF_8))
                    .orElse(json);
        }

        @Test
        @DisplayName("method, field, and class references are rewritten; keys are not")
        void rewritesValues() {
            String result = rewrite("""
                    {"mappings":{"mod/MixinFoo":{
                      "method_1":"Lnet/minecraft/class_1;method_1()V",
                      "field_1":"Lnet/minecraft/class_1;field_1:I",
                      "bare":"net/minecraft/class_2"}}}
                    """);

            assertTrue(result.contains("Lnet/minecraft/world/entity/Entity;tick()V"), result);
            assertTrue(result.contains("Lnet/minecraft/world/entity/Entity;health:I"), result);
            assertTrue(result.contains("net/minecraft/world/level/Level"), result);
            assertTrue(result.contains("\"method_1\":"),
                    "keys name the mixin's own members and must not be rewritten: " + result);
        }

        @Test
        @DisplayName("class names inside a descriptor are rewritten too")
        void rewritesDescriptors() {
            // An owner rewritten to official with an intermediary signature matches nothing,
            // and Mixin reports it as a missing target rather than a mapping problem.
            String result = rewrite("""
                    {"mappings":{"mod/M":{"m":"Lnet/minecraft/class_1;method_1(Lnet/minecraft/class_2;)V"}}}
                    """);

            assertTrue(result.contains("(Lnet/minecraft/world/level/Level;)V"), result);
        }

        @Test
        @DisplayName("an identity mapping leaves the refmap alone")
        void identitySkips() {
            PassContext ctx = clientContext();
            assertTrue(new RefmapRewritePass().transform(
                    new JarResource("mod.refmap.json", "{}".getBytes(StandardCharsets.UTF_8)), ctx)
                    .isEmpty());
        }

        @Test
        @DisplayName("an unreadable refmap warns and passes through instead of failing the pack")
        void malformedWarns() {
            IssueCollector issues = new IssueCollector();
            PassContext ctx = context(MAPPINGS, PassContext.GameSideTarget.CLIENT, issues);

            Optional<TransformPass.Outcome> result = new RefmapRewritePass().transform(
                    new JarResource("mod.refmap.json",
                            "{not json".getBytes(StandardCharsets.UTF_8)), ctx);

            assertTrue(result.isEmpty());
            assertTrue(issues.issues().stream()
                            .anyMatch(issue -> issue.code().equals("transform.refmap-unreadable")),
                    "the user should be told, but one bad file must not block the whole pack");
        }

        @Test
        @DisplayName("a non-refmap json file is ignored")
        void ignoresOtherJson() {
            PassContext ctx = context(MAPPINGS, PassContext.GameSideTarget.CLIENT,
                    new IssueCollector());
            assertTrue(new RefmapRewritePass().transform(
                    new JarResource("omni.mod.json",
                            "{\"id\":\"net/minecraft/class_1\"}".getBytes(StandardCharsets.UTF_8)),
                    ctx).isEmpty(),
                    "rewriting arbitrary JSON would corrupt manifests that merely look similar");
        }
    }
}
