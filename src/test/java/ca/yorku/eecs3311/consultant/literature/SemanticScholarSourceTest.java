package ca.yorku.eecs3311.consultant.literature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The second concrete Strategy.
 *
 * <p>It exists because arXiv is keyword retrieval: terms are ANDed, so a
 * compound research question narrows to nothing, and arXiv carries no MICCAI,
 * IPMI, MIDL or journal papers at all. Semantic Scholar ranks a whole
 * natural-language query by relevance, so it cannot produce that failure mode.
 *
 * <p>Every test drives a fake fetcher. The live endpoint returned HTTP 429 to
 * this machine without an API key, which is also why the 429 path is tested as
 * carefully as the success path.
 */
class SemanticScholarSourceTest {

    /** Records what was asked for and replies with a canned status and body. */
    private static final class FakeFetcher implements SemanticScholarSource.JsonFetcher {
        private final List<String> urls = new ArrayList<>();
        private final List<SemanticScholarSource.Response> replies = new ArrayList<>();
        private int call;
        private IOException transportFailure;

        FakeFetcher replying(int status, String body) {
            replies.add(new SemanticScholarSource.Response(status, body));
            return this;
        }

        FakeFetcher failingWith(IOException e) {
            this.transportFailure = e;
            return this;
        }

        @Override
        public SemanticScholarSource.Response get(String url) throws IOException {
            urls.add(url);
            if (transportFailure != null) {
                throw transportFailure;
            }
            return replies.get(Math.min(call++, replies.size() - 1));
        }
    }

    private static final String TWO_PAPERS = """
            {"total":2,"data":[
              {"paperId":"abc123","title":"Fair Synthetic Chest Radiographs",
               "abstract":"We condition a diffusion model on demographics.",
               "year":2024,"venue":"MICCAI","citationCount":41,
               "externalIds":{"ArXiv":"2412.20374","DOI":"10.1/xyz"},
               "openAccessPdf":{"url":"https://example.org/paper.pdf"}},
              {"paperId":"def456","title":"Subgroup Fairness Without Synthetic Data",
               "abstract":"A reweighting baseline.","year":2021,"venue":"MIDL",
               "citationCount":7,"externalIds":{}}
            ]}
            """;

    private static SemanticScholarSource source(FakeFetcher fetcher) {
        // no retries worth waiting for, and no backoff, so tests never sleep
        return new SemanticScholarSource(fetcher, 10, 2, 0L);
    }

    // --- the Strategy contract -----------------------------------------------

    @Test
    void itIsALiteratureSourceSoToolManagerNeedNotKnowWhichOneItIs() {
        assertTrue(source(new FakeFetcher()) instanceof LiteratureSource);
    }

    @Test
    void itNamesItselfForTheLogAndTheUi() {
        assertEquals("Semantic Scholar", source(new FakeFetcher()).name());
    }

    @Test
    void aBlankQueryIsRejectedBeforeAnyRequestIsMade() {
        FakeFetcher fetcher = new FakeFetcher();
        assertThrows(IllegalArgumentException.class,
                () -> source(fetcher).search("  ", SearchIntent.MOST_RELEVANT));
        assertThrows(IllegalArgumentException.class,
                () -> source(fetcher).search(null, SearchIntent.MOST_RELEVANT));
        assertTrue(fetcher.urls.isEmpty(), "nothing should have been requested");
    }

    // --- the query it sends ---------------------------------------------------

    @Test
    void thWholeQuestionIsSentAsOnePhraseRatherThanAndedTerms() throws Exception {
        FakeFetcher fetcher = new FakeFetcher().replying(200, TWO_PAPERS);

        source(fetcher).search("does conditioning a generator on demographics close"
                + " the fairness gap", SearchIntent.MOST_RELEVANT);

        String sent = URLDecoder.decode(fetcher.urls.get(0), StandardCharsets.UTF_8);
        assertTrue(sent.contains("does conditioning a generator on demographics close"
                        + " the fairness gap"),
                "the point of this source is that it ranks a whole question: " + sent);
    }

    @Test
    void theQueryIsUrlEncodedSoSpacesAndQuotesCannotBreakIt() throws Exception {
        FakeFetcher fetcher = new FakeFetcher().replying(200, TWO_PAPERS);

        source(fetcher).search("chest \"X-ray\" & fairness", SearchIntent.MOST_RELEVANT);

        String raw = fetcher.urls.get(0);
        assertFalse(raw.contains(" "), "a raw space makes an invalid URL: " + raw);
        assertFalse(raw.contains("\""), "a raw quote makes an invalid URL: " + raw);
    }

    @Test
    void itAsksOnlyForTheFieldsItUses() throws Exception {
        FakeFetcher fetcher = new FakeFetcher().replying(200, TWO_PAPERS);

        source(fetcher).search("fairness", SearchIntent.MOST_RELEVANT);

        String url = fetcher.urls.get(0);
        assertTrue(url.contains("fields="), url);
        assertTrue(url.contains("externalIds"),
                "the arXiv id is what lets 'rc analyze' work on these results: " + url);
    }

    // --- what it returns ------------------------------------------------------

    @Test
    void papersAreReadIntoTheSameRecordTheOtherSourceProduces() throws Exception {
        List<PaperRecord> found = source(new FakeFetcher().replying(200, TWO_PAPERS))
                .search("fairness", SearchIntent.MOST_RELEVANT);

        assertEquals(2, found.size());
        PaperRecord first = found.get(0);
        assertEquals("Fair Synthetic Chest Radiographs", first.getTitle());
        assertEquals("MICCAI", first.getVenue(), "a venue arXiv does not carry at all");
        assertTrue(first.getSummary().contains("diffusion model"));
    }

    @Test
    void anArxivIdIsPreferredSoTheRestOfTheApplicationStillWorks() throws Exception {
        // analyze, compare and repo all look a paper up by arXiv id
        List<PaperRecord> found = source(new FakeFetcher().replying(200, TWO_PAPERS))
                .search("fairness", SearchIntent.MOST_RELEVANT);

        assertEquals("2412.20374", found.get(0).getId());
    }

    @Test
    void aPaperWithNoArxivIdFallsBackToItsOwnIdentifier() throws Exception {
        List<PaperRecord> found = source(new FakeFetcher().replying(200, TWO_PAPERS))
                .search("fairness", SearchIntent.MOST_RELEVANT);

        assertEquals("def456", found.get(1).getId(),
                "it must still be identifiable, just not by an arXiv id it does not have");
    }

    @Test
    void aPaperWithNoOpenPdfStillGetsAUrlAReaderCanFollow() throws Exception {
        List<PaperRecord> found = source(new FakeFetcher().replying(200, TWO_PAPERS))
                .search("fairness", SearchIntent.MOST_RELEVANT);

        assertTrue(found.get(1).getUrl().contains("def456"), found.get(1).getUrl());
    }

    @Test
    void noMatchesIsAnEmptyListNotAnError() throws Exception {
        List<PaperRecord> found = source(new FakeFetcher().replying(200, "{\"total\":0,\"data\":[]}"))
                .search("nothing matches this", SearchIntent.MOST_RELEVANT);

        assertTrue(found.isEmpty());
    }

    @Test
    void aPaperMissingEveryOptionalFieldDoesNotSinkTheWholePage() throws Exception {
        String sparse = "{\"data\":[{\"paperId\":\"x1\",\"title\":\"Bare Minimum\"}]}";

        List<PaperRecord> found = source(new FakeFetcher().replying(200, sparse))
                .search("fairness", SearchIntent.MOST_RELEVANT);

        assertEquals(1, found.size());
        assertEquals("Bare Minimum", found.get(0).getTitle());
    }

    @Test
    void aPaperWithNoTitleIsSkippedRatherThanListedBlank() throws Exception {
        String oneBad = "{\"data\":[{\"paperId\":\"x1\"},"
                + "{\"paperId\":\"x2\",\"title\":\"Real One\"}]}";

        List<PaperRecord> found = source(new FakeFetcher().replying(200, oneBad))
                .search("fairness", SearchIntent.MOST_RELEVANT);

        assertEquals(1, found.size());
        assertEquals("Real One", found.get(0).getTitle());
    }

    // --- ordering -------------------------------------------------------------

    @Test
    void newestFirstReordersThePageBecauseThisSourceOnlyRanksByRelevance() throws Exception {
        List<PaperRecord> found = source(new FakeFetcher().replying(200, TWO_PAPERS))
                .search("fairness", SearchIntent.NEWEST);

        assertEquals("2024", found.get(0).getDate());
        assertEquals("2021", found.get(1).getDate());
    }

    @Test
    void relevanceOrderIsLeftExactlyAsTheSourceRankedIt() throws Exception {
        List<PaperRecord> found = source(new FakeFetcher().replying(200, TWO_PAPERS))
                .search("fairness", SearchIntent.MOST_RELEVANT);

        assertEquals("2412.20374", found.get(0).getId(),
                "re-sorting relevance results would throw away the ranking");
    }

    // --- rate limiting, the failure this source actually has ------------------

    @Test
    void aRateLimitedRequestIsRetriedBeforeGivingUp() {
        FakeFetcher fetcher = new FakeFetcher().replying(429, "{\"code\":\"429\"}");

        assertThrows(LiteratureUnavailableException.class,
                () -> source(fetcher).search("fairness", SearchIntent.MOST_RELEVANT));
        assertEquals(3, fetcher.urls.size(), "one attempt plus two retries");
    }

    @Test
    void aRetryThatSucceedsReturnsThePapers() throws Exception {
        FakeFetcher fetcher = new FakeFetcher()
                .replying(429, "{\"code\":\"429\"}")
                .replying(200, TWO_PAPERS);

        List<PaperRecord> found = source(fetcher)
                .search("fairness", SearchIntent.MOST_RELEVANT);

        assertEquals(2, found.size());
    }

    @Test
    void beingRateLimitedSaysSoRatherThanLookingLikeNoResults() {
        FakeFetcher fetcher = new FakeFetcher().replying(429, "{\"code\":\"429\"}");

        LiteratureUnavailableException thrown = assertThrows(
                LiteratureUnavailableException.class,
                () -> source(fetcher).search("fairness", SearchIntent.MOST_RELEVANT));

        assertTrue(thrown.getMessage().contains("429")
                        || thrown.getMessage().toLowerCase().contains("rate"),
                "an empty list here would read as 'no such work exists': "
                        + thrown.getMessage());
    }

    @Test
    void anUnauthorizedKeyIsNotRetried() {
        // retrying a rejected key just burns time; it will not start working
        FakeFetcher fetcher = new FakeFetcher().replying(401, "{\"code\":\"401\"}");

        assertThrows(LiteratureUnavailableException.class,
                () -> source(fetcher).search("fairness", SearchIntent.MOST_RELEVANT));
        assertEquals(1, fetcher.urls.size(), "one attempt only: " + fetcher.urls);
    }

    @Test
    void aTransportFailureIsReportedAsUnavailableNotAsACrash() {
        FakeFetcher fetcher = new FakeFetcher().failingWith(new IOException("connect timed out"));

        LiteratureUnavailableException thrown = assertThrows(
                LiteratureUnavailableException.class,
                () -> source(fetcher).search("fairness", SearchIntent.MOST_RELEVANT));
        assertTrue(thrown.getMessage().contains("connect timed out"), thrown.getMessage());
    }

    @Test
    void unreadableJsonIsReportedAsUnavailableRatherThanAsEmpty() {
        FakeFetcher fetcher = new FakeFetcher().replying(200, "{ this is not json");

        assertThrows(LiteratureUnavailableException.class,
                () -> source(fetcher).search("fairness", SearchIntent.MOST_RELEVANT));
    }

    @Test
    void theConstructorRejectsNonsenseRatherThanFailingLater() {
        FakeFetcher f = new FakeFetcher();
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticScholarSource(null, 10, 2, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticScholarSource(f, 0, 2, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticScholarSource(f, 10, -1, 0L));
    }

    // --- backoff and pacing ---------------------------------------------------

    /** A clock that never really sleeps, but records every wait it was asked for. */
    private static final class FakeDelayer implements SemanticScholarSource.Delayer {
        private final List<Long> slept = new ArrayList<>();
        private long now;

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            slept.add(millis);
            now += millis;
        }
    }

    private static SemanticScholarSource paced(FakeFetcher fetcher, FakeDelayer delayer) {
        return new SemanticScholarSource(fetcher, 10, 3, 100L, 1_000L, delayer);
    }

    @Test
    void theBackoffDoublesRatherThanStepping() {
        FakeDelayer delayer = new FakeDelayer();
        SemanticScholarSource source = paced(new FakeFetcher(), delayer);

        // the licence asks for exponential; an earlier version multiplied by
        // the attempt number, giving 100, 200, 300
        assertEquals(100L, source.backoffFor(1));
        assertEquals(200L, source.backoffFor(2));
        assertEquals(400L, source.backoffFor(3));
    }

    @Test
    void retriesWaitTheDoublingAmounts() {
        FakeDelayer delayer = new FakeDelayer();
        FakeFetcher fetcher = new FakeFetcher().replying(429, "{\"code\":\"429\"}");

        assertThrows(LiteratureUnavailableException.class,
                () -> paced(fetcher, delayer).search("fairness", SearchIntent.MOST_RELEVANT));

        // the pacing gap is 0 before the first request, so what remains are the
        // three backoffs between four attempts
        assertTrue(delayer.slept.containsAll(List.of(100L, 200L, 400L)),
                "expected doubling waits, got " + delayer.slept);
    }

    @Test
    void consecutiveSearchesAreSpacedToStayUnderOnePerSecond() throws Exception {
        // a key is rated at 1 request/second and discovery fires several in a
        // row; measured live, even 1.5s spacing still drew 429s
        FakeDelayer delayer = new FakeDelayer();
        FakeFetcher fetcher = new FakeFetcher().replying(200, TWO_PAPERS);
        SemanticScholarSource source = paced(fetcher, delayer);

        source.search("one", SearchIntent.MOST_RELEVANT);
        source.search("two", SearchIntent.MOST_RELEVANT);
        source.search("three", SearchIntent.MOST_RELEVANT);

        assertEquals(List.of(1_000L, 1_000L), delayer.slept,
                "the first request goes straight out; each later one waits");
    }

    @Test
    void aSearchAfterALongPauseIsNotDelayedAgain() throws Exception {
        FakeDelayer delayer = new FakeDelayer();
        SemanticScholarSource source = paced(new FakeFetcher().replying(200, TWO_PAPERS), delayer);

        source.search("one", SearchIntent.MOST_RELEVANT);
        delayer.now += 60_000;
        source.search("two", SearchIntent.MOST_RELEVANT);

        assertTrue(delayer.slept.isEmpty(),
                "the budget has long since refilled: " + delayer.slept);
    }

    @Test
    void theFieldListDoesNotAskForAnythingUnused() throws Exception {
        FakeFetcher fetcher = new FakeFetcher().replying(200, TWO_PAPERS);

        source(fetcher).search("fairness", SearchIntent.MOST_RELEVANT);

        assertFalse(fetcher.urls.get(0).contains("citationCount"),
                "a field that is requested and discarded costs response size"
                        + " against a one-per-second budget");
    }

    @Test
    void aNullDelayerIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SemanticScholarSource(new FakeFetcher(), 10, 1, 0L, 0L, null));
    }
}
