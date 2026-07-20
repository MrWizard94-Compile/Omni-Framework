package org.omniframework.loader.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The diagnostic contract every other module reports through (LDR-10). */
class IssueTest {

    private static OmniIssue sampleError() {
        return OmniIssue.error("manifest.wrong-type")
                .at("depends.somelib.version")
                .with("string", "number")
                .remedy("Expected string here but found number.")
                .build();
    }

    @Test
    @DisplayName("an issue carries code, location, arguments and remedy separately")
    void carriesStructuredParts() {
        OmniIssue issue = sampleError();
        // Kept separate so a UI can translate rather than echo the loader's English.
        assertEquals("manifest.wrong-type", issue.code());
        assertEquals("depends.somelib.version", issue.location());
        assertEquals(List.of("string", "number"), issue.arguments());
        assertFalse(issue.remedy().isBlank(), "an issue without a remedy has done half its job");
        assertTrue(issue.blocksLoading());
    }

    @Test
    @DisplayName("the fallback rendering names the location and the remedy")
    void describeIsUsableInLogs() {
        String text = sampleError().describe();
        assertTrue(text.contains("depends.somelib.version"), text);
        assertTrue(text.contains("Expected string"), text);
    }

    @Test
    @DisplayName("warnings never block loading")
    void warningsDoNotBlock() {
        OmniIssue warning = OmniIssue.warning("manifest.unknown-key")
                .at("futureKey")
                .remedy("Ignored.")
                .build();
        assertFalse(warning.blocksLoading());
        assertFalse(IssueLevel.WARNING.blocksLoading());
        assertTrue(IssueLevel.ERROR.blocksLoading());
    }

    @Test
    @DisplayName("an issue must have a code")
    void codeIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> OmniIssue.error("  ").at("x").remedy("y").build());
    }

    @Test
    @DisplayName("arguments are defensively copied")
    void argumentsAreImmutable() {
        OmniIssue issue = sampleError();
        assertThrows(UnsupportedOperationException.class, () -> issue.arguments().add("mutated"));
    }

    @Test
    @DisplayName("a collector gathers everything before deciding to fail")
    void collectorGathersEverything() {
        IssueCollector collector = new IssueCollector();
        assertTrue(collector.isEmpty());

        collector.add(OmniIssue.warning("a").at("x").remedy("r").build());
        collector.add(sampleError());
        collector.add(OmniIssue.error("b").at("y").remedy("r").build());

        // The point of collecting: one launch reports every problem, not the first.
        assertEquals(3, collector.issues().size());
        assertEquals(2, collector.errors().size());
        assertTrue(collector.hasErrors());
    }

    @Test
    @DisplayName("merging supports parallel discovery")
    void collectorsMerge() {
        IssueCollector first = new IssueCollector();
        first.add(sampleError());
        IssueCollector second = new IssueCollector();
        second.add(OmniIssue.warning("w").at("x").remedy("r").build());

        first.addAll(second);
        assertEquals(2, first.issues().size());
    }

    @Test
    @DisplayName("throwing carries every issue, and adopts the first cause")
    void throwingCarriesEverything() {
        IssueCollector collector = new IssueCollector();
        RuntimeException root = new RuntimeException("underlying");
        collector.add(OmniIssue.error("first").at("a").remedy("r").causedBy(root).build());
        collector.add(OmniIssue.error("second").at("b").remedy("r").build());

        OmniIssueException thrown = assertThrows(OmniIssueException.class,
                () -> collector.throwIfErrors("could not read the mod"));

        assertEquals(2, thrown.issues().size(), "the exception is not just the first problem");
        assertSame(root, thrown.getCause());
        assertTrue(thrown.getMessage().contains("could not read the mod"));
        assertTrue(thrown.getMessage().contains("first"));
        assertTrue(thrown.getMessage().contains("second"));
    }

    @Test
    @DisplayName("a collector with only warnings does not throw")
    void warningsAloneDoNotThrow() {
        IssueCollector collector = new IssueCollector();
        collector.add(OmniIssue.warning("w").at("x").remedy("r").build());
        collector.throwIfErrors("should not throw");
    }
}
