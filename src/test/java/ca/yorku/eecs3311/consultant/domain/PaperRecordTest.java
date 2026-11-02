package ca.yorku.eecs3311.consultant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaperRecordTest {

    @Test
    void hasRepositoryIsFalseUntilAUrlIsSet() {
        PaperRecord r = new PaperRecord("p1", "A Paper", "ICLR 25", "2025-05");
        assertFalse(r.hasRepository(), "F09 must not call GitHub when no repo is linked");
        r.setRepoUrl("https://github.com/example/repo");
        assertTrue(r.hasRepository());
    }

    @Test
    void nullFieldsBecomeEmptyStringsSoCallersNeverSeeNull() {
        PaperRecord r = new PaperRecord(null, null, null, null);
        assertEquals("", r.getTitle());
        assertEquals("", r.getVenue());
        assertFalse(r.hasRepository());
    }

    @Test
    void recordsWithTheSameIdAndTitleAreEqual() {
        PaperRecord a = new PaperRecord("p1", "A Paper", "ICLR 25", "2025-05");
        PaperRecord b = new PaperRecord("p1", "A Paper", "different venue", "2026-01");
        assertEquals(a, b, "identity is the paper, not the metadata around it");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new PaperRecord("p2", "A Paper", "ICLR 25", "2025-05"));
    }
}
