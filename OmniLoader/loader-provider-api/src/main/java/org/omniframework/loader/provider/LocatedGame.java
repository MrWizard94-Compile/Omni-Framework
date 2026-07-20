package org.omniframework.loader.provider;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A located game: what to load, what to call, and what to call it with.
 *
 * <p>{@code gameJars} is a list rather than a single path because a game can legitimately be
 * split — the corpus records multi-jar layouts (bundled servers that unpack, client plus a
 * separate data jar). Treating the common single-jar case as a one-element list keeps the
 * uncommon case from being a special path bolted on later.
 */
public record LocatedGame(
        String version,
        List<Path> gameJars,
        String mainClass,
        List<String> launchArguments) {

    /** Canonical constructor; validates and defensively copies. */
    public LocatedGame {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("a located game must have a version");
        }
        if (mainClass == null || mainClass.isBlank()) {
            throw new IllegalArgumentException("a located game must have a main class");
        }
        gameJars = List.copyOf(Objects.requireNonNull(gameJars, "gameJars"));
        launchArguments = List.copyOf(Objects.requireNonNull(launchArguments, "launchArguments"));
        if (gameJars.isEmpty()) {
            throw new IllegalArgumentException("a located game must have at least one jar");
        }
    }

    @Override
    public String toString() {
        return version + " (" + mainClass + ", " + gameJars.size() + " jar(s))";
    }
}
