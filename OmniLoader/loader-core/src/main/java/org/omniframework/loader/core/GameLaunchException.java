package org.omniframework.loader.core;

import java.io.Serial;

/**
 * The game could not be started, or ended in failure.
 *
 * <p>Separate from {@link org.omniframework.loader.diag.OmniIssueException} because the two mean
 * different things to whoever is reading the log: an issue exception says the loader refused to
 * proceed, this says the loader did its job and the game itself did not run. Conflating them
 * sends people debugging the loader when the problem is a crash in the game.
 */
public final class GameLaunchException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** Creates the exception with a cause, which may be null. */
    public GameLaunchException(String message, Throwable cause) {
        super(message, cause);
    }
}
