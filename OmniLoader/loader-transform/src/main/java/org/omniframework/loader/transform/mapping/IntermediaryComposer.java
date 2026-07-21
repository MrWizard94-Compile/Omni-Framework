package org.omniframework.loader.transform.mapping;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.Objects;
import org.omniframework.loader.transform.TransformException;

/**
 * Builds an {@code intermediary → official} {@link MappingSet} from Fabric intermediary Tiny v2
 * plus Mojang ProGuard maps (ADR-0010 / LDR-3).
 */
public final class IntermediaryComposer {

    private IntermediaryComposer() {}

    /**
     * @param intermediaryTiny Fabric intermediary Tiny v2 ({@code official}=obf + {@code
     *     intermediary})
     * @param proguardMojang Mojang client/server mappings text
     */
    public static MappingSet compose(Reader intermediaryTiny, Reader proguardMojang)
            throws IOException {
        Objects.requireNonNull(intermediaryTiny, "intermediaryTiny");
        Objects.requireNonNull(proguardMojang, "proguardMojang");

        MappingSet interToObf = TinyV2Reader.read(intermediaryTiny, "intermediary", "official");
        ProguardMappingReader.ReverseMaps mojang = ProguardMappingReader.read(proguardMojang);

        MappingSet.Builder out = new MappingSet.Builder("intermediary", "official");
        int mappedClasses = 0;
        for (Map.Entry<String, String> entry : interToObf.classes().entrySet()) {
            String intermediary = entry.getKey();
            String obfuscated = entry.getValue();
            String official = mojang.classes().get(obfuscated);
            if (official == null) {
                continue;
            }
            out.mapClass(intermediary, official);
            mappedClasses++;
        }
        if (mappedClasses == 0) {
            throw new TransformException(
                    "composed 0 classes from intermediary + proguard — check namespaces");
        }

        for (Map.Entry<MappingSet.MemberKey, String> entry : interToObf.fields().entrySet()) {
            MappingSet.MemberKey interKey = entry.getKey();
            String obfOwner = interToObf.mapClass(interKey.owner()).orElse(null);
            if (obfOwner == null) {
                continue;
            }
            String obfName = entry.getValue();
            // Descriptor may still use intermediary type names; remap types for lookup.
            String obfDesc = remapDescriptor(interKey.descriptor(), interToObf);
            String officialName =
                    mojang.fields().get(new MappingSet.MemberKey(obfOwner, obfName, obfDesc));
            if (officialName != null) {
                out.mapField(interKey.owner(), interKey.name(), interKey.descriptor(), officialName);
            }
        }
        for (Map.Entry<MappingSet.MemberKey, String> entry : interToObf.methods().entrySet()) {
            MappingSet.MemberKey interKey = entry.getKey();
            String obfOwner = interToObf.mapClass(interKey.owner()).orElse(null);
            if (obfOwner == null) {
                continue;
            }
            String obfName = entry.getValue();
            String obfDesc = remapDescriptor(interKey.descriptor(), interToObf);
            String officialName =
                    mojang.methods().get(new MappingSet.MemberKey(obfOwner, obfName, obfDesc));
            if (officialName != null) {
                out.mapMethod(
                        interKey.owner(), interKey.name(), interKey.descriptor(), officialName);
            }
        }
        return out.build();
    }

    /** Remaps type names inside a descriptor using a class map. */
    static String remapDescriptor(String descriptor, MappingSet classMap) {
        StringBuilder out = new StringBuilder(descriptor.length());
        for (int i = 0; i < descriptor.length(); i++) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) {
                    out.append(c);
                    continue;
                }
                String internal = descriptor.substring(i + 1, end);
                String mapped = classMap.mapClass(internal).orElse(internal);
                out.append('L').append(mapped).append(';');
                i = end;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
