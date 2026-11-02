package ca.yorku.eecs3311.consultant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DigestTest {

    @Test
    void aDigestWithNothingCollectedIsEmpty() {
        assertTrue(new Digest("2026-11-02").isEmpty(),
                "F12 shows an explicit 'nothing new' instead of a blank view");
    }

    @Test
    void projectChangesAloneMakeADigestWorthShowing() {
        Digest d = new Digest("2026-11-02");
        d.setProjectChanges("switched retriever to dense");
        assertFalse(d.isEmpty());
    }

    @Test
    void thesSamePaperIsNotAddedTwice() {
        Digest d = new Digest("2026-11-02");
        PaperRecord p = new PaperRecord("p1", "A Paper", "ICLR 25", "2025-05");
        d.addPaper(p);
        d.addPaper(new PaperRecord("p1", "A Paper", "other venue", "2026-01"));
        d.addPaper(null);
        assertEquals(1, d.getNewPapers().size(),
                "two listeners reporting the same paper must not duplicate it");
    }

    @Test
    void summaryIsMissingUntilTheAgentWritesOne() {
        Digest d = new Digest("2026-11-02");
        d.setProjectChanges("x");
        assertTrue(d.isSummaryMissing(),
                "the assembled digest is still shown when the LLM is unavailable");
        d.setSummaryText("Three new papers this week.");
        assertFalse(d.isSummaryMissing());
    }
}
