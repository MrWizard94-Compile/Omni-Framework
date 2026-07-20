package net.fabricmc.loader.api.metadata;

import java.util.List;
import java.util.Map;

/**
 * A value from a mod's {@code custom} block.
 *
 * <p>The typed accessors throw rather than coerce. A mod reading a string as a number wants to
 * know it guessed wrong, not to receive a silent zero that surfaces as wrong behaviour later.
 */
public interface CustomValue {

    /** What kind of value this is. */
    CvType getType();

    /** As an object. */
    CvObject getAsObject();

    /** As an array. */
    CvArray getAsArray();

    /** As a string. */
    String getAsString();

    /** As a number. */
    Number getAsNumber();

    /** As a boolean. */
    boolean getAsBoolean();

    /** The kinds a custom value can take. */
    enum CvType {
        /** An object. */
        OBJECT,
        /** An array. */
        ARRAY,
        /** A string. */
        STRING,
        /** A number. */
        NUMBER,
        /** A boolean. */
        BOOLEAN,
        /** JSON null. */
        NULL
    }

    /** An object-valued custom value. */
    interface CvObject extends CustomValue, Iterable<Map.Entry<String, CustomValue>> {
        /** How many members. */
        int size();

        /** Whether a key is present. */
        boolean containsKey(String key);

        /** A member by key. */
        CustomValue get(String key);
    }

    /** An array-valued custom value. */
    interface CvArray extends CustomValue, Iterable<CustomValue> {
        /** How many elements. */
        int size();

        /** An element by index. */
        CustomValue get(int index);

        /** As a list. */
        List<CustomValue> asList();
    }
}
