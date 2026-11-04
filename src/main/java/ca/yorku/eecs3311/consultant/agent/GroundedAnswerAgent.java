package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.analysis.EvidenceRetriever;
import ca.yorku.eecs3311.consultant.domain.Answer;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.literature.LiteratureUnavailableException;
import ca.yorku.eecs3311.consultant.literature.QueryTerms;
import ca.yorku.eecs3311.consultant.literature.SearchIntent;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import java.util.ArrayList;
import java.util.List;

/**
 * F08 and F11: retrieve first, then answer only from what was retrieved.
 *
 * <p>Both features are one pipeline with one optional step, exactly as SD06
 * draws it — F08 additionally queries the live literature source, F11 never
 * does. Everything else is shared: gather the permitted evidence, and if there
 * is none, say so instead of asking the model.
 *
 * <p><strong>Template Method.</strong> {@code plan()} retrieves, so the
 * retrieve-before-answer ordering is enforced by {@link Agent#run} rather than
 * by each caller remembering it. That ordering is the feature: without it this
 * would be a thin wrapper around a language model.
 *
 * <p>This is a separate agent from {@code ResearchConsultantAgent}, which
 * Stage 1 named for both features. One {@code Agent} subclass has exactly one
 * {@code plan}/{@code executeTools}/{@code summarize} pipeline, and that one is
 * already bound to comparison. Forcing a second pipeline through it would turn
 * {@code plan()} into a switch on the input type, which is the opposite of what
 * a template is for. Recorded as a Stage 2 change.
 */
public class GroundedAnswerAgent extends Agent {

    /** Which of the two features is running. */
    public enum Mode {

        /** F11 — answer from stored project data only. */
        ASK,

        /** F08 — also search the live literature for prior work. */
        PRIOR_WORK
    }

    /**
     * How many terms the literature query may carry.
     *
     * <p>arXiv ANDs them, so this is a ceiling on specificity, not a budget.
     */
    private static final int MAX_QUERY_TERMS = 4;

    private final EvidenceRetriever retriever;
    private Query pending;

    public GroundedAnswerAgent(ToolManager tools) {
        this(tools, new EvidenceRetriever());
    }

    public GroundedAnswerAgent(ToolManager tools, EvidenceRetriever retriever) {
        super(tools);
        if (retriever == null) {
            throw new IllegalArgumentException("retriever must not be null");
        }
        this.retriever = retriever;
    }

    /** What the agent works from. */
    public record Query(Mode mode, String text, ProjectProfile profile,
                        List<PaperAnalysis> analyses) {
        public Query {
            if (mode == null) {
                throw new IllegalArgumentException("mode must not be null");
            }
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("the question must not be null or blank");
            }
            if (profile == null) {
                throw new IllegalArgumentException("profile must not be null");
            }
            analyses = analyses == null ? List.of() : List.copyOf(analyses);
        }
    }

    /** What the evidence gathering produced, before any prose. */
    private record Evidence(List<PaperAnalysis> stored, List<PaperRecord> live, String gap) {
    }

    /** F11 — the method the Stage 1 class diagram names {@code ask()}. */
    public Answer ask(String question, ProjectProfile profile, List<PaperAnalysis> analyses)
            throws AgentException {
        return (Answer) run(new Query(Mode.ASK, question, profile, analyses));
    }

    /** F08 — the method the Stage 1 class diagram names {@code searchPriorWork()}. */
    public Answer searchPriorWork(String idea, ProjectProfile profile,
            List<PaperAnalysis> analyses) throws AgentException {
        return (Answer) run(new Query(Mode.PRIOR_WORK, idea, profile, analyses));
    }

    // --- Template Method hooks ----------------------------------------------

    @Override
    protected Plan plan(Object input) throws AgentException {
        if (!(input instanceof Query query)) {
            throw new AgentException("grounded answering needs a Query, got "
                    + (input == null ? "null" : input.getClass().getSimpleName()));
        }
        this.pending = query;

        Plan plan = new Plan()
                .addStep("retrieve the stored records that match the question")
                .addToolCall("evidence retrieval");
        if (query.mode() == Mode.PRIOR_WORK) {
            plan.addStep("also search the live literature, by relevance rather than date")
                    .addToolCall("literature search");
        }
        return plan.addStep("answer using only what was retrieved, or report that"
                + " nothing was found");
    }

    @Override
    protected Object executeTools(Plan plan) throws AgentException {
        if (pending == null) {
            throw new AgentException("nothing to answer");
        }
        List<PaperAnalysis> stored = retriever.relevantTo(pending.text(), pending.analyses());
        List<PaperRecord> live = new ArrayList<>();
        String gap = "";

        if (pending.mode() == Mode.PRIOR_WORK) {
            try {
                String wanted = searchQuery(pending.text());
                List<PaperRecord> raw = new ArrayList<>();
                String used = searchRelaxing(wanted, raw);
                // the same relevance bar the stored analyses had to clear: a
                // broadened query returns papers about a different field, and
                // citing them would dress weak evidence up as evidence
                live.addAll(retriever.papersRelevantTo(pending.text(), raw));

                if (!used.isBlank() && !used.equals(wanted)) {
                    gap = live.isEmpty()
                            ? "the literature search had to be broadened to \"" + used
                                    + "\", and none of what it returned matched the idea"
                                    + " closely enough to cite"
                            : "the literature search was broadened to \"" + used
                                    + "\" before it returned anything, so those results are"
                                    + " less specific than the idea as described";
                } else if (!raw.isEmpty() && live.isEmpty()) {
                    gap = "the literature search returned " + raw.size()
                            + (raw.size() == 1 ? " paper, which did not match" : " papers, none"
                                    + " of which matched")
                            + " the idea closely enough to cite";
                }
            } catch (LiteratureUnavailableException e) {
                // UC07 4a: local evidence still stands; the gap is stated, not hidden
                gap = "the literature search was unavailable (" + e.getMessage()
                        + "), so only your own analysed papers were searched";
            }
        }
        return new Evidence(stored, live, gap);
    }

    @Override
    protected Object summarize(Object result) {
        Evidence evidence = (Evidence) result;
        Answer answer = new Answer(pending.text());
        answer.setNote(evidence.gap());

        boolean profileIsEvidence = pending.mode() == Mode.ASK
                && mentionsTheProjectItself(pending.text(), pending.profile());

        if (evidence.stored().isEmpty() && evidence.live().isEmpty() && !profileIsEvidence) {
            return nothingFound(answer);
        }

        answer.setGrounded(true);
        if (profileIsEvidence) {
            answer.addSource("your project profile");
        }
        for (PaperAnalysis a : evidence.stored()) {
            answer.addSource(a.getPaper().getId() + " - " + a.getPaper().getTitle()
                    + " (already analysed)");
        }
        for (PaperRecord p : evidence.live()) {
            answer.addSource(p.getId() + " - " + p.getTitle() + " (found just now)");
        }
        if (pending.mode() == Mode.PRIOR_WORK) {
            answer.setNote(join(evidence.gap(), "This is the closest evidence these searches"
                    + " found, not proof that the idea is new or settled."));
        }

        try {
            answer.setText(tools.callLLM(prompt(evidence, profileIsEvidence)));
        } catch (LLMUnavailableException e) {
            // UC07 5a / UC10 4a: retrieval succeeded, so the sources are real
            // and worth showing; only the prose is missing
            answer.setText("");
            answer.setNote(join(answer.getNote(),
                    "the model could not be reached, so the records are listed without a summary"));
        }
        return answer;
    }

    /**
     * Turns an idea written as a sentence into something arXiv can answer.
     *
     * <p>arXiv ANDs its terms, so the raw sentence returns nothing. Measured
     * live, the idea "oversampling synthetic chest x-rays for the
     * worst-performing demographic subgroup instead of balancing all subgroups
     * equally" returned 0 papers; "synthetic chest x-ray subgroup fairness"
     * returned 2 and "subgroup fairness synthetic data" returned 10. Sending
     * the sentence made the live half of F08 silently contribute nothing.
     *
     * <p>The first terms are kept because an idea is normally written with its
     * subject first and its qualifiers after.
     */
    private String searchQuery(String idea) {
        List<String> terms = retriever.termsOf(idea);
        return String.join(" ", terms.subList(0, Math.min(MAX_QUERY_TERMS, terms.size())));
    }

    /**
     * Searches, dropping a term at a time while nothing comes back.
     *
     * <p>The same relaxation F03 needs, for the same reason. Four ANDed terms
     * is already enough to empty the result set.
     *
     * @param into collects the papers found
     * @return the query that actually returned something, or blank if none did
     */
    private String searchRelaxing(String query, List<PaperRecord> into)
            throws LiteratureUnavailableException {
        String current = query;
        while (!current.isBlank()) {
            // MOST_RELEVANT: for prior work a five-year-old paper can be the
            // most important result, so date ordering would bury it
            List<PaperRecord> found = tools.search(current, SearchIntent.MOST_RELEVANT);
            if (!found.isEmpty()) {
                into.addAll(found);
                return current;
            }
            current = QueryTerms.dropLastTerm(current);
        }
        return "";
    }

    /**
     * The empty result, phrased as a limit of the search rather than a finding
     * about the world.
     *
     * <p>The model is never called here. There is nothing for it to work from,
     * and asking it anyway is exactly how an ungrounded answer gets presented
     * as a grounded one.
     */
    private Answer nothingFound(Answer answer) {
        answer.setGrounded(false);
        if (pending.mode() == Mode.PRIOR_WORK) {
            answer.setText("No matching evidence found. That is a result about these"
                    + " searches, not about the field - absence of evidence here is not"
                    + " evidence that the idea is unexplored.");
            answer.setNote(join(answer.getNote(), "searched for: "
                    + String.join(", ", retriever.termsOf(pending.text()))));
        } else {
            answer.setText("Nothing relevant is stored for this question yet. Analyse a"
                    + " paper or import your project, and ask again.");
            answer.setNote(join(answer.getNote(), "searched your profile and "
                    + pending.analyses().size() + " analysed paper(s) for: "
                    + String.join(", ", retriever.termsOf(pending.text()))));
        }
        return answer;
    }

    /**
     * Whether the profile itself answers the question.
     *
     * <p>"Which metrics am I reporting?" is answerable from stored project data
     * with no paper involved, so the profile has to count as evidence — but
     * only when the question actually overlaps it, otherwise every question
     * would look grounded.
     */
    private boolean mentionsTheProjectItself(String question, ProjectProfile profile) {
        // the field names belong in here as well as the values: "which metrics
        // am I reporting?" is a question about the profile even though the word
        // "metrics" appears nowhere in the metrics the researcher listed
        String haystack = ("research question datasets dataset models model metrics metric"
                + " results result baselines profile project "
                + profile.getResearchQuestion() + " "
                + String.join(" ", profile.getDatasets()) + " "
                + String.join(" ", profile.getModels()) + " "
                + String.join(" ", profile.getMetrics()) + " "
                + String.join(" ", profile.getOwnResults())).toLowerCase();
        for (String term : retriever.termsOf(question)) {
            if (haystack.contains(term)) {
                return true;
            }
        }
        // Only possessives, not every first person pronoun. Matching " i "
        // treated "How do I tune a quadruped gait controller?" as a question
        // about this project, and answered it from a chest X-ray profile.
        String lower = " " + question.toLowerCase() + " ";
        return !profile.getResearchQuestion().isBlank()
                && (lower.contains(" my ") || lower.contains(" our ")
                        || lower.contains(" mine "));
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "; " + second;
    }

    private String prompt(Evidence evidence, boolean profileIsEvidence) {
        StringBuilder records = new StringBuilder();
        if (profileIsEvidence) {
            ProjectProfile p = pending.profile();
            records.append("The researcher's own project:\n")
                    .append("- question: ").append(p.getResearchQuestion()).append('\n')
                    .append("- datasets: ").append(String.join(", ", p.getDatasets()))
                    .append('\n')
                    .append("- models: ").append(String.join(", ", p.getModels())).append('\n')
                    .append("- metrics: ").append(String.join(", ", p.getMetrics())).append('\n')
                    .append("- results so far: ").append(String.join(", ", p.getOwnResults()))
                    .append('\n');
        }
        for (PaperAnalysis a : evidence.stored()) {
            records.append("\nAnalysed paper ").append(a.getPaper().getId()).append(" - ")
                    .append(a.getPaper().getTitle()).append('\n')
                    .append("- problem: ").append(a.getProblem()).append('\n')
                    .append("- method: ").append(a.getMethod()).append('\n')
                    .append("- dataset: ").append(a.getDataset()).append('\n')
                    .append("- metrics: ").append(String.join(", ", a.getMetrics())).append('\n')
                    .append("- results: ").append(a.getResults()).append('\n');
        }
        for (PaperRecord p : evidence.live()) {
            records.append("\nPaper found just now ").append(p.getId()).append(" - ")
                    .append(p.getTitle()).append('\n')
                    .append("- abstract: ").append(p.getSummary()).append('\n');
        }

        String task = pending.mode() == Mode.PRIOR_WORK
                ? """
                  Say what the closest evidence to this idea is, and how it differs
                  from the idea as described. Do not say whether the idea is new -
                  these searches cannot establish that.
                  """
                : """
                  Answer the question.
                  """;

        return """
                %s
                Question: %s

                Records retrieved:
                %s
                Use only the records above. Cite the record you used by its id.
                If they do not contain the answer, say which part is missing rather
                than filling it in from general knowledge. Keep it under 150 words.
                """.formatted(task, pending.text(), records);
    }
}
