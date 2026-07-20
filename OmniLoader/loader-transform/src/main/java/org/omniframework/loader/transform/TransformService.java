package org.omniframework.loader.transform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.transform.mapping.ClassIndex;
import org.omniframework.loader.transform.mapping.MappingSet;

/**
 * The "transform ensure" step of the boot sequence (ARCHITECTURE §3).
 *
 * <p>Ties the three pieces together: compute the key, look in the cache, and on a miss run the
 * pipeline and store the result. Callers ask for a usable jar and do not care which happened —
 * which is the point, because on the second launch of a pack nearly every jar is a hit and the
 * pipeline never runs at all.
 *
 * <h2>Misses run in parallel; the cache is what makes that safe</h2>
 *
 * <p>A first launch of a 300-mod pack has 300 misses, and transformation is CPU-bound, so they
 * are spread across cores. Two threads that happen to compute the same key would each transform
 * and each store — wasteful but not incorrect, because the key guarantees their outputs are
 * equivalent and {@link TransformCache#store} replaces atomically. That is why no lock is needed
 * on the write path: correctness comes from the key, not from exclusion.
 */
public final class TransformService {

    private final TransformCache cache;
    private final TransformPipeline pipeline;
    private final String loaderVersion;
    private final Path workDirectory;

    /**
     * Builds a service over a cache and pipeline.
     *
     * @param workDirectory where jars are built before being moved into the cache
     */
    public TransformService(
            TransformCache cache,
            TransformPipeline pipeline,
            String loaderVersion,
            Path workDirectory) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.loaderVersion = Objects.requireNonNull(loaderVersion, "loaderVersion");
        this.workDirectory = Objects.requireNonNull(workDirectory, "workDirectory");
    }

    /** What to transform, and everything that identifies the result. */
    public record Request(
            String modId,
            Path jar,
            MappingSet mappings,
            String mappingsArtifactId,
            String mappingsHash,
            String layerId,
            String layerVersion,
            PassContext.GameSideTarget side,
            Map<String, String> flags) {

        /** Canonical constructor; defensively copies. */
        public Request {
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(jar, "jar");
            Objects.requireNonNull(mappings, "mappings");
            Objects.requireNonNull(mappingsArtifactId, "mappingsArtifactId");
            Objects.requireNonNull(mappingsHash, "mappingsHash");
            Objects.requireNonNull(layerId, "layerId");
            Objects.requireNonNull(layerVersion, "layerVersion");
            Objects.requireNonNull(side, "side");
            flags = Map.copyOf(Objects.requireNonNull(flags, "flags"));
        }

        /** A request for a native Omni jar: already official-named, no compat layer. */
        public static Request native_(
                String modId, Path jar, PassContext.GameSideTarget side) {
            return new Request(modId, jar, MappingSet.identity(), "none", "none",
                    "native", "0", side, Map.of());
        }
    }

    /** The outcome of ensuring one jar. */
    public record Ensured(CacheKey key, CacheEntry entry, boolean wasHit, String modId) {

        /** Canonical constructor. */
        public Ensured {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(modId, "modId");
        }

        /** The transformed jar to put on the classloader's search path. */
        public Path jar() {
            return entry.jar();
        }
    }

    /**
     * Returns a transformed jar for {@code request}, transforming only if the cache misses.
     */
    public Ensured ensure(Request request, ClassIndex hierarchy, IssueCollector issues) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(issues, "issues");

        CacheKey.Builder builder;
        try {
            builder = CacheKey.builder()
                    .jar(request.jar())
                    .mappings(request.mappingsArtifactId(), request.mappingsHash())
                    .pipelineVersion(pipeline.pipelineVersion())
                    .layer(request.layerId(), request.layerVersion())
                    .flag("side", request.side().lowerName());
        } catch (IOException e) {
            throw new TransformException("cannot read " + request.jar() + " to key it", e);
        }
        request.flags().forEach(builder::flag);
        CacheKey key = builder.build();

        Optional<CacheEntry> hit = cache.lookup(key);
        if (hit.isPresent()) {
            return new Ensured(key, hit.get(), true, request.modId());
        }

        Path staging = null;
        try {
            Files.createDirectories(workDirectory);
            staging = Files.createTempFile(workDirectory, "transform-", ".jar");
            PassContext context = new PassContext(
                    request.jar().getFileName().toString(), request.modId(),
                    request.mappings(), hierarchy, request.side(), issues);
            TransformPipeline.Result result = pipeline.run(request.jar(), staging, context);
            EntryMeta meta = new EntryMeta(
                    request.jar().getFileName().toString(),
                    builder.describeInputs(),
                    pipeline.pipelineVersion(),
                    loaderVersion,
                    Instant.now(),
                    result.entryCount(),
                    result.modifiedCount());
            CacheEntry stored = cache.store(key, staging, result.audit(), meta);
            return new Ensured(key, stored, false, request.modId());
        } catch (IOException e) {
            throw new TransformException("cannot transform " + request.jar(), e);
        } finally {
            deleteQuietly(staging);
        }
    }

    /**
     * Ensures many jars, transforming misses in parallel.
     *
     * <p>Results come back in request order regardless of completion order, because load order is
     * decided by the resolver and must not be perturbed by how fast each jar happened to build.
     */
    public List<Ensured> ensureAll(
            List<Request> requests, ClassIndex hierarchy, IssueCollector issues) {
        Objects.requireNonNull(requests, "requests");
        if (requests.isEmpty()) {
            return List.of();
        }
        int threads = Math.min(requests.size(), Runtime.getRuntime().availableProcessors());
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Ensured>> futures = new ArrayList<>(requests.size());
            for (Request request : requests) {
                futures.add(pool.submit(() -> ensure(request, hierarchy, issues)));
            }
            List<Ensured> results = new ArrayList<>(requests.size());
            for (Future<Ensured> future : futures) {
                results.add(await(future));
            }
            return results;
        }
    }

    private static Ensured await(Future<Ensured> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransformException("transformation was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TransformException transformFailure) {
                // Preserve the typed failure, which already names the jar and entry.
                throw transformFailure;
            }
            throw new TransformException("transformation failed", cause);
        }
    }

    /** Statistics for the boot log: how much of a launch was cache hits. */
    public static Summary summarise(List<Ensured> results) {
        AtomicInteger hits = new AtomicInteger();
        results.forEach(result -> {
            if (result.wasHit()) {
                hits.incrementAndGet();
            }
        });
        return new Summary(results.size(), hits.get());
    }

    /** How a boot's transform phase went. */
    public record Summary(int total, int hits) {

        /** Jars that had to be transformed. */
        public int misses() {
            return total - hits;
        }

        /** A line for the boot log. */
        public String describe() {
            if (total == 0) {
                return "no jars required transformation";
            }
            return hits + " of " + total + " jars served from cache, " + misses() + " transformed";
        }
    }

    /** Index of every class across a set of jars, for hierarchy-aware remapping. */
    public static ClassIndex indexAll(List<Path> jars) {
        ClassIndex index = new ClassIndex();
        for (Path jar : jars) {
            for (JarResource resource : TransformPipeline.entriesOf(jar)) {
                if (resource.isClass()) {
                    index.add(resource.content());
                }
            }
        }
        return index;
    }

    /** The transform history of a class, across every cached jar. */
    public List<TransformCache.ClassHistory> auditClass(String className) {
        return cache.auditClass(className);
    }

    /** Startup maintenance: clear interrupted writes and entries from older pipelines. */
    public Map<String, Integer> maintain(long maxBytes) {
        Map<String, Integer> removed = new LinkedHashMap<>();
        removed.put("incomplete", cache.sweepIncompleteWrites());
        removed.put("staleVersion", cache.sweepStaleVersions(pipeline.pipelineVersion()));
        removed.put("evicted", cache.evictToFit(maxBytes));
        return removed;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover temp file is harmless and swept on the next boot.
        }
    }
}
