package net.fabricmc.loader.api.metadata;

import java.nio.file.Path;
import java.util.List;

/** Where a mod was loaded from. */
public interface ModOrigin {

    /** The kind of origin. */
    Kind getKind();

    /** Paths, for {@link Kind#PATH}. */
    List<Path> getPaths();

    /** The parent mod's id, for {@link Kind#NESTED}. */
    String getParentModId();

    /** The sub-location within the parent, for {@link Kind#NESTED}. */
    String getParentSubLocation();

    /** How a mod arrived. */
    enum Kind {
        /** A jar or directory on disk. */
        PATH,
        /** Bundled inside another mod. */
        NESTED,
        /** Anything else — a mod the loader synthesised. */
        UNKNOWN
    }
}
