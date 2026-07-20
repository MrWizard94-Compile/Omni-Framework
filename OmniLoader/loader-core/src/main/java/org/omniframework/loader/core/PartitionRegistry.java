package org.omniframework.loader.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.omniframework.loader.core.resource.ResourceRoot;
import org.omniframework.loader.diag.IssueCollector;
import org.omniframework.loader.diag.OmniIssue;

/**
 * The ordered set of partitions the classloader searches, plus the package-claim diagnostics
 * that make conflicts legible (LDR-4).
 *
 * <p><b>Resolution order is first-wins, and the order is meaningful.</b> Partitions are searched
 * in the order added: game first, then mods in resolved load order, then libraries. A mod that
 * ships a class the game also has therefore does <em>not</em> silently shadow the game — the
 * game wins, and the overlap is reported.
 *
 * <p><b>Package claims are diagnosed, not enforced.</b> ARCHITECTURE §4 is explicit that JPMS
 * module layers must not be required of mods, so two mods sharing a package is legal and
 * sometimes intentional (a mod and its own bundled library). It is also the signature of a real
 * and confusing failure: two copies of the same library at different versions, where which one
 * you get depends on load order. Reporting it as a warning that names both claimants turns a
 * mystery into a sentence.
 */
public final class PartitionRegistry implements java.io.Closeable {

    private final List<Partition> partitions = new ArrayList<>();
    /** Class name → the partition that provides it. Populated lazily, cached forever. */
    private final Map<String, Partition> classOwners = new LinkedHashMap<>();

    /**
     * Adds a partition to the end of the search order.
     *
     * @throws IllegalStateException if a partition with the same id is already present
     */
    public void add(Partition partition) {
        Objects.requireNonNull(partition, "partition");
        boolean duplicate = partitions.stream().anyMatch(existing -> existing.id().equals(partition.id()));
        if (duplicate) {
            throw new IllegalStateException("duplicate partition id: " + partition.id());
        }
        partitions.add(partition);
    }

    /** The partitions, in search order. */
    public List<Partition> partitions() {
        return List.copyOf(partitions);
    }

    /**
     * The partition that provides a class, if any — first in search order wins.
     *
     * <p>Results are cached, including the fact that the search order settled on a particular
     * owner, so a class cannot resolve to different partitions at different times.
     */
    public synchronized Optional<Partition> ownerOf(String binaryName) {
        Objects.requireNonNull(binaryName, "binaryName");
        Partition cached = classOwners.get(binaryName);
        if (cached != null) {
            return Optional.of(cached);
        }
        String path = ResourceRoot.classPath(binaryName);
        for (Partition partition : partitions) {
            if (partition.root().contains(path)) {
                classOwners.put(binaryName, partition);
                return Optional.of(partition);
            }
        }
        return Optional.empty();
    }

    /** Reads a resource from the first partition that has it. */
    public Optional<byte[]> readResource(String path) {
        Objects.requireNonNull(path, "path");
        for (Partition partition : partitions) {
            Optional<byte[]> bytes = partition.root().read(path);
            if (bytes.isPresent()) {
                return bytes;
            }
        }
        return Optional.empty();
    }

    /**
     * Reports every package claimed by more than one <em>mod</em> partition.
     *
     * <p>Library and game overlaps are not reported: a library appearing in several mods'
     * bundled jars is ordinary, and the game sharing packages with nothing is the norm. Only
     * mod-versus-mod overlap carries the "which copy did I get?" hazard.
     */
    public void diagnosePackageClaims(IssueCollector issues) {
        Objects.requireNonNull(issues, "issues");
        Map<String, List<String>> claimants = new TreeMap<>();
        for (Partition partition : partitions) {
            if (partition.kind() != Partition.Kind.MOD) {
                continue;
            }
            for (String pkg : new TreeSet<>(partition.root().packages())) {
                claimants.computeIfAbsent(pkg, ignored -> new ArrayList<>()).add(partition.id());
            }
        }
        claimants.forEach((pkg, owners) -> {
            if (owners.size() > 1) {
                issues.add(OmniIssue.warning("partition.package-claimed-twice")
                        .at(pkg.isEmpty() ? "(default package)" : pkg.replace('/', '.'))
                        .with(String.join(", ", owners))
                        .remedy("These mods define classes in the same package: "
                                + String.join(", ", owners)
                                + ". Whichever loads first wins, so behaviour can change with "
                                + "load order. If they bundle the same library, one of them "
                                + "should depend on it instead.")
                        .build());
            }
        });
    }

    /**
     * Closes every partition's root, reporting the first failure after trying them all.
     *
     * <p>Every root is attempted even if an earlier one fails, because leaking the rest to
     * report the first is the wrong trade — on Windows an unclosed jar handle locks the file,
     * so a partial close leaves files the user cannot delete or update.
     */
    @Override
    public void close() throws java.io.IOException {
        java.io.IOException first = null;
        for (Partition partition : partitions) {
            try {
                partition.root().close();
            } catch (java.io.IOException failure) {
                if (first == null) {
                    first = failure;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
