package org.omniframework.loader.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.omniframework.api.OmniLoaderApi;
import org.omniframework.loader.provider.GameSide;
import org.omniframework.loader.resolve.ModCandidate;

/** Production {@link OmniLoaderApi} installed before entrypoints run. */
final class OmniLoaderApiImpl implements OmniLoaderApi {

    private final String loaderVersion;
    private final List<ModCandidate> mods;
    private final GameSide side;
    private final Path gameDirectory;
    private final boolean development;

    OmniLoaderApiImpl(
            String loaderVersion,
            List<ModCandidate> mods,
            GameSide side,
            Path gameDirectory,
            boolean development) {
        this.loaderVersion = Objects.requireNonNull(loaderVersion, "loaderVersion");
        this.mods = List.copyOf(Objects.requireNonNull(mods, "mods"));
        this.side = Objects.requireNonNull(side, "side");
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory");
        this.development = development;
    }

    @Override
    public String loaderVersion() {
        return loaderVersion;
    }

    @Override
    public boolean isModLoaded(String modId) {
        Objects.requireNonNull(modId, "modId");
        return mods.stream().anyMatch(m -> m.id().equals(modId));
    }

    @Override
    public Optional<String> modVersion(String modId) {
        Objects.requireNonNull(modId, "modId");
        return mods.stream()
                .filter(m -> m.id().equals(modId))
                .map(m -> m.metadata().version().toString())
                .findFirst();
    }

    @Override
    public List<String> loadedMods() {
        return mods.stream().map(ModCandidate::id).toList();
    }

    @Override
    public Side side() {
        return side == GameSide.SERVER ? Side.SERVER : Side.CLIENT;
    }

    @Override
    public boolean isDevelopment() {
        return development;
    }

    @Override
    public Path configDirectory(String modId) {
        Objects.requireNonNull(modId, "modId");
        Path dir = gameDirectory.resolve("config").resolve(modId);
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            throw new IllegalStateException("cannot create config dir for " + modId, e);
        }
        return dir;
    }
}
