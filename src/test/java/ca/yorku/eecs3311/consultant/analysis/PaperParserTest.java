package ca.yorku.eecs3311.consultant.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import org.junit.jupiter.api.Test;

class PaperParserTest {

    private final PaperParser parser = new PaperParser();

    private static PaperRecord paper(String summary) {
        PaperRecord p = new PaperRecord("1", "A Paper", "arXiv cs.CV", "2026-01-01");
        p.setSummary(summary);
        return p;
    }

    @Test
    void returnsTheAbstract() throws Exception {
        assertEquals("the abstract text", parser.parse(paper("the abstract text")));
    }

    @Test
    void trimsSurroundingWhitespace() throws Exception {
        assertEquals("the abstract text", parser.parse(paper("   the abstract text\n ")));
    }

    @Test
    void aPaperWithNoAbstractIsFlaggedNotGuessedAt() {
        // UC04 3a. Extracting a method from a title alone would be fabrication.
        UnparseablePaperException e = assertThrows(UnparseablePaperException.class,
                () -> parser.parse(paper("")));
        assertTrue(e.getMessage().contains("A Paper"),
                "the message should name the paper: " + e.getMessage());
    }

    @Test
    void aNullAbstractIsTreatedTheSameAsAnEmptyOne() {
        assertThrows(UnparseablePaperException.class, () -> parser.parse(paper(null)));
    }

    @Test
    void rejectsANullPaper() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(null));
    }
}
