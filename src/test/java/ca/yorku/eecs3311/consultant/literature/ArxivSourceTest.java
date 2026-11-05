package ca.yorku.eecs3311.consultant.literature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Parsing tests driven by canned arXiv Atom XML. No network: the feed arrives
 * through an injected fetcher, so these assertions hold offline and cannot
 * break because arXiv changed what it has indexed today.
 */
class ArxivSourceTest {

    /** A realistic two-entry feed, including the whitespace arXiv really sends. */
    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title type="html">ArXiv Query: search_query=all:rag</title>
              <opensearch:totalResults>2</opensearch:totalResults>
              <entry>
                <id>http://arxiv.org/abs/2301.12345v2</id>
                <published>2023-01-29T18:00:00Z</published>
                <updated>2023-02-02T09:12:00Z</updated>
                <title>Cross-lingual Retrieval-Augmented
                  Generation for Low-Resource QA</title>
                <summary>  We study whether retrieval helps
                  low-resource question answering.
                </summary>
                <author><name>A. Researcher</name></author>
                <author><name>B. Coauthor</name></author>
                <arxiv:comment>9 pages. Code at https://github.com/example/xrag</arxiv:comment>
                <arxiv:journal_ref>Proceedings of ACL 2023</arxiv:journal_ref>
                <link href="http://arxiv.org/abs/2301.12345v2" rel="alternate" type="text/html"/>
                <link title="pdf" href="http://arxiv.org/pdf/2301.12345v2" rel="related" type="application/pdf"/>
                <arxiv:primary_category term="cs.CL"/>
              </entry>
              <entry>
                <id>http://arxiv.org/abs/2402.00001v1</id>
                <published>2024-02-01T00:00:00Z</published>
                <title>A Second Paper</title>
                <summary>No repository here.</summary>
                <author><name>C. Author</name></author>
                <link href="http://arxiv.org/abs/2402.00001v1" rel="alternate" type="text/html"/>
                <arxiv:primary_category term="cs.LG"/>
              </entry>
            </feed>
            """;

    private static ArxivSource sourceReturning(String xml) {
        return new ArxivSource(url -> xml);
    }

    // --- the happy path ------------------------------------------------------

    @Test
    void parsesEveryEntry() throws Exception {
        List<PaperRecord> papers = sourceReturning(FEED).search("rag");
        assertEquals(2, papers.size());
    }

    @Test
    void extractsTheArxivIdNotTheWholeUrl() throws Exception {
        assertEquals("2301.12345v2", sourceReturning(FEED).search("rag").get(0).getId());
    }

    @Test
    void collapsesTheWhitespaceArxivPutsInTitles() throws Exception {
        // arXiv wraps titles across lines with indentation; left alone this
        // would show up in the GUI as a title with a newline in the middle
        assertEquals("Cross-lingual Retrieval-Augmented Generation for Low-Resource QA",
                sourceReturning(FEED).search("rag").get(0).getTitle());
    }

    @Test
    void collapsesAndTrimsTheAbstract() throws Exception {
        assertEquals("We study whether retrieval helps low-resource question answering.",
                sourceReturning(FEED).search("rag").get(0).getSummary());
    }

    @Test
    void takesTheDateOnlyFromPublished() throws Exception {
        assertEquals("2023-01-29", sourceReturning(FEED).search("rag").get(0).getDate());
    }

    @Test
    void prefersTheJournalReferenceAsTheVenue() throws Exception {
        assertEquals("Proceedings of ACL 2023",
                sourceReturning(FEED).search("rag").get(0).getVenue());
    }

    @Test
    void fallsBackToThePrimaryCategoryWhenUnpublished() throws Exception {
        assertEquals("arXiv cs.LG", sourceReturning(FEED).search("rag").get(1).getVenue());
    }

    @Test
    void keepsTheAbstractPageUrl() throws Exception {
        assertEquals("http://arxiv.org/abs/2301.12345v2",
                sourceReturning(FEED).search("rag").get(0).getUrl());
    }

    @Test
    void findsARepositoryMentionedInTheComment() throws Exception {
        // F09 needs a repository URL, and arXiv has no field for one - authors
        // put it in the comment
        PaperRecord first = sourceReturning(FEED).search("rag").get(0);
        assertEquals("https://github.com/example/xrag", first.getRepoUrl());
        assertTrue(first.hasRepository());
    }

    @Test
    void leavesTheRepositoryEmptyWhenNoneIsMentioned() throws Exception {
        assertFalse(sourceReturning(FEED).search("rag").get(1).hasRepository());
    }

    // --- empty and broken replies -------------------------------------------

    @Test
    void anEmptyFeedIsAnEmptyListNotAnError() throws Exception {
        // UC03 flow 5a: "no new papers" is a state, not a failure
        String empty = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <opensearch:totalResults>0</opensearch:totalResults>
                </feed>
                """;
        assertTrue(sourceReturning(empty).search("nothing matches this").isEmpty());
    }

    @Test
    void unreadableXmlIsReportedAsUnavailable() {
        ArxivSource source = sourceReturning("<feed><entry>truncated...");
        LiteratureUnavailableException e = assertThrows(LiteratureUnavailableException.class,
                () -> source.search("rag"));
        assertTrue(e.getMessage().toLowerCase().contains("arxiv"),
                "the message should say which source failed, got: " + e.getMessage());
    }

    @Test
    void aFailedFetchIsReportedAsUnavailable() {
        ArxivSource source = new ArxivSource(url -> {
            throw new java.io.IOException("connect timed out");
        });
        assertThrows(LiteratureUnavailableException.class, () -> source.search("rag"));
    }

    @Test
    void anEntryMissingItsIdIsSkippedRatherThanBreakingTheWholeSearch() throws Exception {
        String partlyBroken = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry><title>No id at all</title></entry>
                  <entry>
                    <id>http://arxiv.org/abs/2402.00001v1</id>
                    <published>2024-02-01T00:00:00Z</published>
                    <title>A Good Entry</title>
                  </entry>
                </feed>
                """;
        List<PaperRecord> papers = sourceReturning(partlyBroken).search("rag");
        assertEquals(1, papers.size(), "one bad entry must not lose the good ones");
        assertEquals("A Good Entry", papers.get(0).getTitle());
    }

    // --- the parser must not be a way into this machine ---------------------

    @Test
    void refusesXmlThatDeclaresADoctype() {
        // XXE: arXiv's reply is untrusted input. A DOCTYPE can pull in local
        // files or hang the parser, so it is rejected outright.
        String withDoctype = """
                <?xml version="1.0"?>
                <!DOCTYPE feed [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry><id>http://arxiv.org/abs/1</id><title>&xxe;</title></entry>
                </feed>
                """;
        assertThrows(LiteratureUnavailableException.class,
                () -> sourceReturning(withDoctype).search("rag"));
    }

    // --- query handling -----------------------------------------------------

    @Test
    void rejectsABlankQuery() {
        ArxivSource source = sourceReturning(FEED);
        assertThrows(IllegalArgumentException.class, () -> source.search("  "));
        assertThrows(IllegalArgumentException.class, () -> source.search(null));
    }

    private static String urlFor(String query) throws Exception {
        return urlFor(query, SearchIntent.MOST_RELEVANT);
    }

    private static String urlFor(String query, SearchIntent intent) throws Exception {
        StringBuilder requested = new StringBuilder();
        new ArxivSource(url -> {
            requested.append(url);
            return FEED;
        }).search(query, intent);
        return requested.toString();
    }

    @Test
    void everyTermIsAndedAgainstTheAbstract() throws Exception {
        // one all: term containing spaces makes arXiv keep the first word and
        // silently drop the rest, returning the newest papers on all of arXiv
        assertTrue(urlFor("fairness synthetic chest")
                        .contains("abs:fairness+AND+abs:synthetic+AND+abs:chest"),
                urlFor("fairness synthetic chest"));
    }

    @Test
    void searchesTheAbstractNotTheFullText() throws Exception {
        // all: covers full text and author names, so all:chest matches a paper
        // that merely cites someone in a chest-imaging journal
        String url = urlFor("fairness");
        assertTrue(url.contains("abs:fairness"), url);
        assertFalse(url.contains("all:"), url);
    }

    @Test
    void aQuotedPhraseStaysOneTerm() throws Exception {
        String url = urlFor("\"chest X-ray\" fairness");
        assertTrue(url.contains("abs:%22chest%20X-ray%22"),
                "the phrase must survive as one quoted term: " + url);
        assertTrue(url.contains("+AND+abs:fairness"), url);
        assertFalse(url.contains("abs:chest+AND+abs:X-ray"),
                "splitting the phrase loses the papers that matter: " + url);
    }

    @Test
    void aPhraseIsEncodedForTheWireNotLeftRaw() throws Exception {
        // a raw quote or space in the query string is an HTTP 400 from arXiv
        String url = urlFor("\"synthetic data\"");
        assertFalse(url.contains("\""), "a raw quote would be rejected: " + url);
        assertFalse(url.contains(" "), "a raw space would be rejected: " + url);
    }

    @Test
    void anArxivFieldPrefixIsPassedThrough() throws Exception {
        // lets a query narrow to a subject area: cat:eess.IV cut the noise
        // noticeably on a medical-imaging topic
        String url = urlFor("cat:eess.IV fairness");
        assertTrue(url.contains("cat:eess.IV"), url);
        assertFalse(url.contains("abs:cat"), "the prefix must not be treated as a word: " + url);
    }

    @Test
    void anUnknownPrefixIsTreatedAsAnOrdinaryWord() throws Exception {
        String url = urlFor("doi:10.1234 fairness");
        assertFalse(url.contains("doi:"), "only arXiv's own prefixes pass through: " + url);
    }

    @Test
    void anUnclosedQuoteStillSearches() throws Exception {
        // these queries are written by an LLM; losing the search to a missing
        // quote is worse than searching one term too broadly
        String url = urlFor("\"chest X-ray fairness");
        assertTrue(url.contains("abs:%22chest%20X-ray%20fairness%22"), url);
    }

    @Test
    void dropsPunctuationOnlyWords() throws Exception {
        String url = urlFor("low resource QA & RAG");
        assertTrue(url.contains("abs:low+AND+abs:resource+AND+abs:QA+AND+abs:RAG"), url);
    }

    @Test
    void aQueryOfOnlyPunctuationIsRejected() {
        ArxivSource source = sourceReturning(FEED);
        assertThrows(IllegalArgumentException.class,
                () -> source.search("??? !!!", SearchIntent.MOST_RELEVANT));
    }

    @Test
    void theIntentChoosesTheSortOrder() throws Exception {
        // UC03 wants what is new; UC07 wants the best match whatever its age
        assertTrue(urlFor("fairness", SearchIntent.NEWEST).contains("sortBy=submittedDate"));
        assertTrue(urlFor("fairness", SearchIntent.MOST_RELEVANT).contains("sortBy=relevance"));
    }

    @Test
    void theOneArgumentSearchAsksForTheBestMatches() throws Exception {
        StringBuilder requested = new StringBuilder();
        new ArxivSource(url -> {
            requested.append(url);
            return FEED;
        }).search("fairness");
        assertTrue(requested.toString().contains("sortBy=relevance"), requested.toString());
    }

    @Test
    void rejectsANullIntent() {
        ArxivSource source = sourceReturning(FEED);
        assertThrows(IllegalArgumentException.class, () -> source.search("fairness", null));
    }

    @Test
    void asksForTheConfiguredNumberOfResults() throws Exception {
        StringBuilder requested = new StringBuilder();
        new ArxivSource(url -> {
            requested.append(url);
            return FEED;
        }, 7).search("fairness");
        assertTrue(requested.toString().contains("max_results=7"), requested.toString());
    }

    @Test
    void reportsItsOwnName() {
        assertEquals("arXiv", sourceReturning(FEED).name());
    }

    // --- looking one paper up by id ------------------------------------------

    @Test
    void aLookupReturnsTheRequestedPaper() throws Exception {
        Optional<PaperRecord> found = sourceReturning(FEED).findById("2301.12345v2");

        assertTrue(found.isPresent());
        assertEquals("2301.12345v2", found.get().getId());
    }

    @Test
    void aVersionSuffixDoesNotMakeItADifferentPaper() throws Exception {
        // arXiv answers 2301.12345 with 2301.12345v2; refusing that would make
        // every unversioned id unusable
        assertTrue(sourceReturning(FEED).findById("2301.12345").isPresent());
    }

    @Test
    void aLookupThatMatchesNothingReturnsEmptyRatherThanAnUnrelatedPaper() throws Exception {
        // arXiv answers an unmatched id: query with other papers, not with
        // none. Taking the first result is how an unrelated paper once got
        // analysed and stored under the id that had been asked for.
        assertTrue(sourceReturning(FEED).findById("9999.99999").isEmpty(),
                "the feed has two papers, neither of them this one");
    }

    @Test
    void aBlankLookupIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> sourceReturning(FEED).findById(" "));
        assertThrows(IllegalArgumentException.class,
                () -> sourceReturning(FEED).findById(null));
    }
}
