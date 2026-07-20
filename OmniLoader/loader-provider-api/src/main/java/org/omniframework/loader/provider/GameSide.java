package org.omniframework.loader.provider;

import org.omniframework.loader.model.Environment;

/**
 * Which program is being launched (LDR-9): the client, or the dedicated server.
 *
 * <p>Distinct from {@link Environment} on purpose, even though the two look alike.
 * {@code Environment} is what a <em>mod declares</em> about itself; {@code GameSide} is what the
 * <em>loader is doing right now</em>. Collapsing them would read fine until the day something
 * needs to ask "is this mod's declaration compatible with this launch?" — a question that needs
 * both halves to exist separately to be answerable at all.
 */
public enum GameSide {
    /** The client program. */
    CLIENT,
    /** The dedicated server program. */
    SERVER;

    /** The mod-declaration environment that corresponds to this side. */
    public Environment asEnvironment() {
        return this == CLIENT ? Environment.CLIENT : Environment.SERVER;
    }

    /** Whether a mod declaring {@code declared} should load on this side. */
    public boolean loads(Environment declared) {
        return declared.loadsOn(asEnvironment());
    }
}
