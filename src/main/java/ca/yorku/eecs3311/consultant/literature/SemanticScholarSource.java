package ca.yorku.eecs3311.consultant.literature;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A CONCRETE STRATEGY: searches Semantic Scholar's Graph API.
 *
 * <p><strong>It exists for coverage and metadata, not for better query
 * handling.</strong> An earlier version of this comment claimed that Semantic
 * Scholar ranks a whole natural-language question by relevance and so could not
 * suffer arXiv's collapse on long queries. Measured with a real key on
 * 2026-11-03, that is false — it collapses too:
 *
 * <pre>
 *   1 word   "fairness"                                        593282 results
 *   4 words  "synthetic chest xray fairness"                       45 results
 *   5 words  ... + "demographic"                                    9 results
 *   9 words  "conditioning generative models on demographics ..."    0 results
 * </pre>
 *
 * Both sources therefore need the same short, term-reduced queries, and the
 * relaxation in {@code PaperDiscoveryAgent} and {@code GroundedAnswerAgent}
 * serves both.
 *
 * <p>What this source does add, measured on the same day with the query
 * "synthetic chest xray fairness":
 *
 * <ul>
 *   <li><strong>Coverage.</strong> 4 of 10 results were not on arXiv at all —
 *       AMIA Annual Symposium, PLOS Digital Health and two conference
 *       proceedings. For medical imaging that is a large hole in arXiv.
 *   <li><strong>The published venue.</strong> arXiv's own API reports its
 *       results as "arXiv cs.CV"; this one reported the same paper as
 *       <em>Nature Medicine</em>. That difference matters when judging a paper.
 *   <li><strong>Citation counts.</strong> A ranking signal arXiv does not
 *       expose at all: in that result set the counts ran from 0 to 239.
 * </ul>
 *
 * <p>Two sources with different indexes is what makes the Strategy worth having
 * here. Swapping them is a configuration change and no agent is edited.
 *
 * <p><strong>Rate limiting is this source's normal failure.</strong> The
 * anonymous endpoint returned HTTP 429 to the development machine on every
 * attempt, and said so explicitly: <em>"apply for a key for higher rate
 * limits"</em>. 429 is therefore handled from the start, with retries and a
 * backoff, and a rate-limited search raises
 * {@link LiteratureUnavailableException} rather than returning an empty list —
 * "we could not look" and "there is no such work" must never render the same.
 * {@code ToolManager} can then fall back to arXiv.
 */
public class SemanticScholarSource implements LiteratureSource {

    private static final String ENDPOINT =
            "https://api.semanticscholar.org/graph/v1/paper/search";

    /** Single-paper lookup; the id is appended. */
    private static final String ENDPOINT_PAPER =
            "https://api.semanticscholar.org/graph/v1/paper/";

    /**
     * Only what {@link #parse} actually reads.
     *
     * <p>{@code citationCount} is deliberately absent. This source exposes it
     * and it would be a real ranking signal, but nothing here uses it yet, and
     * requesting a field that is then discarded costs response size against a
     * budget of one request per second.
     */
    private static final String FIELDS =
            "title,abstract,year,venue,externalIds,openAccessPdf";

    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final int DEFAULT_RETRIES = 3;
    private static final long DEFAULT_BACKOFF_MILLIS = 2_000L;

    /**
     * The smallest gap between two requests.
     *
     * <p>A key is rated at one request per second. Measured with a real key,
     * probing at 1.5-second intervals still drew HTTP 429, so this leaves
     * headroom. Discovery issues several queries in a row, which is exactly the
     * pattern that trips the limit, and waiting for a rejection to find that
     * out costs a retry cycle each time.
     */
    private static final long DEFAULT_MIN_INTERVAL_MILLIS = 1_100L;

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One HTTP exchange. Replaced in tests, and the seam a cache would sit in. */
    interface JsonFetcher {

        /**
         * @param url the fully built request URL
         * @return the status and body; a non-200 status is returned, not thrown,
         *     because this source treats 429, 401 and 404 differently
         * @throws IOException if the request could not be made at all
         */
        Response get(String url) throws IOException;
    }

    /** An HTTP status and body, so the caller can decide what each status means. */
    record Response(int status, String body) {
    }

    /**
     * The clock and the waiting, so a test can observe pacing and backoff
     * without the suite actually sleeping.
     */
    interface Delayer {
        long nowMillis();

        void sleep(long millis) throws InterruptedException;
    }

    private static final class RealDelayer implements Delayer {
        @Override
        public long nowMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            if (millis > 0) {
                Thread.sleep(millis);
            }
        }
    }

    private final JsonFetcher fetcher;
    private final int maxResults;
    private final int retries;
    private final long backoffMillis;
    private final long minIntervalMillis;
    private final Delayer delayer;
    private final String apiKey;

    /** When the last request went out, for pacing. Guarded by {@link #pace()}. */
    private long lastRequestAt;

    /**
     * Whether any request has gone out yet.
     *
     * <p>An explicit flag rather than comparing {@link #lastRequestAt} against
     * zero. With a real epoch clock the zero happens to be far enough in the
     * past that the first request is not delayed, but that is an accident of
     * the clock's origin, and under a clock starting at zero the very first
     * request waited a full interval for no reason.
     */
    private boolean hasRequested;

    /** Anonymous. Expect HTTP 429; see the class javadoc. */
    public SemanticScholarSource() {
        this("");
    }

    /**
     * @param apiKey the S2 API key, or blank to go anonymous. Never logged, and
     *     never included in a message
     */
    public SemanticScholarSource(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.fetcher = this::httpGet;
        this.maxResults = DEFAULT_MAX_RESULTS;
        this.retries = DEFAULT_RETRIES;
        this.backoffMillis = DEFAULT_BACKOFF_MILLIS;
        this.minIntervalMillis = DEFAULT_MIN_INTERVAL_MILLIS;
        this.delayer = new RealDelayer();
    }

    SemanticScholarSource(JsonFetcher fetcher, int maxResults, int retries, long backoffMillis) {
        this(fetcher, maxResults, retries, backoffMillis, 0L, new RealDelayer());
    }

    SemanticScholarSource(JsonFetcher fetcher, int maxResults, int retries, long backoffMillis,
            long minIntervalMillis, Delayer delayer) {
        if (fetcher == null) {
            throw new IllegalArgumentException("fetcher must not be null");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be positive, was " + maxResults);
        }
        if (retries < 0) {
            throw new IllegalArgumentException("retries must not be negative, was " + retries);
        }
        if (delayer == null) {
            throw new IllegalArgumentException("delayer must not be null");
        }
        this.fetcher = fetcher;
        this.maxResults = maxResults;
        this.retries = retries;
        this.backoffMillis = backoffMillis;
        this.minIntervalMillis = minIntervalMillis;
        this.delayer = delayer;
        this.apiKey = "";
    }

    @Override
    public String name() {
        return "Semantic Scholar";
    }

    /**
     * {@inheritDoc}
     *
     * <p>A real lookup: {@code /graph/v1/paper/{id}} returns one paper, or 404.
     * Putting the id into the free-text {@code query} parameter instead — which
     * is what the application used to do for every source — made this endpoint
     * read it as keywords and return an unrelated paper, which was then
     * analysed and stored under the requested id.
     *
     * <p>The service accepts its own hash ids and prefixed external ones such
     * as {@code arXiv:2304.09218}, so an arXiv id is passed through in the form
     * it understands.
     */
    @Override
    public Optional<PaperRecord> findById(String id) throws LiteratureUnavailableException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        String wanted = id.strip();
        String lookup = wanted.matches("\\d{4}\\.\\d{4,5}(v\\d+)?")
                ? "arXiv:" + wanted.replaceAll("v\\d+$", "")
                : wanted;

        String body = request(ENDPOINT_PAPER + URLEncoder.encode(lookup, StandardCharsets.UTF_8)
                + "?fields=" + FIELDS, true);
        if (body == null) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new LiteratureUnavailableException(
                    "Semantic Scholar's reply could not be read: " + e.getMessage(), e);
        }
        return Optional.ofNullable(toRecord(node));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The query is sent as given. Callers already reduce a question to a few
     * terms and relax it when nothing comes back, and they must: measured with
     * a real key, a nine-word question returned nothing here, exactly as it
     * does on arXiv. This source does not rescue a long query.
     *
     * <p><strong>{@link SearchIntent#NEWEST} reorders the page it was given.</strong>
     * This endpoint ranks by relevance only, so newest-first is applied to the
     * returned results rather than asked of the server: it is the newest of the
     * most relevant, not the newest overall. {@link ArxivSource} can sort
     * server-side and is the better source when recency is what matters, which
     * is a reason to keep both rather than replace one.
     */
    @Override
    public List<PaperRecord> search(String query, SearchIntent intent)
            throws LiteratureUnavailableException {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }
        if (intent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }

        String url = ENDPOINT
                + "?query=" + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8)
                + "&limit=" + maxResults
                + "&fields=" + FIELDS;

        List<PaperRecord> papers = parse(request(url));
        if (intent == SearchIntent.NEWEST) {
            papers.sort(Comparator.comparing(PaperRecord::getDate).reversed());
        }
        return papers;
    }

    /**
     * Makes the request, retrying only what retrying can fix.
     *
     * <p>429 is retried with a backoff. 401 is not: a rejected key will not
     * start working, and retrying it only delays the fallback to another source.
     */
    private String request(String url) throws LiteratureUnavailableException {
        return request(url, false);
    }

    /**
     * @param notFoundIsEmpty true for a single-paper lookup, where HTTP 404
     *     means "this source does not have it" rather than a failure
     * @return the body, or null when the paper was simply not found
     */
    private String request(String url, boolean notFoundIsEmpty)
            throws LiteratureUnavailableException {
        int attempts = retries + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Response response;
            try {
                pace();
                response = fetcher.get(url);
            } catch (IOException e) {
                throw new LiteratureUnavailableException(
                        "Semantic Scholar could not be reached: " + e.getMessage(), e);
            }

            switch (response.status()) {
                case 200 -> {
                    return response.body();
                }
                case 429 -> {
                    if (attempt == attempts) {
                        throw new LiteratureUnavailableException(
                                "Semantic Scholar is rate limiting this client (HTTP 429)"
                                + " after " + attempts + " attempts."
                                + (apiKey.isBlank()
                                        ? " Set S2_API_KEY - the anonymous endpoint is"
                                                + " heavily limited."
                                        : ""));
                    }
                    pause(backoffFor(attempt));
                }
                case 404 -> {
                    if (notFoundIsEmpty) {
                        return null;
                    }
                    throw new LiteratureUnavailableException(
                            "Semantic Scholar returned HTTP 404.");
                }
                case 401, 403 -> throw new LiteratureUnavailableException(
                        "Semantic Scholar rejected the request (HTTP " + response.status()
                        + "). Check S2_API_KEY.");
                default -> throw new LiteratureUnavailableException(
                        "Semantic Scholar returned HTTP " + response.status() + ".");
            }
        }
        // unreachable: the loop either returns or throws
        throw new LiteratureUnavailableException("Semantic Scholar could not be read.");
    }

    /**
     * The wait before retry number {@code attempt}, doubling each time.
     *
     * <p>Exponential, not linear: the API licence asks for exponential backoff,
     * and an earlier version multiplied by the attempt number instead, giving
     * 2s, 4s, 6s rather than 2s, 4s, 8s.
     *
     * @param attempt the attempt that just failed, counting from 1
     */
    long backoffFor(int attempt) {
        return backoffMillis * (1L << (attempt - 1));
    }

    /**
     * Waits until this source is allowed to make another request.
     *
     * <p>A key is rated at one request per second, and discovery issues several
     * queries in a row. Reacting only to a rejection would spend a whole retry
     * cycle discovering something the limit already told us.
     *
     * <p>Synchronized because the GUI searches off the FX thread while the same
     * source instance may be in use elsewhere.
     */
    private synchronized void pace() {
        if (minIntervalMillis > 0 && hasRequested) {
            long waited = delayer.nowMillis() - lastRequestAt;
            if (waited < minIntervalMillis) {
                pause(minIntervalMillis - waited);
            }
        }
        lastRequestAt = delayer.nowMillis();
        hasRequested = true;
    }

    private void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            delayer.sleep(millis);
        } catch (InterruptedException e) {
            // keep the interrupt visible rather than swallowing it
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reads the result page.
     *
     * <p>A paper with no title is skipped: it cannot be shown, compared or
     * cited, and a blank row in a result list reads as a tool malfunction. One
     * unusable entry does not discard the rest of the page.
     */
    private List<PaperRecord> parse(String body) throws LiteratureUnavailableException {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (IOException e) {
            throw new LiteratureUnavailableException(
                    "Semantic Scholar's reply could not be read: " + e.getMessage(), e);
        }

        List<PaperRecord> papers = new ArrayList<>();
        for (JsonNode node : root.path("data")) {
            PaperRecord paper = toRecord(node);
            if (paper != null) {
                papers.add(paper);
            }
        }
        return papers;
    }

    /**
     * One JSON object to one {@link PaperRecord}, or null when it has no title.
     *
     * <p>A paper with no title cannot be shown, compared or cited, and a blank
     * row in a result list reads as a tool malfunction.
     */
    private PaperRecord toRecord(JsonNode node) {
        String title = node.path("title").asText("").strip();
        if (title.isEmpty()) {
            return null;
        }
        String paperId = node.path("paperId").asText("").strip();
        String arxivId = node.path("externalIds").path("ArXiv").asText("").strip();

        // the arXiv id when there is one: analyze, compare and repo all look a
        // paper up by it, so preferring it keeps those features working on
        // results that came from here
        PaperRecord paper = new PaperRecord(
                arxivId.isEmpty() ? paperId : arxivId,
                title,
                node.path("venue").asText("").strip().isEmpty()
                        ? "Semantic Scholar" : node.path("venue").asText().strip(),
                node.path("year").isMissingNode() ? "" : node.path("year").asText(""));
        paper.setSummary(node.path("abstract").asText("").strip());

        String pdf = node.path("openAccessPdf").path("url").asText("").strip();
        paper.setUrl(pdf.isEmpty()
                ? "https://www.semanticscholar.org/paper/" + paperId
                : pdf);
        return paper;
    }

    /** The real transport. The key goes in a header, never in the URL or a log. */
    private Response httpGet(String url) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "research-consultant/0.1 (EECS3311 course project)")
                .GET();
        if (!apiKey.isBlank()) {
            request.header("x-api-key", apiKey);
        }
        // not try-with-resources: HttpClient is only AutoCloseable from Java 21
        // and this project targets release 17
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            HttpResponse<String> response =
                    client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling Semantic Scholar", e);
        }
    }
}
