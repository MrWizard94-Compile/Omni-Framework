package net.fabricmc.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or member as existing only on one side.
 *
 * <p>Retained at CLASS level rather than RUNTIME, matching upstream: the loader's
 * environment-stripping pass reads it from the class file ahead of time, so a stripped member is
 * gone before anything could reflect on it.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
public @interface Environment {
    /** The side this element belongs to. */
    EnvType value();
}
