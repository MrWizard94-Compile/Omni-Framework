package org.omniframework.loader.compat;

import java.util.Optional;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.model.ModMetadata;

/**
 * Converts a guest manifest into the canonical model (contract 2, ARCHITECTURE §7).
 *
 * <p>The canonical model is the shape every ecosystem imports INTO — the generalisation of
 * Sinytra's metadata-translation lesson. Once a mod is in it, resolution, transformation and
 * dispatch treat it identically to a native mod, which is what stops the loader growing a
 * parallel pipeline per ecosystem.
 *
 * <h2>Lossy imports warn; they do not fail</h2>
 *
 * <p>Guest manifests carry things the canonical model has no place for, and refusing those jars
 * would reject most of the ecosystem over fields that do not affect loading. So an importer maps
 * what it can, records what it could not, and returns metadata anyway.
 *
 * <p>The distinction that matters: a field that <em>changes what loads</em> and cannot be
 * expressed is an error, because ignoring it produces a mod that loads under conditions its
 * author excluded. A field that only affects presentation is a warning. Importers must not
 * blur the two to keep a jar loading.
 */
@FunctionalInterface
public interface MetadataImporter {

    /**
     * Imports a manifest.
     *
     * @param manifestText the guest manifest, as read from the jar
     * @param issues collects everything lost or suspect in translation
     * @return the canonical metadata, or empty when a blocking problem prevented it
     */
    Optional<ModMetadata> importMetadata(String manifestText, IssueCollector issues);
}
