package ca.yorku.eecs3311.consultant.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void theRankingFailureIsStillVisibleOnAStaleFeed() {
        String heading = PaperFeedPanel.staleHeading(
                "12 papers - NOT RANKED, the reply could not be read");

        assertTrue(heading.contains("NOT RANKED"),
                "staleness must not hide a ranking failure: " + heading);
    }
}
