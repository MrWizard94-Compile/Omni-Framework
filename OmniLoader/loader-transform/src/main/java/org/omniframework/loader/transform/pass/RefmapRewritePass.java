package org.omniframework.loader.transform.pass;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.omniframework.loader.model.json.Json;
import org.omniframework.loader.model.json.JsonParseException;
import org.omniframework.loader.model.json.JsonReader;
import org.omniframework.loader.model.json.JsonWriter;
import org.omniframework.loader.transform.JarResource;
import org.omniframework.loader.transform.PassContext;
import org.omniframework.loader.transform.TransformPass;
import org.omniframework.loader.transform.mapping.MappingSet;

/**
 * Rewrites Mixin refmaps into the official namespace (ARCHITECTURE §6, "refmap rewrite").
 *
 * <p>A refmap is the lookup table Mixin consults at runtime to find the member a
 * {@code @Inject} or {@code @Shadow} was written against. Its <em>values</em> are names in the
 * namespace the mod was compiled in — intermediary, for Fabric-family mods.
 *
 * <p>{@link RemapPass} rewrites the bytecode but cannot see this file, because it is JSON, not a
 * class. Leaving it alone produces the most confusing failure in the whole pipeline: the jar
 * loads, the classes are correct, and then every mixin fails to apply at runtime with "target
 * method not found" naming a member that genuinely does not exist any more. So the same mapping
 * set is applied here, to the values only.
 *
 * <p>Keys are left alone. They name the mixin's own members, which live in the mod's package and
 * are never remapped.
 */
public final class RefmapRewritePass implements TransformPass {

    /** Bump when reference rewriting changes. */
    public static final String VERSION = "1";

    private static final String REFMAP_SUFFIX = "refmap.json";

    @Override
    public String id() {
        return "refmap-rewrite";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Optional<Outcome> transform(JarResource resource, PassContext context) {
        if (!isRefmap(resource.name()) || context.mappings().isIdentity()) {
            return Optional.empty();
        }
        Json parsed;
        try {
            parsed = JsonReader.parse(new String(resource.content(), StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            // A malformed refmap is the mod's problem, but failing the whole jar over it would
            // block a pack for a file that Mixin itself might tolerate. Report and pass through.
            context.issues().add(org.omniframework.loader.diag.OmniIssue
                    .warning("transform.refmap-unreadable")
                    .at(context.jarName() + "!" + resource.name())
                    .remedy("This mixin refmap is not valid JSON and was left untouched, so its "
                            + "mixins may fail to apply: " + e.getMessage())
                    .build());
            return Optional.empty();
        }

        List<String> notes = new ArrayList<>();
        Json rewritten = rewrite(parsed, context.mappings(), notes);
        if (notes.isEmpty()) {
            return Optional.empty();
        }
        String text = JsonWriter.pretty(rewritten);
        return Optional.of(Outcome.of(text.getBytes(StandardCharsets.UTF_8),
                List.of(notes.size() + " refmap references rewritten")));
    }

    /** Whether a jar entry is a Mixin refmap. */
    static boolean isRefmap(String name) {
        return name.endsWith(REFMAP_SUFFIX);
    }

    /** Walks the document, rewriting every string value that names a mapped member. */
    private static Json rewrite(Json value, MappingSet mappings, List<String> changed) {
        return switch (value) {
            case Json.Obj obj -> {
                Map<String, Json> members = new LinkedHashMap<>();
                obj.members().forEach((key, member) ->
                        members.put(key, rewrite(member, mappings, changed)));
                yield new Json.Obj(members);
            }
            case Json.Arr arr -> new Json.Arr(
                    arr.elements().stream().map(element -> rewrite(element, mappings, changed)).toList());
            case Json.Str str -> {
                String mapped = rewriteReference(str.value(), mappings);
                if (!mapped.equals(str.value())) {
                    changed.add(str.value() + " → " + mapped);
                    yield new Json.Str(mapped);
                }
                yield str;
            }
            default -> value;
        };
    }

    /**
     * Rewrites one reference string.
     *
     * <p>Three shapes occur, and they are distinguished by punctuation rather than by guessing:
     *
     * <ul>
     *   <li>{@code Lowner;name:descriptor} — a field reference
     *   <li>{@code Lowner;name(args)ret} — a method reference
     *   <li>{@code owner} — a bare class name
     * </ul>
     */
    static String rewriteReference(String reference, MappingSet mappings) {
        if (reference.isEmpty()) {
            return reference;
        }
        if (reference.charAt(0) == 'L' && reference.indexOf(';') > 0) {
            return rewriteQualified(reference, mappings);
        }
        // A bare name is only a class reference if it looks like an internal name; anything
        // else is ordinary text that happens to live in the refmap.
        return mappings.mapClass(reference).orElse(reference);
    }

    private static String rewriteQualified(String reference, MappingSet mappings) {
        int semicolon = reference.indexOf(';');
        String owner = reference.substring(1, semicolon);
        String remainder = reference.substring(semicolon + 1);
        String mappedOwner = mappings.mapClass(owner).orElse(owner);

        int colon = remainder.indexOf(':');
        int paren = remainder.indexOf('(');
        String mappedRemainder;
        if (paren >= 0) {
            String name = remainder.substring(0, paren);
            String descriptor = remainder.substring(paren);
            String mappedName = mappings.mapMethod(owner, name, descriptor).orElse(name);
            mappedRemainder = mappedName + mapDescriptor(descriptor, mappings);
        } else if (colon >= 0) {
            String name = remainder.substring(0, colon);
            String descriptor = remainder.substring(colon + 1);
            String mappedName = mappings.mapField(owner, name, descriptor).orElse(name);
            mappedRemainder = mappedName + ":" + mapDescriptor(descriptor, mappings);
        } else {
            mappedRemainder = remainder;
        }
        return "L" + mappedOwner + ";" + mappedRemainder;
    }

    /**
     * Rewrites the class names inside a type descriptor.
     *
     * <p>A descriptor such as {@code (Lnet/minecraft/class_1;)Lnet/minecraft/class_2;} names
     * types that are themselves being renamed. Leaving them would produce a reference whose owner
     * is official and whose signature is intermediary — a combination that matches nothing.
     */
    static String mapDescriptor(String descriptor, MappingSet mappings) {
        StringBuilder out = new StringBuilder(descriptor.length());
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c != 'L') {
                out.append(c);
                i++;
                continue;
            }
            int end = descriptor.indexOf(';', i);
            if (end < 0) {
                // Malformed descriptor; copy the rest verbatim rather than throwing. Mixin will
                // report a far better error about this than the transformer can.
                out.append(descriptor, i, descriptor.length());
                break;
            }
            String type = descriptor.substring(i + 1, end);
            out.append('L').append(mappings.mapClass(type).orElse(type)).append(';');
            i = end + 1;
        }
        return out.toString();
    }
}
