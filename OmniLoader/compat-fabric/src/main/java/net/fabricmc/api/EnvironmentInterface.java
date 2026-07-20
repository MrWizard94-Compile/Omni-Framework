package net.fabricmc.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks an implemented interface as present only on one side. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@Repeatable(EnvironmentInterfaces.class)
public @interface EnvironmentInterface {
    /** The side the interface belongs to. */
    EnvType value();

    /** The interface itself. */
    Class<?> itf();
}
