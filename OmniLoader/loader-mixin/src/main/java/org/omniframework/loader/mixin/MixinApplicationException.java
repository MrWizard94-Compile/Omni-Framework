package org.omniframework.loader.mixin;

import java.io.Serial;
import java.util.Objects;

/**
 * A mixin could not be applied to a class, and the class load is failing because of it.
 *
 * <p>Deliberately fatal. The tempting alternative — log the failure and define the untransformed
 * class — produces a mod that loads successfully and then misbehaves only along the paths its
 * mixin was meant to change. There is no error at that point and no way for a user to connect
 * the symptom to the cause, so a loud failure here is the kinder outcome.
 *
 * <p>Carries the target class name so the report names something actionable rather than
 * surfacing as an anonymous failure several frames inside Mixin.
 */
public final class MixinApplicationException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final String className;

    /** Records a failure applying mixins to {@code className}. */
    public MixinApplicationException(String className, Throwable cause) {
        super("failed to apply mixins to " + Objects.requireNonNull(className, "className")
                + " — a mod's mixin targets it and could not be applied, so the class is not "
                + "being loaded rather than being loaded without it", cause);
        this.className = className;
    }

    /** The class whose transformation failed. */
    public String className() {
        return className;
    }
}
