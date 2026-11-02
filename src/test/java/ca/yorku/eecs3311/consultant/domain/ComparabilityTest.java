package ca.yorku.eecs3311.consultant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ComparabilityTest {

    @Test
    void onlyComparableCountsAsYes() {
        assertTrue(Comparability.COMPARABLE.isComparable());
        assertFalse(Comparability.NOT_COMPARABLE.isComparable());
        assertFalse(Comparability.CANNOT_DETERMINE.isComparable(),
                "an undetermined verdict must never read as comparable");
    }

    @Test
    void everyValueHasAReadableLabel() {
        assertEquals("COMPARABLE", Comparability.COMPARABLE.label());
        assertEquals("NOT COMPARABLE", Comparability.NOT_COMPARABLE.label());
        assertEquals("CANNOT DETERMINE", Comparability.CANNOT_DETERMINE.label());
    }

    @Test
    void comparisonResultDefaultsToCannotDetermine() {
        assertEquals(Comparability.CANNOT_DETERMINE, new ComparisonResult().getComparability(),
                "an unset verdict must not default to a claim either way");
    }

    @Test
    void nullVerdictFallsBackToCannotDetermineRatherThanNull() {
        ComparisonResult r = new ComparisonResult("A Paper");
        r.setComparability(Comparability.COMPARABLE);
        r.setComparability(null);
        assertEquals(Comparability.CANNOT_DETERMINE, r.getComparability());
    }
}
