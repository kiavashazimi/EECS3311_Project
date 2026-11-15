package ca.yorku.eecs3311.consultant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import org.junit.jupiter.api.Test;

/**
 * The feed's heading text, tested without a JavaFX toolkit.
 *
 * <p>Only the string composition is covered here. Building a {@code ListView}
 * needs a started toolkit, which is why there are no other tests in this
 * package; the heading was pulled out into a static method precisely so this
 * part could be checked.
 */
class PaperFeedPanelTest {

    @Test
    void aStaleFeedSaysSoWithoutLosingWhatTheHeadingAlreadySaid() {
        String heading = PaperFeedPanel.staleHeading("12 papers, most useful first");

        assertTrue(heading.contains("12 papers, most useful first"),
                "the paper count must survive: " + heading);
        assertTrue(heading.toLowerCase().contains("last discovery"),
                "the heading must say the list is older than the answer beside it: " + heading);
    }

    @Test
    void aFeedIsNotMarkedStaleTwice() {
        String once = PaperFeedPanel.staleHeading("12 papers, most useful first");

        assertEquals(once, PaperFeedPanel.staleHeading(once),
                "asking twice must not stack prefixes");
    }

    @Test
    void anAnalysedPaperIsMarkedInTheFeedRatherThanRemovedFromIt() {
        // Removing it would make "Compare to my project" and "Check code"
        // unreachable: both are only enabled for an analysed paper, which has
        // to be selectable in this list to be selected at all.
        RankedPaper entry = new RankedPaper(
                new PaperRecord("1", "FairDiffusion", "arXiv cs.CV", "2026-02-01"), 90, "close", false);

        String marked = PaperFeedPanel.rowText(entry, true);
        String plain = PaperFeedPanel.rowText(entry, false);

        assertTrue(marked.contains("FairDiffusion"),
                "the paper stays in the list: " + marked);
        assertTrue(marked.contains(PaperFeedPanel.ANALYSED_MARKER),
                "and says it has been analysed: " + marked);
        assertFalse(plain.contains(PaperFeedPanel.ANALYSED_MARKER),
                "an unanalysed paper carries no marker: " + plain);
    }

    @Test
    void theRankingFailureIsStillVisibleOnAStaleFeed() {
        String heading = PaperFeedPanel.staleHeading(
                "12 papers - NOT RANKED, the reply could not be read");

        assertTrue(heading.contains("NOT RANKED"),
                "staleness must not hide a ranking failure: " + heading);
    }
}
