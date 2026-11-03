package ca.yorku.eecs3311.consultant.literature;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Really queries arXiv. Free and keyless, but it needs a network and depends
 * on what arXiv has indexed, so it is skipped unless asked for:
 *
 * <pre>mvn test -Dnet.live=true -Dtest=ArxivLiveTest</pre>
 *
 * <p>Worth keeping despite being skipped by default: every bug this class has
 * found so far — the http-to-https redirect, the silently truncated
 * multi-word query, the HTTP 400 from an unencoded phrase — passed every
 * offline test.
 */
@EnabledIfSystemProperty(named = "net.live", matches = "true")
class ArxivLiveTest {

    private static void show(String label, List<PaperRecord> papers) {
        System.out.println("\n" + label + " -> " + papers.size() + " papers");
        papers.forEach(p -> System.out.println("   " + p.getDate() + "  "
                + (p.getTitle().length() > 76 ? p.getTitle().substring(0, 76) : p.getTitle())));
    }

    @Test
    void aQuotedPhraseFindsOnTopicPapers() throws Exception {
        List<PaperRecord> papers = new ArxivSource(5)
                .search("\"chest X-ray\" fairness synthetic", SearchIntent.MOST_RELEVANT);
        show("\"chest X-ray\" fairness synthetic", papers);

        assertFalse(papers.isEmpty(), "a real topic should match something");
        long onTopic = papers.stream()
                .filter(p -> {
                    String text = (p.getTitle() + " " + p.getSummary()).toLowerCase();
                    return text.contains("fair") || text.contains("bias");
                })
                .count();
        assertTrue(onTopic >= papers.size() / 2,
                "most results should be about fairness or bias, got " + onTopic + "/" + papers.size());
    }

    @Test
    void aCategoryFilterIsHonoured() throws Exception {
        List<PaperRecord> papers = new ArxivSource(5)
                .search("cat:eess.IV fairness synthetic", SearchIntent.MOST_RELEVANT);
        show("cat:eess.IV fairness synthetic", papers);
        assertFalse(papers.isEmpty(), "the category filter should still match papers");
    }

    @Test
    void theTwoIntentsReturnDifferentOrderings() throws Exception {
        String query = "\"chest X-ray\" fairness";
        List<PaperRecord> newest = new ArxivSource(5).search(query, SearchIntent.NEWEST);
        List<PaperRecord> relevant = new ArxivSource(5).search(query, SearchIntent.MOST_RELEVANT);

        show("NEWEST", newest);
        show("MOST_RELEVANT", relevant);

        assertFalse(newest.isEmpty());
        assertFalse(relevant.isEmpty());
        for (PaperRecord p : newest) {
            assertFalse(p.getTitle().contains("\n"), "titles must be one line");
        }
    }
}
