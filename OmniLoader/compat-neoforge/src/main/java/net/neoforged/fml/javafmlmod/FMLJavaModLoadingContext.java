package net.neoforged.fml.javafmlmod;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.neoforged.bus.api.IEventBus;

/**
 * Hosted stand-in for NeoForge's per-mod loading context.
 *
 * <p>OmniLoader installs one context per NeoForge mod id before constructing {@code @Mod}
 * classes so constructor code that calls {@link #get()}{@code .getModEventBus()} works.
 */
public final class FMLJavaModLoadingContext {

    private static final Map<String, FMLJavaModLoadingContext> BY_MOD = new ConcurrentHashMap<>();
    private static volatile String activeModId;

    private final String modId;
    private final IEventBus modEventBus;

    private FMLJavaModLoadingContext(String modId, IEventBus modEventBus) {
        this.modId = Objects.requireNonNull(modId, "modId");
        this.modEventBus = Objects.requireNonNull(modEventBus, "modEventBus");
    }

    /** Installs a context for {@code modId} (called by Omni boot before construction). */
    public static void install(String modId, IEventBus bus) {
        BY_MOD.put(modId, new FMLJavaModLoadingContext(modId, bus));
    }

    /** Makes {@code modId} the current context for {@link #get()}. */
    public static void activate(String modId) {
        activeModId = modId;
    }

    /** Clears all installed contexts after NeoForge entrypoints finish. */
    public static void clear() {
        BY_MOD.clear();
        activeModId = null;
    }

    /**
     * The context for the mod currently being constructed.
     *
     * @throws IllegalStateException if no context is active
     */
    public static FMLJavaModLoadingContext get() {
        String id = activeModId;
        if (id == null) {
            throw new IllegalStateException(
                    "FMLJavaModLoadingContext.get() called with no active NeoForge mod — "
                            + "this usually means a static initialiser ran before Omni installed the context");
        }
        return forMod(id);
    }

    /**
     * Context installed for {@code modId}, independent of the active-construction pointer.
     *
     * <p>Use this when posting events after construction so listeners fire on the owning mod's
     * bus even if another mod is (or was last) active.
     *
     * @throws IllegalStateException if no context was installed for that id
     */
    public static FMLJavaModLoadingContext forMod(String modId) {
        Objects.requireNonNull(modId, "modId");
        FMLJavaModLoadingContext ctx = BY_MOD.get(modId);
        if (ctx == null) {
            throw new IllegalStateException("no FMLJavaModLoadingContext for mod " + modId);
        }
        return ctx;
    }

    /** This mod's event bus. */
    public IEventBus getModEventBus() {
        return modEventBus;
    }

    /** Mod id this context was installed for. */
    public String getModId() {
        return modId;
    }
}
