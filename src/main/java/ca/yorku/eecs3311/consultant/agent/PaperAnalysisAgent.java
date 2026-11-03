package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.analysis.StrategyTaxonomy;
import ca.yorku.eecs3311.consultant.analysis.UnparseablePaperException;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F04 and F05 — turns a paper into a structured record and tags the strategy it
 * uses.
 *
 * <p>The two features share one agent because classification is the last step
 * of analysis rather than a separate interaction, which is why SD04 covers both.
 *
 * <p>Classification is deliberately hybrid. {@link StrategyTaxonomy} matches the
 * extracted method deterministically; the model also proposes tags; and a tag
 * the taxonomy does not recognise marks the result <em>uncertain</em> instead of
 * being asserted, which is UC04 flow 5a. Without that split "uncertain" would
 * be decorative — there would be nothing for the model's suggestion to be
 * checked against.
 */
public class PaperAnalysisAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StrategyTaxonomy taxonomy;

    /**
     * The paper under analysis, set by {@link #plan} and read by
     * {@link #executeTools}.
     *
     * <p>Carried in a field because the template passes a {@link Plan} between
     * those two steps, not the original input. {@code Agent.run} is final and
     * must stay that way — overriding it to smuggle the input through would
     * defeat the pattern the class exists to demonstrate.
     */
    private PaperRecord pending;

    public PaperAnalysisAgent(ToolManager tools) {
        this(tools, new StrategyTaxonomy());
    }

    public PaperAnalysisAgent(ToolManager tools, StrategyTaxonomy taxonomy) {
        super(tools);
        if (taxonomy == null) {
            throw new IllegalArgumentException("taxonomy must not be null");
        }
        this.taxonomy = taxonomy;
    }

    public StrategyTaxonomy getTaxonomy() {
        return taxonomy;
    }

    // --- Template Method hooks ----------------------------------------------

    @Override
    protected Plan plan(Object input) throws AgentException {
        if (!(input instanceof PaperRecord paper)) {
            throw new AgentException("analysis needs a PaperRecord, got "
                    + (input == null ? "null" : input.getClass().getSimpleName()));
        }
        this.pending = paper;
        return new Plan()
                .addStep("read the paper's text")
                .addStep("extract problem, method, dataset, model, metrics, results, limitations")
                .addStep("classify the strategy and check it against the taxonomy")
                .addToolCall("parse " + paper.getId());
    }

    @Override
    protected Object executeTools(Plan plan) throws AgentException {
        PaperRecord paper = pending;
        if (paper == null) {
            throw new AgentException("no paper to analyse");
        }
        try {
            return new Parsed(paper, tools.parsePaper(paper));
        } catch (UnparseablePaperException e) {
            // UC04 3a: flagged, not analysed from its title
            throw new AgentException(e.getMessage(), e);
        }
    }

    @Override
    protected Object summarize(Object result) {
        Parsed parsed = (Parsed) result;
        PaperAnalysis analysis = extract(parsed);
        classify(analysis);
        return analysis;
    }

    private record Parsed(PaperRecord paper, String text) {
    }

    // --- F04: extraction -----------------------------------------------------

    /**
     * Extracts the structured fields, leaving anything the model did not give
     * as {@code unknown}.
     *
     * <p>UC04 flow 4a: a partial extraction is still saved. A field the model
     * omitted must stay {@code unknown} rather than being filled with a plausible
     * value, because a fabricated dataset name makes the F07 comparability check
     * silently wrong.
     */
    private PaperAnalysis extract(Parsed parsed) {
        PaperAnalysis analysis = new PaperAnalysis(parsed.paper());

        String reply;
        try {
            reply = tools.callLLM("""
                    Extract the structure of this paper. Return JSON only, no prose.

                    Title: %s
                    Abstract: %s

                    {"problem":"...","method":"...","dataset":"...","model":"...",
                     "metrics":["..."],"results":"...","limitations":"...",
                     "strategy_tags":["..."]}

                    Rules:
                    - use the string "unknown" for any field the text does not state.
                      Never guess a dataset, model or number that is not written there
                    - "method" is one or two sentences on what they actually did
                    - "metrics" lists only metrics the text names
                    - "strategy_tags" names the techniques used, lowercase, 1 to 4 tags
                    """.formatted(parsed.paper().getTitle(), parsed.text()));
        } catch (LLMUnavailableException e) {
            // every field stays unknown; the record is still created and saved
            return analysis;
        }

        Matcher json = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(reply);
        if (!json.find()) {
            return analysis;
        }
        try {
            JsonNode node = MAPPER.readTree(json.group());
            analysis.setProblem(field(node, "problem"));
            analysis.setMethod(field(node, "method"));
            analysis.setDataset(field(node, "dataset"));
            analysis.setModel(field(node, "model"));
            analysis.setResults(field(node, "results"));
            analysis.setLimitations(field(node, "limitations"));
            analysis.setMetrics(strings(node.path("metrics")));
            analysis.setStrategyTags(new ArrayList<>(strings(node.path("strategy_tags"))));
        } catch (Exception e) {
            return new PaperAnalysis(parsed.paper());
        }
        return analysis;
    }

    private static String field(JsonNode node, String name) {
        String value = node.path(name).asText("").strip();
        return value.isBlank() ? PaperAnalysis.UNKNOWN : value;
    }

    /**
     * Reads a JSON array of strings, dropping blanks and the {@code unknown}
     * sentinel.
     *
     * <p>The sentinel belongs in a scalar field, where {@code isUnknown()} can
     * recognise it, never as an element of a list. A metrics list holding the
     * single entry "unknown" is <em>not</em> a paper that reports a metric
     * called "unknown": it is a paper whose metrics were not stated, and
     * storing it that way made F07 answer "different measures" where it should
     * have answered "cannot determine".
     */
    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                String value = item.asText("").strip();
                if (!value.isBlank() && !PaperAnalysis.isUnknown(value)) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    // --- F05: classification -------------------------------------------------

    /**
     * Settles the strategy tags, marking them uncertain when the taxonomy does
     * not recognise what the model proposed.
     */
    void classify(PaperAnalysis analysis) {
        Set<String> tags = new LinkedHashSet<>(taxonomy.match(analysis.getMethod()));
        int recognisedFromModel = 0;

        for (String suggested : analysis.getStrategyTags()) {
            if (taxonomy.isKnown(suggested)) {
                tags.add(taxonomy.canonical(suggested));
                recognisedFromModel++;
            }
        }

        boolean modelProposedSomething = !analysis.getStrategyTags().isEmpty();
        analysis.setStrategyTags(new ArrayList<>(tags));

        // uncertain when there is nothing the taxonomy stands behind, or when
        // the model's suggestions were all unrecognised
        analysis.setTagsUncertain(
                tags.isEmpty() || (modelProposedSomething && recognisedFromModel == 0));
    }

    /** F05 on an analysis that already exists, without re-extracting it. */
    public List<String> classifyExisting(PaperAnalysis analysis) {
        if (analysis == null) {
            throw new IllegalArgumentException("analysis must not be null");
        }
        classify(analysis);
        return analysis.getStrategyTags();
    }
}
