package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.analysis.ComparabilityChecker;
import ca.yorku.eecs3311.consultant.domain.Comparability;
import ca.yorku.eecs3311.consultant.domain.ComparisonResult;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F06 and F07 — explains how a paper relates to the researcher's own project,
 * and whether the two sets of numbers can fairly be compared.
 *
 * <p>The order is deliberate and is what UC05's {@code <<include>>} of UC06
 * means in code: the **deterministic** verdict is computed first, then the
 * model is asked to put it into words. The reverse order would let a fluent
 * narrative talk the researcher into a comparison the data does not support.
 * When the model is unavailable the verdict and the raw mismatch list still
 * stand, which is UC06 flow 3a.
 */
public class ResearchConsultantAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ComparabilityChecker checker;

    /** Carried from {@link #plan} to the later steps. */
    private Comparison pending;

    public ResearchConsultantAgent(ToolManager tools) {
        this(tools, new ComparabilityChecker());
    }

    public ResearchConsultantAgent(ToolManager tools, ComparabilityChecker checker) {
        super(tools);
        if (checker == null) {
            throw new IllegalArgumentException("checker must not be null");
        }
        this.checker = checker;
    }

    /** What the agent works from: a paper and the project to compare it against. */
    public record Comparison(PaperAnalysis analysis, ProjectProfile profile) {
        public Comparison {
            if (analysis == null) {
                throw new IllegalArgumentException("analysis must not be null");
            }
            if (profile == null) {
                throw new IllegalArgumentException("profile must not be null");
            }
        }
    }

    /** F06 — the method the Stage 1 class diagram names. */
    public ComparisonResult compare(PaperAnalysis analysis, ProjectProfile profile)
            throws AgentException {
        return (ComparisonResult) run(new Comparison(analysis, profile));
    }

    public ComparabilityChecker getChecker() {
        return checker;
    }

    // --- Template Method hooks ----------------------------------------------

    @Override
    protected Plan plan(Object input) throws AgentException {
        if (!(input instanceof Comparison comparison)) {
            throw new AgentException("comparison needs a Comparison, got "
                    + (input == null ? "null" : input.getClass().getSimpleName()));
        }
        this.pending = comparison;
        return new Plan()
                .addStep("check result comparability deterministically")
                .addStep("list every specific mismatch")
                .addStep("ask the model to explain the comparison in context")
                .addToolCall("comparability check");
    }

    @Override
    protected Object executeTools(Plan plan) throws AgentException {
        if (pending == null) {
            throw new AgentException("nothing to compare");
        }
        // F07 runs here, before the model is involved at all
        ComparisonResult result = new ComparisonResult(pending.analysis().getPaper().getTitle());
        result.setComparability(checker.isComparable(pending.analysis(), pending.profile()));
        result.setMismatches(checker.findMismatches(pending.analysis(), pending.profile()));
        result.setComparabilityNote(checker.explain(pending.analysis(), pending.profile()));
        return result;
    }

    @Override
    protected Object summarize(Object result) {
        ComparisonResult comparison = (ComparisonResult) result;
        try {
            addNarrative(comparison, tools.callLLM(prompt(comparison)));
        } catch (LLMUnavailableException e) {
            // UC06 3a / UC05 5a: the verdict and the mismatches are already
            // set and stand on their own; only the prose is missing
            comparison.setNarrative("");
        }
        return comparison;
    }

    // --- the narrative -------------------------------------------------------

    private String prompt(ComparisonResult computed) {
        PaperAnalysis a = pending.analysis();
        ProjectProfile p = pending.profile();
        return """
                Compare a paper with a researcher's own project.

                THE RESEARCHER'S PROJECT
                Question: %s
                Datasets: %s
                Models: %s
                Metrics: %s
                Their results so far: %s

                THE PAPER: %s
                Problem: %s
                Method: %s
                Dataset: %s
                Model: %s
                Metrics: %s
                Results: %s
                Limitations: %s

                A deterministic check has ALREADY decided whether the two sets of
                numbers can be compared. Its verdict is %s, because: %s
                Do not contradict that verdict or claim the numbers are comparable
                when it says they are not. Explain it, in context.

                Return JSON only:
                {"similarities":["..."],"differences":["..."],
                 "narrative":"two or three sentences: what this paper means for
                 this project, and what is genuinely novel on either side"}

                Be concrete and use the two projects' own terms. Do not invent
                numbers that are not given above.
                """.formatted(
                p.getResearchQuestion(),
                String.join(", ", p.getDatasets()),
                String.join(", ", p.getModels()),
                String.join(", ", p.getMetrics()),
                String.join("; ", p.getOwnResults()),
                a.getPaper().getTitle(),
                a.getProblem(), a.getMethod(), a.getDataset(), a.getModel(),
                String.join(", ", a.getMetrics()), a.getResults(), a.getLimitations(),
                computed.getComparability().label(),
                computed.getMismatches().isEmpty()
                        ? "the dataset and the measure agree"
                        : String.join("; ", computed.getMismatches()));
    }

    /**
     * Reads the model's reply into the result, leaving the deterministic fields
     * untouched. A reply that cannot be parsed costs the narrative only.
     */
    private static void addNarrative(ComparisonResult result, String reply) {
        Matcher json = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(reply);
        if (!json.find()) {
            result.setNarrative("");
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(json.group());
            for (JsonNode item : node.path("similarities")) {
                addIfPresent(item.asText(""), result::addSimilarity);
            }
            for (JsonNode item : node.path("differences")) {
                addIfPresent(item.asText(""), result::addDifference);
            }
            result.setNarrative(node.path("narrative").asText("").strip());
        } catch (Exception e) {
            result.setNarrative("");
        }
    }

    private static void addIfPresent(String value, java.util.function.Consumer<String> sink) {
        if (value != null && !value.isBlank()) {
            sink.accept(value.strip());
        }
    }

    /** @return true when the verdict says the numbers line up */
    public static boolean linesUp(ComparisonResult result) {
        return result.getComparability() == Comparability.COMPARABLE;
    }

    /** @return the mismatch list, for a caller that wants it without the prose */
    public List<String> mismatchesFor(PaperAnalysis analysis, ProjectProfile profile) {
        return checker.findMismatches(analysis, profile);
    }
}
