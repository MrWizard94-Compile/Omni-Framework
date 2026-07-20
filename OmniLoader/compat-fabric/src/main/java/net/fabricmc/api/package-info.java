/**
 * Fabric Loader's own API, reimplemented for OmniLoader (CMP-2, contract 5).
 *
 * <h2>Why these classes live in Fabric's namespace</h2>
 *
 * <p>Hosted mods reference {@code net.fabricmc.api.ModInitializer} by name in their compiled
 * constant pools. Nothing can change that without rewriting every jar, which is precisely what
 * CMP-2's metadata-level approach exists to avoid, and which would break every reflective lookup
 * and string reference besides. So the types must appear under the names the bytecode already
 * uses.
 *
 * <p>This is a clean-room reimplementation of a published API surface, written from the public
 * signatures (verified against fabric-loader 0.19.2 with {@code javap}, 2026-07-20). No upstream
 * source is copied. Fabric Loader is Apache-2.0 licensed, which permits this independently of the
 * fact that an API surface is not itself the protectable part.
 *
 * <p><b>Scope note.</b> This package is the loader's API. The much larger
 * {@code net.fabricmc.fabric.api.*} belongs to the Fabric API <em>mod</em>, which OmniLoader
 * hosts as an ordinary jar rather than reimplementing — see
 * {@link org.omniframework.loader.compat.fabric.FabricLoaderSurface}.
 */
package net.fabricmc.api;
