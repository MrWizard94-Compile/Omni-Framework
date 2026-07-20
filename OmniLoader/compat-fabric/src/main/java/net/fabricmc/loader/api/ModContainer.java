package net.fabricmc.loader.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;

/** A loaded mod, as a hosted mod sees it. */
public interface ModContainer {

    /** What the mod declares about itself. */
    ModMetadata getMetadata();

    /** The roots its files live under. */
    List<Path> getRootPaths();

    /** Where it came from. */
    ModOrigin getOrigin();

    /** The mod that bundles this one, if it is nested. */
    Optional<ModContainer> getContainingMod();

    /** Mods bundled inside this one. */
    Collection<ModContainer> getContainedMods();

    /**
     * Locates a file inside the mod, if it exists.
     *
     * <p>Checks every root rather than only the first: a mod may legitimately span more than one
     * (a split source set in a development environment being the usual case).
     */
    default Optional<Path> findPath(String file) {
        for (Path root : getRootPaths()) {
            Path candidate = root.resolve(file.replace("/", root.getFileSystem().getSeparator()));
            if (Files.exists(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * The first root.
     *
     * @deprecated a mod can have several roots; {@link #getRootPaths()} is correct. Kept because
     *     hosted bytecode still calls it, and removing it would be a NoSuchMethodError in mods
     *     that load perfectly well otherwise.
     */
    @Deprecated
    default Path getRoot() {
        return getRootPath();
    }

    /**
     * The first root.
     *
     * @deprecated see {@link #getRoot()}.
     */
    @Deprecated
    Path getRootPath();

    /**
     * Resolves a path inside the mod whether or not it exists.
     *
     * @deprecated prefer {@link #findPath(String)}, which says whether it is really there.
     */
    @Deprecated
    Path getPath(String file);
}
