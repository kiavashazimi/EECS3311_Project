package ca.yorku.eecs3311.consultant.literature;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A CONCRETE STRATEGY: searches arXiv's public Atom API.
 *
 * <p>Needs no key and no account, which is why it is the source implemented
 * first. The HTTP fetch is injectable so the parsing can be tested offline
 * against canned XML — otherwise every test would depend on what arXiv happens
 * to have indexed today.
 */
public class ArxivSource implements LiteratureSource {

    private static final String ENDPOINT = "https://export.arxiv.org/api/query";
    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** arXiv's own query prefixes, passed through when a term names one. */
    private static final java.util.Set<String> ARXIV_FIELDS =
            java.util.Set.of("all", "ti", "abs", "au", "co", "jr", "cat", "rn", "id");

    /** Authors put repository links in the comment; arXiv has no field for one. */
    private static final Pattern REPO_URL =
            Pattern.compile("https?://(?:www\\.)?github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

    /** Where the Atom XML comes from. Replaced in tests. */
    interface FeedFetcher {
        String fetch(String url) throws IOException;
    }

    private final FeedFetcher fetcher;
    private final int maxResults;

    public ArxivSource() {
        this(ArxivSource::httpGet, DEFAULT_MAX_RESULTS);
    }

    public ArxivSource(int maxResults) {
        this(ArxivSource::httpGet, maxResults);
    }

    ArxivSource(FeedFetcher fetcher) {
        this(fetcher, DEFAULT_MAX_RESULTS);
    }

    ArxivSource(FeedFetcher fetcher, int maxResults) {
        if (fetcher == null) {
            throw new IllegalArgumentException("fetcher must not be null");
        }
        if (maxResults <= 0) {
            throw new IllegalArgumentException("maxResults must be positive, was " + maxResults);
        }
        this.fetcher = fetcher;
        this.maxResults = maxResults;
    }

    @Override
    public String name() {
        return "arXiv";
    }

    /**
     * {@inheritDoc}
     *
     * <p>arXiv's API has no separate lookup, but it does understand
     * {@code id:<identifier>} in a normal query, so that is a real lookup here
     * rather than a guess. The result is still checked against the id that was
     * asked for: a query that matched nothing returns other papers, not none.
     */
    @Override
    public Optional<PaperRecord> findById(String id) throws LiteratureUnavailableException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        for (PaperRecord found : search("id:" + id.strip(), SearchIntent.MOST_RELEVANT)) {
            if (sameArxivId(found.getId(), id.strip())) {
                return Optional.of(found);
            }
        }
        return Optional.empty();
    }

    /** arXiv appends a version, so 2304.09218 and 2304.09218v1 are one paper. */
    private static boolean sameArxivId(String a, String b) {
        return withoutVersion(a).equalsIgnoreCase(withoutVersion(b));
    }

    private static String withoutVersion(String id) {
        return id == null ? "" : id.replaceAll("v\\d+$", "");
    }

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
                + "?search_query=" + searchExpression(query)
                + "&start=0&max_results=" + maxResults
                + "&sortBy=" + intent.arxivSortKey() + "&sortOrder=descending";

        String xml;
        try {
            xml = fetcher.fetch(url);
        } catch (IOException | RuntimeException e) {
            throw new LiteratureUnavailableException(
                    "the arXiv search could not be completed: " + e.getMessage(), e);
        }
        return parse(xml);
    }

    /**
     * Builds arXiv's {@code search_query}: every term ANDed together, matched
     * against the abstract unless the term names its own field.
     *
     * <p>Three things here are load-bearing, each found by querying the live
     * API rather than by reading the docs:
     *
     * <ul>
     *   <li><b>Terms are ANDed.</b> A multi-word phrase passed as one
     *       {@code all:a b c} term makes arXiv keep the first word and
     *       <em>silently ignore the rest</em>; with a newest-first sort that
     *       returns the latest papers on all of arXiv, which looks like a
     *       working search and is not.
     *   <li><b>{@code abs:} not {@code all:}.</b> {@code all:} covers full
     *       text and author names, so {@code all:chest} matches a paper that
     *       merely cites someone in a chest-imaging journal.
     *   <li><b>A quoted phrase stays one term</b>, with its spaces as
     *       {@code %20} and its quotes as {@code %22}. Splitting
     *       {@code "chest X-ray"} into two terms loses the papers that matter,
     *       and sending a raw quote or space is an HTTP 400.
     * </ul>
     *
     * @throws IllegalArgumentException if nothing searchable is left
     */
    private static String searchExpression(String query) {
        List<String> terms = new ArrayList<>();
        for (String token : QueryTerms.split(query)) {
            String term = toTerm(token);
            if (term != null) {
                terms.add(term);
            }
        }
        if (terms.isEmpty()) {
            throw new IllegalArgumentException(
                    "the query has no searchable words: \"" + query + "\"");
        }
        return String.join("+AND+", terms);
    }

    /** @return the encoded arXiv term, or null if there is nothing to search */
    private static String toTerm(String token) {
        if (token.startsWith("\"")) {
            String phrase = token.substring(1, token.length() - 1).strip();
            // keep words and the hyphens in "X-ray"; drop anything else
            phrase = phrase.replaceAll("[^\\p{Alnum}\\-_. ]", "").replaceAll("\\s+", " ").strip();
            if (phrase.isBlank()) {
                return null;
            }
            // inside a quoted phrase arXiv wants %20, not the + that
            // URLEncoder produces for a space
            return "abs:%22" + encode(phrase).replace("+", "%20") + "%22";
        }

        int colon = token.indexOf(':');
        if (colon > 0) {
            String field = token.substring(0, colon).toLowerCase();
            String value = token.substring(colon + 1);
            if (ARXIV_FIELDS.contains(field) && !value.isBlank()) {
                return field + ":" + encode(value);
            }
        }

        String word = token.replaceAll("[^\\p{Alnum}\\-_.]", "");
        return word.isBlank() ? null : "abs:" + encode(word);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // --- parsing -------------------------------------------------------------

    private List<PaperRecord> parse(String xml) throws LiteratureUnavailableException {
        NodeList entries;
        try {
            var doc = secureBuilderFactory()
                    .newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            entries = doc.getElementsByTagName("entry");
        } catch (Exception e) {
            throw new LiteratureUnavailableException(
                    "the arXiv reply could not be read: " + e.getMessage(), e);
        }

        List<PaperRecord> papers = new ArrayList<>();
        for (int i = 0; i < entries.getLength(); i++) {
            if (entries.item(i) instanceof Element entry) {
                // one malformed entry must not cost us the rest of the results
                toRecord(entry).ifPresent(papers::add);
            }
        }
        return papers;
    }

    private static java.util.Optional<PaperRecord> toRecord(Element entry) {
        String rawId = text(entry, "id");
        if (rawId.isBlank()) {
            return java.util.Optional.empty();
        }

        PaperRecord paper = new PaperRecord();
        paper.setId(rawId.substring(rawId.lastIndexOf('/') + 1));
        paper.setTitle(collapse(text(entry, "title")));
        paper.setSummary(collapse(text(entry, "summary")));
        paper.setDate(dateOnly(text(entry, "published")));
        paper.setUrl(alternateLink(entry));
        paper.setVenue(venue(entry));

        // the comment is where authors mention code; the abstract sometimes is too
        Matcher repo = REPO_URL.matcher(
                text(entry, "arxiv:comment") + " " + text(entry, "summary"));
        if (repo.find()) {
            paper.setRepoUrl(repo.group());
        }
        return java.util.Optional.of(paper);
    }

    private static String venue(Element entry) {
        String journal = collapse(text(entry, "arxiv:journal_ref"));
        if (!journal.isBlank()) {
            return journal;
        }
        String category = attribute(entry, "arxiv:primary_category", "term");
        return category.isBlank() ? "arXiv" : "arXiv " + category;
    }

    /** The {@code rel="alternate"} link is the human-readable abstract page. */
    private static String alternateLink(Element entry) {
        NodeList links = entry.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            if (links.item(i) instanceof Element link
                    && "alternate".equals(link.getAttribute("rel"))) {
                return link.getAttribute("href");
            }
        }
        return "";
    }

    private static String text(Element parent, String tag) {
        NodeList found = parent.getElementsByTagName(tag);
        for (int i = 0; i < found.getLength(); i++) {
            Node node = found.item(i);
            // only direct children: an <author><name> must not be read as the entry's name
            if (node.getParentNode() == parent && node.getTextContent() != null) {
                return node.getTextContent();
            }
        }
        return "";
    }

    private static String attribute(Element parent, String tag, String attribute) {
        NodeList found = parent.getElementsByTagName(tag);
        return found.getLength() > 0 && found.item(0) instanceof Element e
                ? e.getAttribute(attribute)
                : "";
    }

    /** arXiv wraps long values across indented lines; the UI wants one line. */
    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    /** {@code 2023-01-29T18:00:00Z} to {@code 2023-01-29}. */
    private static String dateOnly(String timestamp) {
        String t = collapse(timestamp);
        return t.length() >= 10 ? t.substring(0, 10) : t;
    }

    /**
     * A parser that refuses to follow anything the document points at. The
     * reply comes off the public internet, so a DOCTYPE could pull in a local
     * file or hang the parse; both are rejected outright.
     */
    private static DocumentBuilderFactory secureBuilderFactory()
            throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory;
    }

    // --- the real network call ----------------------------------------------

    private static String httpGet(String url) throws IOException {
        // arXiv answers http:// with a 301 to https://, and HttpClient does not
        // follow redirects unless told to; NORMAL never downgrades to plain http
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "research-consultant/0.1 (EECS3311 course project)")
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("arXiv returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("the arXiv request was interrupted", e);
        }
    }
}
