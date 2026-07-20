package org.omniframework.loader.provider.minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;
import org.omniframework.loader.provider.GameProvider;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.provider.LocatedGame;

/**
 * The provider that knows about Minecraft — and the only place in the loader that does.
 *
 * <p>Everything Minecraft-specific lives behind this class: where the jar sits, what the main
 * class is called, how a version is named. `loader-core` is deliberately ignorant of all of it
 * (LDR-1), which is what makes TGT-2's version-agnosticism structural rather than a promise.
 *
 * <p><b>Where the jar comes from.</b> The launcher installs the game jar into its
 * content-addressed cache and hands the loader an explicit path, because a launcher that already
 * verified a file's hash should not then have the loader guess at a filename. The directory scan
 * is the fallback for a hand-assembled instance, and it is a scan rather than a fixed name
 * because instances in the wild are not consistently laid out.
 */
public final class MinecraftGameProvider implements GameProvider {

    /** The client entry point, stable across every version the loader targets. */
    public static final String CLIENT_MAIN_CLASS = "net.minecraft.client.main.Main";
    /** The dedicated-server entry point. */
    public static final String SERVER_MAIN_CLASS = "net.minecraft.server.Main";

    private final Optional<Path> explicitJar;
    private final Optional<String> explicitVersion;

    /** A provider that finds the jar by scanning the game directory. */
    public MinecraftGameProvider() {
        this(Optional.empty(), Optional.empty());
    }

    /**
     * A provider told exactly which jar to use and what version it is — the path the launcher
     * takes, since it installed that jar itself.
     */
    public MinecraftGameProvider(Path jar, String version) {
        this(Optional.of(jar), Optional.of(version));
    }

    private MinecraftGameProvider(Optional<Path> explicitJar, Optional<String> explicitVersion) {
        this.explicitJar = Objects.requireNonNull(explicitJar, "explicitJar");
        this.explicitVersion = Objects.requireNonNull(explicitVersion, "explicitVersion");
    }

    @Override
    public String id() {
        return "minecraft";
    }

    @Override
    public String displayName() {
        return "Minecraft: Java Edition";
    }

    @Override
    public boolean canHandle(Path gameDirectory, GameSide side) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(side, "side");
        if (explicitJar.isPresent()) {
            return Files.isRegularFile(explicitJar.get());
        }
        return findJarByScan(gameDirectory, side).isPresent();
    }

    @Override
    public Optional<LocatedGame> locate(Path gameDirectory, GameSide side, IssueCollector issues) {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(issues, "issues");

        Optional<Path> jar = explicitJar.isPresent() ? explicitJar : findJarByScan(gameDirectory, side);
        if (jar.isEmpty()) {
            issues.add(OmniIssue.error("minecraft.jar-not-found")
                    .at(gameDirectory.toString())
                    .with(side.name().toLowerCase(java.util.Locale.ROOT))
                    .remedy("No Minecraft jar was found for this instance. Run Install / Repair "
                            + "in the launcher to download the game files.")
                    .build());
            return Optional.empty();
        }
        if (!Files.isRegularFile(jar.get())) {
            issues.add(OmniIssue.error("minecraft.jar-missing")
                    .at(jar.get().toString())
                    .remedy("The game jar this instance points at is gone. Run Install / Repair "
                            + "to fetch it again.")
                    .build());
            return Optional.empty();
        }

        String version = explicitVersion.orElseGet(() -> versionFromFileName(jar.get()));
        if (version.isBlank()) {
            issues.add(OmniIssue.error("minecraft.version-unknown")
                    .at(jar.get().toString())
                    .remedy("The game version could not be determined from the jar's name. "
                            + "Launch through OmniLauncher, which knows which version it installed.")
                    .build());
            return Optional.empty();
        }

        String mainClass = side == GameSide.CLIENT ? CLIENT_MAIN_CLASS : SERVER_MAIN_CLASS;
        return Optional.of(new LocatedGame(version, List.of(jar.get()), mainClass, List.of()));
    }

    /**
     * Looks for a jar in the conventional places: {@code <dir>/<name>.jar} beside a version
     * folder, or a single jar in {@code <dir>/versions/<version>/}.
     */
    private Optional<Path> findJarByScan(Path gameDirectory, GameSide side) {
        String preferred = side == GameSide.CLIENT ? "client.jar" : "server.jar";
        Path direct = gameDirectory.resolve(preferred);
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct);
        }
        Path versions = gameDirectory.resolve("versions");
        if (!Files.isDirectory(versions)) {
            return Optional.empty();
        }
        try (var stream = Files.list(versions)) {
            return stream.filter(Files::isDirectory)
                    .map(dir -> dir.resolve(dir.getFileName().toString() + ".jar"))
                    .filter(Files::isRegularFile)
                    .findFirst();
        } catch (java.io.IOException unreadable) {
            return Optional.empty();
        }
    }

    /** Derives a version from {@code 1.21.1.jar}, or empty when the name says nothing. */
    private static String versionFromFileName(Path jar) {
        String name = jar.getFileName().toString();
        if (!name.endsWith(".jar")) {
            return "";
        }
        String stem = name.substring(0, name.length() - 4);
        // "client"/"server" are the generic names the launcher's cache uses; they carry no
        // version, and inventing one would be worse than admitting we do not know.
        return stem.equals("client") || stem.equals("server") ? "" : stem;
    }
}
