package net.fabricmc.loader.api.metadata;

import java.util.Collection;
import java.util.Optional;
import net.fabricmc.loader.api.Version;

/**
 * What a hosted mod declares about itself.
 *
 * <p>A view over OmniLoader's canonical model, not a second copy of it. Mods introspect this to
 * make decisions — "is that other mod new enough" — so it must answer about the whole pack
 * consistently with what the resolver actually decided, whichever ecosystem a given mod came
 * from (ADR-0020).
 */
public interface ModMetadata {

    /** The mod id. */
    String getId();

    /** Ids this mod also provides. */
    Collection<String> getProvides();

    /** The version. */
    Version getVersion();

    /** Which sides it loads on. */
    ModEnvironment getEnvironment();

    /** Display name. */
    String getName();

    /** Description. */
    String getDescription();

    /** Credited authors. */
    Collection<Person> getAuthors();

    /** Credited contributors. */
    Collection<Person> getContributors();

    /** Contact links. */
    ContactInformation getContact();

    /** Declared licences. */
    Collection<String> getLicense();

    /** The icon path for a given size, if one is declared. */
    Optional<String> getIconPath(int size);

    /** Whether a namespaced custom value is present. */
    boolean containsCustomValue(String key);

    /** A namespaced custom value. */
    CustomValue getCustomValue(String key);

    /** Whether this mod's type is the ordinary one. */
    default String getType() {
        return "fabric";
    }
}
