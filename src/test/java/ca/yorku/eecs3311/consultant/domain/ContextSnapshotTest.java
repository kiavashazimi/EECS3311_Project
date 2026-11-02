package ca.yorku.eecs3311.consultant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextSnapshotTest {

    private static ContextSnapshot snap(List<String> commits, Map<String, String> config, List<String> results) {
        ContextSnapshot s = new ContextSnapshot("2026-11-02", "/home/kia/project");
        s.setRecentCommits(commits);
        s.setConfigEntries(config);
        s.setResultFiles(results);
        return s;
    }

    @Test
    void identicalSnapshotsReportNoChanges() {
        ContextSnapshot a = snap(List.of("c1"), Map.of("chunk_size", "512"), List.of("r1.json"));
        ContextSnapshot b = snap(List.of("c1"), Map.of("chunk_size", "512"), List.of("r1.json"));
        assertTrue(b.changesSince(a).isEmpty(), "F10 must say nothing changed, not invent a change");
    }

    @Test
    void newCommitsAndResultFilesAreReported() {
        ContextSnapshot old = snap(List.of("c1"), Map.of(), List.of());
        ContextSnapshot now = snap(List.of("c1", "c2"), Map.of(), List.of("eval.json"));
        List<String> changes = now.changesSince(old);
        assertTrue(changes.contains("new commit: c2"));
        assertTrue(changes.contains("new result file: eval.json"));
        assertEquals(2, changes.size());
    }

    @Test
    void aRemovedResultFileIsReported() {
        ContextSnapshot old = snap(List.of(), Map.of(), List.of("eval.json"));
        ContextSnapshot now = snap(List.of(), Map.of(), List.of());
        assertEquals(List.of("removed result file: eval.json"), now.changesSince(old));
    }

    @Test
    void aChangedConfigValueShowsBothSides() {
        ContextSnapshot old = snap(List.of(), Map.of("chunk_size", "512"), List.of());
        ContextSnapshot now = snap(List.of(), Map.of("chunk_size", "256"), List.of());
        assertEquals(List.of("config changed: chunk_size: 512 -> 256"), now.changesSince(old));
    }

    @Test
    void withNoPriorSnapshotNothingIsClaimedToHaveChanged() {
        ContextSnapshot now = snap(List.of("c1"), Map.of(), List.of());
        assertTrue(now.changesSince(null).isEmpty(),
                "F10 prompts the user to import first rather than reporting everything as new");
    }

    @Test
    void emptyScanIsDetectable() {
        assertTrue(new ContextSnapshot().isEmpty());
        assertFalse(snap(List.of("c1"), Map.of(), List.of()).isEmpty());
    }
}
