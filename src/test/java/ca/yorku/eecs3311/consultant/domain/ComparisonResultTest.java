package ca.yorku.eecs3311.consultant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ComparisonResultTest {

    @Test
    void narrativeIsMissingUntilTheAgentSuppliesOne() {
        ComparisonResult r = new ComparisonResult("A Paper");
        assertTrue(r.isNarrativeMissing(),
                "F07 must still render a verdict when the LLM is unavailable");
        r.setNarrative("Their metric differs from yours.");
        assertFalse(r.isNarrativeMissing());
    }

    @Test
    void blankEntriesAreIgnoredAndValuesAreTrimmed() {
        ComparisonResult r = new ComparisonResult("A Paper");
        r.addSimilarity("  same dataset  ");
        r.addSimilarity("");
        r.addSimilarity(null);
        r.addDifference("different metric");
        r.addMismatch("metric: Recall@20 vs nDCG@10");
        assertEquals(List.of("same dataset"), r.getSimilarities());
        assertEquals(List.of("different metric"), r.getDifferences());
        assertEquals(List.of("metric: Recall@20 vs nDCG@10"), r.getMismatches());
    }

    @Test
    void aMismatchCanBeRecordedAlongsideAnUndeterminedVerdict() {
        ComparisonResult r = new ComparisonResult("A Paper");
        r.setComparability(Comparability.CANNOT_DETERMINE);
        r.addMismatch("split: not stated in the paper");
        assertEquals(Comparability.CANNOT_DETERMINE, r.getComparability());
        assertEquals(1, r.getMismatches().size(),
                "the specific unknown must survive even when no verdict is reached");
    }
}
