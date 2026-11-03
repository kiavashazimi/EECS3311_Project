package ca.yorku.eecs3311.consultant.literature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class QueryTermsTest {

    @Test
    void splitsOnWhitespace() {
        assertEquals(List.of("fairness", "synthetic"), QueryTerms.split("fairness synthetic"));
    }

    @Test
    void keepsAQuotedPhraseWhole() {
        assertEquals(List.of("\"chest X-ray\"", "fairness"),
                QueryTerms.split("\"chest X-ray\" fairness"));
    }

    @Test
    void treatsAnUnclosedQuoteAsRunningToTheEnd() {
        assertEquals(List.of("\"chest X-ray fairness\""),
                QueryTerms.split("\"chest X-ray fairness"));
    }

    @Test
    void anEmptyQueryHasNoTerms() {
        assertEquals(List.of(), QueryTerms.split("   "));
        assertEquals(List.of(), QueryTerms.split(null));
    }

    @Test
    void countsTermsNotWords() {
        assertEquals(2, QueryTerms.count("\"chest X-ray\" fairness"));
    }

    // --- relaxation ----------------------------------------------------------

    @Test
    void dropsTheLastTerm() {
        // terms are ANDed, so a query that matched nothing is widened by
        // removing a requirement rather than reported as "no such work"
        assertEquals("fairness synthetic",
                QueryTerms.dropLastTerm("fairness synthetic imaging"));
    }

    @Test
    void dropsTheLastTermWithoutBreakingAPhrase() {
        assertEquals("\"chest X-ray\" fairness",
                QueryTerms.dropLastTerm("\"chest X-ray\" fairness synthetic"));
    }

    @Test
    void keepsAPhraseIntactWhenItIsTheSurvivor() {
        assertEquals("\"chest X-ray\"", QueryTerms.dropLastTerm("\"chest X-ray\" fairness"));
    }

    @Test
    void aSingleTermCannotBeWidenedFurther() {
        assertTrue(QueryTerms.dropLastTerm("fairness").isEmpty());
        assertTrue(QueryTerms.dropLastTerm("\"chest X-ray\"").isEmpty());
    }

    @Test
    void relaxingRepeatedlyTerminates() {
        String query = "a b c d e";
        int steps = 0;
        while (!query.isBlank() && steps < 20) {
            query = QueryTerms.dropLastTerm(query);
            steps++;
        }
        assertEquals(5, steps, "five terms should take exactly five drops to exhaust");
    }
}
