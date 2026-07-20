package net.fabricmc.loader.api.metadata;

import java.util.Map;
import java.util.Optional;

/** Contact links a mod or person declares. */
public interface ContactInformation {

    /** Empty contact information, for the common case of none declared. */
    ContactInformation EMPTY = new ContactInformation() {
        @Override
        public Optional<String> get(String key) {
            return Optional.empty();
        }

        @Override
        public Map<String, String> asMap() {
            return Map.of();
        }
    };

    /** One link by key, e.g. {@code homepage} or {@code issues}. */
    Optional<String> get(String key);

    /** Every link. */
    Map<String, String> asMap();
}
