package org.omniframework.loader.compat.fabric.impl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ObjectShare;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.metadata.Person;
import org.omniframework.loader.resolve.ModCandidate;

/**
 * Runtime {@link FabricLoader} installed before hosted entrypoints run.
 *
 * <p>Reports the <em>whole</em> resolved pack (ADR-0020). Entrypoint containers are populated
 * from declared metadata so {@code getEntrypoints}/{@code getEntrypointContainers} answer
 * during init (deeper host surface); Omni still owns phase ordering via EntrypointDispatcher.
 */
public final class OmniFabricLoader implements FabricLoader {

    private final Path gameDir;
    private final Path configDir;
    private final EnvType envType;
    private final String gameVersion;
    private final String[] launchArguments;
    private final Map<String, ModContainer> modsById;
    private final Map<String, List<DeclaredEntrypoint>> entrypointsByKey;
    private final ObjectShare objectShare = new SimpleObjectShare();
    private final MappingResolver mappingResolver = new IdentityMappingResolver();
    private volatile Object gameInstance;
    private volatile ClassLoader entrypointLoader;

    public OmniFabricLoader(
            Path gameDir,
            EnvType envType,
            String gameVersion,
            String[] launchArguments,
            List<ModCandidate> resolvedMods) {
        this.gameDir = Objects.requireNonNull(gameDir, "gameDir");
        this.configDir = gameDir.resolve("config");
        this.envType = Objects.requireNonNull(envType, "envType");
        this.gameVersion = Objects.requireNonNull(gameVersion, "gameVersion");
        this.launchArguments = launchArguments == null ? new String[0] : launchArguments.clone();
        Map<String, ModContainer> map = new LinkedHashMap<>();
        Map<String, List<DeclaredEntrypoint>> byKey = new LinkedHashMap<>();
        for (ModCandidate candidate : resolvedMods) {
            SimpleModContainer container = new SimpleModContainer(candidate);
            map.put(candidate.id(), container);
            for (var provided : candidate.metadata().provides()) {
                map.putIfAbsent(provided.id(), container);
            }
            for (var ep : candidate.metadata().entrypoints()) {
                byKey.computeIfAbsent(ep.phase(), ignored -> new ArrayList<>())
                        .add(new DeclaredEntrypoint(container, ep.value()));
            }
        }
        this.modsById = Collections.unmodifiableMap(map);
        this.entrypointsByKey = Collections.unmodifiableMap(byKey);
    }

    /** Sets the classloader used to materialize entrypoint instances for getEntrypoints. */
    public void setEntrypointLoader(ClassLoader loader) {
        this.entrypointLoader = loader;
    }

    public void setGameInstance(Object gameInstance) {
        this.gameInstance = gameInstance;
    }

    @Override
    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        return getEntrypointContainers(key, type).stream()
                .map(EntrypointContainer::getEntrypoint)
                .toList();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        List<DeclaredEntrypoint> declared = entrypointsByKey.getOrDefault(key, List.of());
        if (declared.isEmpty() || entrypointLoader == null) {
            return List.of();
        }
        List<EntrypointContainer<T>> out = new ArrayList<>();
        for (DeclaredEntrypoint decl : declared) {
            try {
                Class<?> cls = Class.forName(decl.className, false, entrypointLoader);
                if (!type.isAssignableFrom(cls)) {
                    continue;
                }
                T instance = (T) cls.getDeclaredConstructor().newInstance();
                out.add(new SimpleEntrypointContainer<>(decl.container, instance));
            } catch (ReflectiveOperationException ignored) {
                // Skip unloadable; boot dispatcher still owns the authoritative path.
            }
        }
        return out;
    }

    @Override
    public <T> void invokeEntrypoints(String key, Class<T> type, Consumer<? super T> invoker) {
        for (T entry : getEntrypoints(key, type)) {
            invoker.accept(entry);
        }
    }

    private record DeclaredEntrypoint(ModContainer container, String className) {}

    private static final class SimpleEntrypointContainer<T> implements EntrypointContainer<T> {
        private final ModContainer provider;
        private final T entrypoint;

        SimpleEntrypointContainer(ModContainer provider, T entrypoint) {
            this.provider = provider;
            this.entrypoint = entrypoint;
        }

        @Override
        public T getEntrypoint() {
            return entrypoint;
        }

        @Override
        public ModContainer getProvider() {
            return provider;
        }
    }

    @Override
    public ObjectShare getObjectShare() {
        return objectShare;
    }

    @Override
    public MappingResolver getMappingResolver() {
        return mappingResolver;
    }

    @Override
    public Optional<ModContainer> getModContainer(String id) {
        return Optional.ofNullable(modsById.get(id));
    }

    @Override
    public Collection<ModContainer> getAllMods() {
        // Dedup by identity for provides aliases.
        return modsById.values().stream().distinct().toList();
    }

    @Override
    public boolean isModLoaded(String id) {
        return modsById.containsKey(id);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return false;
    }

    @Override
    public EnvType getEnvironmentType() {
        return envType;
    }

    @Override
    public String getRawGameVersion() {
        return gameVersion;
    }

    @Override
    public Object getGameInstance() {
        return gameInstance;
    }

    @Override
    public Path getGameDir() {
        return gameDir;
    }

    @Override
    public Path getConfigDir() {
        return configDir;
    }

    @Override
    public String[] getLaunchArguments(boolean sanitize) {
        return launchArguments.clone();
    }

    private static final class SimpleObjectShare implements ObjectShare {
        private final Map<String, Object> values = new ConcurrentHashMap<>();
        private final Map<String, List<BiConsumer<String, Object>>> waiters =
                new ConcurrentHashMap<>();

        @Override
        public Object get(String key) {
            return values.get(key);
        }

        @Override
        public Object put(String key, Object value) {
            Object previous = values.put(key, value);
            fire(key, value);
            return previous;
        }

        @Override
        public Object putIfAbsent(String key, Object value) {
            Object previous = values.putIfAbsent(key, value);
            if (previous == null) {
                fire(key, value);
            }
            return previous;
        }

        @Override
        public Object remove(String key) {
            return values.remove(key);
        }

        @Override
        public void whenAvailable(String key, BiConsumer<String, Object> consumer) {
            Object existing = values.get(key);
            if (existing != null) {
                consumer.accept(key, existing);
                return;
            }
            waiters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(consumer);
        }

        @Override
        public Map<String, Object> getMap() {
            return Map.copyOf(values);
        }

        private void fire(String key, Object value) {
            List<BiConsumer<String, Object>> list = waiters.remove(key);
            if (list != null) {
                for (BiConsumer<String, Object> consumer : list) {
                    consumer.accept(key, value);
                }
            }
        }
    }

    private static final class IdentityMappingResolver implements MappingResolver {
        @Override
        public Collection<String> getNamespaces() {
            return List.of("official", "intermediary", "named");
        }

        @Override
        public String getCurrentRuntimeNamespace() {
            return "official";
        }

        @Override
        public String mapClassName(String namespace, String className) {
            return className;
        }

        @Override
        public String unmapClassName(String targetNamespace, String className) {
            return className;
        }

        @Override
        public String mapFieldName(String namespace, String owner, String name, String descriptor) {
            return name;
        }

        @Override
        public String mapMethodName(String namespace, String owner, String name, String descriptor) {
            return name;
        }
    }

    @SuppressWarnings("deprecation")
    private static final class SimpleModContainer implements ModContainer {
        private final ModCandidate candidate;
        private final ModMetadata metadata;
        private final ModOrigin origin;

        SimpleModContainer(ModCandidate candidate) {
            this.candidate = candidate;
            this.metadata = new SimpleModMetadata(candidate);
            this.origin = new PathOrigin(candidate.source());
        }

        @Override
        public ModMetadata getMetadata() {
            return metadata;
        }

        @Override
        public List<Path> getRootPaths() {
            return List.of(candidate.source());
        }

        @Override
        public ModOrigin getOrigin() {
            return origin;
        }

        @Override
        public Optional<ModContainer> getContainingMod() {
            return Optional.empty();
        }

        @Override
        public Collection<ModContainer> getContainedMods() {
            return List.of();
        }

        @Override
        public Path getRootPath() {
            return candidate.source();
        }

        @Override
        public Path getPath(String file) {
            return candidate.source().resolve(file.replace('/', java.io.File.separatorChar));
        }
    }

    private static final class PathOrigin implements ModOrigin {
        private final Path path;

        PathOrigin(Path path) {
            this.path = path;
        }

        @Override
        public Kind getKind() {
            return Kind.PATH;
        }

        @Override
        public List<Path> getPaths() {
            return List.of(path);
        }

        @Override
        public String getParentModId() {
            throw new UnsupportedOperationException("PATH origin has no parent");
        }

        @Override
        public String getParentSubLocation() {
            throw new UnsupportedOperationException("PATH origin has no parent");
        }
    }

    private static final class SimpleModMetadata implements ModMetadata {
        private final ModCandidate candidate;

        SimpleModMetadata(ModCandidate candidate) {
            this.candidate = candidate;
        }

        @Override
        public String getId() {
            return candidate.id();
        }

        @Override
        public Collection<String> getProvides() {
            return candidate.metadata().provides().stream().map(p -> p.id()).toList();
        }

        @Override
        public Version getVersion() {
            try {
                return OmniVersion.parse(candidate.version().raw());
            } catch (Exception e) {
                try {
                    return OmniVersion.parse("0.0.0");
                } catch (Exception ignored) {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public ModEnvironment getEnvironment() {
            return ModEnvironment.UNIVERSAL;
        }

        @Override
        public String getName() {
            return candidate.metadata().displayName();
        }

        @Override
        public String getDescription() {
            return candidate.metadata().description().orElse("");
        }

        @Override
        public Collection<Person> getAuthors() {
            return List.of();
        }

        @Override
        public Collection<Person> getContributors() {
            return List.of();
        }

        @Override
        public ContactInformation getContact() {
            return ContactInformation.EMPTY;
        }

        @Override
        public Collection<String> getLicense() {
            return List.of();
        }

        @Override
        public Optional<String> getIconPath(int size) {
            return Optional.empty();
        }

        @Override
        public boolean containsCustomValue(String key) {
            return false;
        }

        @Override
        public CustomValue getCustomValue(String key) {
            return null;
        }
    }
}
