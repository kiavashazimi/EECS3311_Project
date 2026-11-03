package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import ca.yorku.eecs3311.consultant.literature.LiteratureUnavailableException;
import ca.yorku.eecs3311.consultant.literature.QueryTerms;
import ca.yorku.eecs3311.consultant.literature.SearchIntent;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F03 — finds papers that matter for this specific project.
 *
 * <p>Also the SUBJECT of the Observer pattern: it broadcasts each discovered
 * paper and knows only {@link PaperFeedListener}.
 *
 * <p>The shape of the work was settled by measurement, not by guesswork. One
 * query is not enough for a real research question: six differently-phrased
 * queries on the same topic returned 26 unique papers of which 24 were found by
 * exactly <em>one</em> query, because the subtopics of a compound question are
 * nearly disjoint. So {@link #plan} writes several queries and {@link
 * #executeTools} unions them, which is what UC03 step 3 means by "plans search
 * queries", plural.
 */
public class PaperDiscoveryAgent extends Agent {

    /** Beyond this the prompt grows faster than the ranking improves. */
    static final int MAX_QUERIES = 6;

    /** Caps the ranking prompt. Papers past this are returned unranked. */
    static final int MAX_RANKED = 25;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<PaperFeedListener> listeners = new ArrayList<>();
    private ProjectProfile profile;

    public PaperDiscoveryAgent(ToolManager tools) {
        super(tools);
    }

    // --- Observer ------------------------------------------------------------

    /** Registers a listener. Adding the same one twice would notify it twice. */
    public void addListener(PaperFeedListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(PaperFeedListener listener) {
        listeners.remove(listener);
    }

    /**
     * Tells every listener about one paper.
     *
     * <p>A listener that throws is skipped rather than allowed to abort the
     * broadcast: the GUI feed failing to redraw must not stop the digest
     * builder from recording the paper.
     */
    public void notifyListeners(PaperRecord paper) {
        if (paper == null) {
            throw new IllegalArgumentException("paper must not be null");
        }
        for (PaperFeedListener listener : List.copyOf(listeners)) {
            try {
                listener.onNewPaper(paper);
            } catch (RuntimeException e) {
                // one broken observer must not silence the others
            }
        }
    }

    // --- Template Method hooks ----------------------------------------------

    @Override
    protected Plan plan(Object input) throws AgentException {
        if (!(input instanceof ProjectProfile p)) {
            throw new AgentException("discovery needs a ProjectProfile, got "
                    + (input == null ? "null" : input.getClass().getSimpleName()));
        }
        if (p.getResearchQuestion().isBlank()) {
            throw new AgentException(
                    "the project has no research question yet - set up the profile first");
        }
        this.profile = p;

        Plan plan = new Plan().addStep("write search queries from the project profile");
        List<String> queries = writeQueries(p);
        plan.addStep("search " + queries.size() + " queries and union the results");
        plan.addStep("rank the union against the project profile");
        for (String query : queries) {
            plan.addToolCall(query);
        }
        return plan;
    }

    @Override
    protected Object executeTools(Plan plan) throws AgentException {
        // dedupe across queries; PaperRecord equality is id plus title
        Map<PaperRecord, PaperRecord> union = new LinkedHashMap<>();
        int failures = 0;
        LiteratureUnavailableException last = null;

        for (String query : plan.getToolCalls()) {
            try {
                for (PaperRecord paper : searchRelaxing(query)) {
                    union.putIfAbsent(paper, paper);
                }
            } catch (LiteratureUnavailableException e) {
                // one dud query must not lose the results of the others
                failures++;
                last = e;
            }
        }

        if (failures == plan.getToolCalls().size()) {
            // UC03 4a: every search failed, so this is an error the researcher
            // must see, with whatever they already had left on screen
            throw new AgentException(
                    "the literature search failed: " + (last == null ? "" : last.getMessage()),
                    last);
        }
        return new ArrayList<>(union.values());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Object summarize(Object result) {
        List<PaperRecord> found = (List<PaperRecord>) result;
        if (found.isEmpty()) {
            // UC03 5a: "no new papers" is a state, not a failure
            return List.of();
        }

        List<RankedPaper> ranked = rank(found);
        for (RankedPaper paper : ranked) {
            notifyListeners(paper.paper());
        }
        return ranked;
    }

    /**
     * Searches, dropping a term at a time while nothing comes back.
     *
     * <p>Terms are ANDed, so every one must appear in the abstract and a query
     * collapses to nothing quickly: measured on arXiv, one real query returned
     * 5 papers at three terms, 1 at four and 0 at five. Without this, a
     * perfectly sensible four-term query makes the whole feature report "no
     * new papers", which is what it did on the first live run.
     */
    private List<PaperRecord> searchRelaxing(String query) throws LiteratureUnavailableException {
        String current = query;
        while (!current.isBlank()) {
            List<PaperRecord> found = tools.search(current, SearchIntent.NEWEST);
            if (!found.isEmpty()) {
                return found;
            }
            current = QueryTerms.dropLastTerm(current);
        }
        return List.of();
    }

    // --- query writing -------------------------------------------------------

    /**
     * Asks the model for several queries, falling back to a deterministic set.
     *
     * <p>Synonym expansion is the part only a model can do: nothing
     * deterministic knows that <em>chest X-ray</em>, <em>chest radiograph</em>
     * and <em>CXR</em> are the same thing, or that a <em>fairness gap</em> is
     * also a <em>subgroup disparity</em>.
     */
    private List<String> writeQueries(ProjectProfile p) {
        String prompt = """
                A researcher is looking for papers related to their own project.

                %s

                Write up to %d arXiv search queries that together cover this topic.
                Rules:
                - one query per line, nothing else, no numbering, no explanation
                - EVERY term must appear in a paper's abstract for it to match,
                  so use 2 or 3 terms per query and never more
                - quote a multi-word phrase only when it is standard vocabulary
                  many authors would write verbatim, such as "chest X-ray".
                  A phrase only this researcher would use matches nothing
                - vary the vocabulary across queries: use synonyms a different
                  author would have chosen, not the researcher's exact words
                - you may start one query with cat:<arxiv category> to narrow it
                """.formatted(describe(p), MAX_QUERIES);

        try {
            return parseQueries(tools.callLLM(prompt), p);
        } catch (LLMUnavailableException e) {
            return fallbackQueries(p);
        }
    }

    static List<String> parseQueries(String reply, ProjectProfile p) {
        List<String> queries = new ArrayList<>();
        for (String line : reply.split("\\R")) {
            String query = line.strip().replaceFirst("^[-*\\d.)\\s]+", "").strip();
            if (!query.isBlank() && !queries.contains(query)) {
                queries.add(query);
            }
            if (queries.size() == MAX_QUERIES) {
                break;
            }
        }
        return queries.isEmpty() ? fallbackQueries(p) : queries;
    }

    /**
     * Queries built without the model, so discovery still works with no key.
     * Narrower than the model's, because it cannot find a synonym.
     */
    static List<String> fallbackQueries(ProjectProfile p) {
        List<String> queries = new ArrayList<>();
        List<String> keywords = keywords(p.getResearchQuestion());
        if (!keywords.isEmpty()) {
            queries.add(String.join(" ", keywords));
        }
        for (String dataset : p.getDatasets()) {
            queries.add('"' + dataset + '"' + (keywords.isEmpty() ? "" : " " + keywords.get(0)));
        }
        for (String metric : p.getMetrics()) {
            queries.add('"' + metric + '"' + (keywords.isEmpty() ? "" : " " + keywords.get(0)));
        }
        return queries.size() > MAX_QUERIES ? queries.subList(0, MAX_QUERIES) : queries;
    }

    /** The longest words of the question, as a crude stand-in for its topic. */
    private static List<String> keywords(String question) {
        List<String> words = new ArrayList<>();
        for (String word : question.toLowerCase().split("[^\\p{Alnum}-]+")) {
            if (word.length() > 5 && !words.contains(word)) {
                words.add(word);
            }
        }
        words.sort((a, b) -> b.length() - a.length());
        return words.size() > 3 ? words.subList(0, 3) : words;
    }

    private static String describe(ProjectProfile p) {
        return """
                Research question: %s
                Datasets: %s
                Models: %s
                Metrics: %s""".formatted(
                p.getResearchQuestion(),
                String.join(", ", p.getDatasets()),
                String.join(", ", p.getModels()),
                String.join(", ", p.getMetrics()));
    }

    // --- ranking -------------------------------------------------------------

    /**
     * Ranks the union in one call, scoring each paper against the profile.
     *
     * <p>One listwise call rather than one call per paper: it is far cheaper and
     * the ordering is better calibrated, because the model sees the
     * alternatives it is ranking against.
     */
    private List<RankedPaper> rank(List<PaperRecord> found) {
        List<PaperRecord> candidates =
                found.size() > MAX_RANKED ? found.subList(0, MAX_RANKED) : found;

        String reply;
        try {
            reply = tools.callLLM(rankingPrompt(candidates));
        } catch (LLMUnavailableException e) {
            // UC03 6a: show them unranked and labelled, never fabricated
            return unranked(found);
        }

        List<RankedPaper> ranked = parseRanking(reply, candidates);
        if (ranked.isEmpty()) {
            return unranked(found);
        }

        // anything the model dropped is appended rather than lost
        for (PaperRecord paper : found) {
            if (ranked.stream().noneMatch(r -> r.paper().equals(paper))) {
                ranked.add(RankedPaper.unranked(paper));
            }
        }
        return ranked;
    }

    private String rankingPrompt(List<PaperRecord> candidates) {
        StringBuilder listing = new StringBuilder();
        for (PaperRecord p : candidates) {
            listing.append("[").append(p.getId()).append("] ").append(p.getTitle())
                    .append("\n").append(p.getSummary()).append("\n\n");
        }
        return """
                Rank papers for one specific researcher's project.

                %s

                Candidates:

                %s
                Return JSON only, no prose, ranking every candidate from most to
                least useful to THIS project:
                {"ranked":[{"id":"...","score":0-100,"why":"one line naming what
                this project can take from it or how it differs",
                "closest_prior_work":true}]}

                Score usefulness to this project, not general quality. A paper
                doing nearly the same thing scores highest. Say in "why" what
                transfers or what differs. Use the exact id given in brackets.
                """.formatted(describe(profile), listing);
    }

    /**
     * Reads the ranking, keeping only ids that were actually offered.
     *
     * <p>Both guards matter. A model can invent an id, which would otherwise be
     * rendered as a paper that does not exist; and it can silently omit one,
     * which would lose a real result to a formatting slip.
     */
    static List<RankedPaper> parseRanking(String reply, List<PaperRecord> candidates) {
        Map<String, PaperRecord> byId = new LinkedHashMap<>();
        for (PaperRecord p : candidates) {
            byId.put(p.getId(), p);
        }

        Matcher json = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(reply);
        if (!json.find()) {
            return List.of();
        }

        List<RankedPaper> ranked = new ArrayList<>();
        try {
            JsonNode entries = MAPPER.readTree(json.group()).path("ranked");
            for (JsonNode entry : entries) {
                PaperRecord paper = byId.remove(entry.path("id").asText(""));
                if (paper == null) {
                    continue; // invented, or a duplicate of one already ranked
                }
                int score = Math.max(0, Math.min(100, entry.path("score").asInt(0)));
                ranked.add(new RankedPaper(paper, score,
                        entry.path("why").asText(""),
                        entry.path("closest_prior_work").asBoolean(false)));
            }
        } catch (Exception e) {
            return List.of();
        }
        return ranked;
    }

    private static List<RankedPaper> unranked(List<PaperRecord> found) {
        List<RankedPaper> papers = new ArrayList<>();
        for (PaperRecord paper : found) {
            papers.add(RankedPaper.unranked(paper));
        }
        return papers;
    }
}
