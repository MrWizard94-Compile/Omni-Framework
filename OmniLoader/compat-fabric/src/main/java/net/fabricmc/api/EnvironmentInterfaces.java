package net.fabricmc.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Container for repeated {@link EnvironmentInterface} annotations. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface EnvironmentInterfaces {
    /** The repeated annotations. */
    EnvironmentInterface[] value();
}
