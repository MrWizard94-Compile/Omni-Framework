package net.fabricmc.loader.api.metadata;

/** A named person credited by a mod. */
public interface Person {

    /** Their name. */
    String getName();

    /** How to reach them. */
    ContactInformation getContact();
}
