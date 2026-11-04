package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.Answer;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.literature.FakeLiteratureSource;
import ca.yorku.eecs3311.consultant.literature.QueryTerms;
import ca.yorku.eecs3311.consultant.literature.SearchIntent;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F08 and F11 share one shape: retrieve first, then answer only from what was
 * retrieved (SD06). These tests are mostly about the failure branch, because
 * that is where the feature earns its keep — an ungrounded answer that looks
 * grounded is worse than no answer.
 */
class GroundedAnswerAgentTest {

    private FakeLLMClient llm;
    private FakeLiteratureSource source;
    private GroundedAnswerAgent agent;

    @BeforeEach
    void setUp() {
        llm = new FakeLLMClient();
        source = new FakeLiteratureSource("arXiv");
        ToolManager tools = new ToolManager(llm);
        tools.setLiteratureSource(source);
        agent = new GroundedAnswerAgent(tools);
    }

    private static ProjectProfile profile() {
        ProjectProfile p = new ProjectProfile(
                "Does conditioning a generator on demographics close the fairness gap?");
        p.setDatasets(List.of("CheXpert", "MIMIC-CXR"));
        p.setMetrics(List.of("AUROC", "subgroup AUROC gap"));
        return p;
    }

    private static PaperAnalysis fairDiffusion() {
        PaperAnalysis a = new PaperAnalysis(
                new PaperRecord("2412.20374v2", "FairDiffusion", "arXiv cs.CV", "2024-12-29"));
        a.setMethod("a fair Bayesian perturbation inside a latent diffusion model");
        a.setDataset("CheXpert");
        a.setStrategyTags(List.of("diffusion model"));
        return a;
    }

    private static PaperRecord fromArxiv() {
        PaperRecord p = new PaperRecord(
                "2508.16783v1", "Finely-Controllable Synthetic Data", "arXiv cs.CV", "2025-08-22");
        p.setSummary("controllable synthetic chest radiographs improve fairness");
        p.setUrl("http://arxiv.org/abs/2508.16783v1");
        return p;
    }

    // --- F11: ask a project-aware question -----------------------------------

    @Test
    void anAnswerNamesTheRecordsItRestsOn() throws Exception {
        llm.respondWith("Your CheXpert work lines up with FairDiffusion's conditioning.");

        Answer answer = agent.ask("What does FairDiffusion do on CheXpert?",
                profile(), List.of(fairDiffusion()));

        assertTrue(answer.isGrounded());
        assertTrue(answer.getSources().stream().anyMatch(s -> s.contains("2412.20374v2")),
                "the answer must be checkable: " + answer.getSources());
    }

    @Test
    void withNothingRelevantStoredItSaysSoInsteadOfAnswering() throws Exception {
        llm.respondWith("Reinforcement learning works well for locomotion.");

        Answer answer = agent.ask("How do I tune a quadruped gait controller?",
                profile(), List.of(fairDiffusion()));

        assertFalse(answer.isGrounded());
        assertEquals(0, llm.callCount(),
                "the model must not be asked at all when nothing was retrieved");
        assertTrue(answer.getText().toLowerCase().contains("nothing relevant")
                        || answer.getText().toLowerCase().contains("no relevant"),
                answer.getText());
    }

    @Test
    void theProfileCountsAsEvidenceForAQuestionAboutTheProjectItself() throws Exception {
        llm.respondWith("You measure AUROC and the subgroup AUROC gap.");

        Answer answer = agent.ask("Which metrics am I reporting?", profile(), List.of());

        assertTrue(answer.isGrounded(),
                "the project profile is stored project data and may ground an answer");
        assertTrue(answer.getSources().stream().anyMatch(s -> s.toLowerCase().contains("profile")),
                answer.getSources().toString());
    }

    @Test
    void theRetrievedRecordsAreInThePromptAndNothingElseIs() throws Exception {
        llm.respondWith("an answer");

        agent.ask("What does FairDiffusion do on CheXpert?", profile(), List.of(fairDiffusion()));

        String prompt = llm.promptsSeen().get(0);
        assertTrue(prompt.contains("FairDiffusion"), prompt);
        assertTrue(prompt.toLowerCase().contains("only"),
                "the prompt must confine the model to the retrieved records: " + prompt);
    }

    @Test
    void anUnreachableModelLeavesTheSourcesStanding() throws Exception {
        llm.failWith("HTTP 529 overloaded");

        Answer answer = agent.ask("What does FairDiffusion do on CheXpert?",
                profile(), List.of(fairDiffusion()));

        assertTrue(answer.isTextMissing());
        assertFalse(answer.getSources().isEmpty(),
                "retrieval already succeeded; its result is still useful");
        assertTrue(answer.getNote().toLowerCase().contains("model"), answer.getNote());
    }

    // --- F08: has anyone tried this ------------------------------------------

    @Test
    void priorWorkSearchesTheLiteratureAsWellAsMemory() throws Exception {
        source.returning(fromArxiv());
        llm.respondWith("Two lines of work are close to this idea.");

        Answer answer = agent.searchPriorWork(
                "conditioning a diffusion model on demographics for chest radiographs",
                profile(), List.of(fairDiffusion()));

        assertTrue(answer.isGrounded());
        assertEquals(List.of(SearchIntent.MOST_RELEVANT), source.intentsSeen(),
                "prior work sorts by relevance, not by date");
        assertTrue(answer.getSources().stream().anyMatch(s -> s.contains("2508.16783v1")),
                "a paper found live must be cited too: " + answer.getSources());
        assertTrue(answer.getSources().stream().anyMatch(s -> s.contains("2412.20374v2")),
                "a paper already analysed must be cited too: " + answer.getSources());
    }

    @Test
    void findingNothingIsNotTheClaimThatNoOneHasTriedIt() throws Exception {
        // two searches cannot prove absence
        source.returning();

        Answer answer = agent.searchPriorWork(
                "quantum annealing for radiograph fairness", profile(), List.of());

        assertFalse(answer.isGrounded());
        String text = answer.getText().toLowerCase();
        assertFalse(text.contains("no one has"), answer.getText());
        assertFalse(text.contains("nobody has"), answer.getText());
        assertFalse(text.contains("has not been tried"), answer.getText());
        assertTrue(text.contains("no matching evidence"), answer.getText());
    }

    @Test
    void aFailedLiteratureSearchStillReturnsWhatMemoryHad() throws Exception {
        source.failWith("HTTP 503");
        llm.respondWith("Your own analysed paper is the closest evidence.");

        Answer answer = agent.searchPriorWork(
                "a fair Bayesian perturbation in a latent diffusion model",
                profile(), List.of(fairDiffusion()));

        assertTrue(answer.isGrounded(), "local evidence survives a failed literature call");
        assertTrue(answer.getNote().toLowerCase().contains("literature"),
                "the gap must be stated, not hidden: " + answer.getNote());
    }

    @Test
    void theAnswerIsNeverADefinitiveYesOrNo() throws Exception {
        source.returning(fromArxiv());
        llm.respondWith("Yes. This has definitely been done.");

        Answer answer = agent.searchPriorWork(
                "controllable synthetic chest radiographs for fairness",
                profile(), List.of());

        assertFalse(answer.getNote().isBlank(),
                "F08 output carries a confidence note, not a verdict");
    }

    // --- the Template Method -------------------------------------------------

    @Test
    void thePlanIsRecordedAndShowsThatRetrievalComesFirst() throws Exception {
        llm.respondWith("an answer");

        agent.ask("What does FairDiffusion do on CheXpert?", profile(), List.of(fairDiffusion()));

        Plan plan = agent.getLastPlan();
        assertFalse(plan.getSteps().isEmpty());
        assertTrue(plan.getSteps().get(0).toLowerCase().contains("retrieve"),
                "retrieve-then-answer is the point of the feature: " + plan.getSteps());
    }

    @Test
    void thePlanRecordsTheLiteratureCallOnlyForPriorWork() throws Exception {
        llm.respondWith("an answer");
        source.returning(fromArxiv());

        agent.ask("What does FairDiffusion do on CheXpert?", profile(), List.of(fairDiffusion()));
        assertTrue(agent.getLastPlan().getToolCalls().stream()
                .noneMatch(c -> c.toLowerCase().contains("literature")),
                "F11 never queries the literature: " + agent.getLastPlan().getToolCalls());

        agent.searchPriorWork("controllable synthetic chest radiographs", profile(), List.of());
        assertTrue(agent.getLastPlan().getToolCalls().stream()
                .anyMatch(c -> c.toLowerCase().contains("literature")),
                "F08 does: " + agent.getLastPlan().getToolCalls());
    }

    @Test
    void aBlankQuestionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> agent.ask("  ", profile(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> agent.searchPriorWork(null, profile(), List.of()));
    }

    @Test
    void theRunTemplateRefusesAnInputItDoesNotUnderstand() {
        assertThrows(AgentException.class, () -> agent.run("just a string"));
    }

    // --- the literature query F08 actually sends -----------------------------

    private static final String A_REAL_IDEA =
            "oversampling synthetic chest x-rays for the worst-performing demographic"
            + " subgroup instead of balancing all subgroups equally";

    @Test
    void theLiteratureQueryIsBuiltFromTermsNotTheWholeSentence() throws Exception {
        source.returning(fromArxiv());
        llm.respondWith("an answer");

        agent.searchPriorWork(A_REAL_IDEA, profile(), List.of());

        String sent = source.queriesSeen().get(0);
        assertFalse(sent.contains("instead"), "stopwords must not reach arXiv: " + sent);
        assertFalse(sent.contains(" for "), "stopwords must not reach arXiv: " + sent);
        assertTrue(QueryTerms.count(sent) <= 4,
                "arXiv ANDs its terms, so a whole sentence returns nothing: " + sent);
    }

    /** A live result that genuinely matches {@link #A_REAL_IDEA}. */
    private static PaperRecord matchingPriorWork() {
        PaperRecord p = new PaperRecord("2601.00001v1",
                "Subgroup-Targeted Synthetic Augmentation", "arXiv cs.CV", "2026-01-02");
        p.setSummary("oversampling synthetic chest radiographs for underperforming"
                + " demographic subgroups rather than balancing all subgroups");
        p.setUrl("http://arxiv.org/abs/2601.00001v1");
        return p;
    }

    @Test
    void liveResultsThatDoNotMatchTheIdeaAreNotCited() throws Exception {
        // a broadened query returns papers from other fields; citing them would
        // dress weak evidence up as evidence
        PaperRecord offTopic = new PaperRecord("1504.06667v2",
                "Handling oversampling in dynamic networks using link prediction",
                "arXiv cs.SI", "2015-06-25");
        offTopic.setSummary("link prediction in dynamic networks with oversampling");
        source.returning(offTopic);
        llm.respondWith("an answer");

        Answer answer = agent.searchPriorWork(A_REAL_IDEA, profile(), List.of(fairDiffusion()));

        assertTrue(answer.getSources().stream().noneMatch(s -> s.contains("1504.06667v2")),
                "an off-topic result must not be listed as evidence: " + answer.getSources());
        assertTrue(answer.getNote().toLowerCase().contains("did not match")
                        || answer.getNote().toLowerCase().contains("none of which matched")
                        || answer.getNote().toLowerCase().contains("none of what it returned"),
                "the discarded results are accounted for: " + answer.getNote());
    }

    @Test
    void theLiteratureQueryIsRelaxedUntilSomethingComesBack() throws Exception {
        // measured live: this idea returned 0 papers at 4 terms and 10 at 3
        source.emptyUntilRelaxedTo(2).returning(matchingPriorWork());
        llm.respondWith("an answer");

        Answer answer = agent.searchPriorWork(A_REAL_IDEA, profile(), List.of());

        assertTrue(answer.getSources().stream().anyMatch(s -> s.contains("2601.00001v1")),
                "relaxing is what makes the live half of F08 work at all: "
                        + answer.getSources());
        assertTrue(source.queriesSeen().size() > 1,
                "it should have tried a narrower query first: " + source.queriesSeen());
    }

    @Test
    void broadeningTheSearchIsStatedRatherThanHidden() throws Exception {
        source.emptyUntilRelaxedTo(2).returning(matchingPriorWork());
        llm.respondWith("an answer");

        Answer answer = agent.searchPriorWork(A_REAL_IDEA, profile(), List.of());

        assertTrue(answer.getNote().toLowerCase().contains("broadened"),
                "a broadened search returns less specific evidence, so say so: "
                        + answer.getNote());
    }

    @Test
    void anIdeaThatMatchesNothingAtAnyWidthIsStillReportedHonestly() throws Exception {
        source.emptyUntilRelaxedTo(0).returning();

        Answer answer = agent.searchPriorWork(A_REAL_IDEA, profile(), List.of());

        assertFalse(answer.isGrounded());
        assertTrue(answer.getText().toLowerCase().contains("no matching evidence"),
                answer.getText());
    }
}
