package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.Comparability;
import ca.yorku.eecs3311.consultant.domain.ComparisonResult;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F06 and F07 together, in the order UC05's include of UC06 requires. */
class ResearchConsultantAgentTest {

    private static ProjectProfile myProject() {
        ProjectProfile p = new ProjectProfile("do synthetic X-rays improve fairness?");
        p.setDatasets(List.of("MIMIC-CXR"));
        p.setMetrics(List.of("AUROC"));
        p.setOwnResults(List.of("AUROC 0.84"));
        return p;
    }

    private static PaperAnalysis paper(String dataset, List<String> metrics) {
        PaperAnalysis a = new PaperAnalysis(
                new PaperRecord("1", "A relevant paper", "arXiv", "2026-01-01"));
        a.setProblem("subgroup underperformance");
        a.setMethod("a conditional diffusion model");
        a.setDataset(dataset);
        a.setModel("DenseNet-121");
        a.setMetrics(metrics);
        a.setResults("AUROC 0.86");
        a.setLimitations("one modality");
        return a;
    }

    private static final String NARRATIVE_REPLY = """
            {"similarities":["both add synthetic data to training",
                             "both measure a subgroup gap"],
             "differences":["they condition on labels, you condition on demographics"],
             "narrative":"This is the closest prior work. Your novelty is the conditioning variable."}
            """;

    private static ComparisonResult compare(PaperAnalysis a, String llmReply) throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith(llmReply));
        return new ResearchConsultantAgent(tools).compare(a, myProject());
    }

    // --- the deterministic verdict comes first ------------------------------

    @Test
    void theVerdictAndMismatchesAreSetBeforeTheModelIsCalled() throws Exception {
        ComparisonResult r = compare(paper("MIMIC-CXR", List.of("AUROC")), NARRATIVE_REPLY);

        assertEquals(Comparability.COMPARABLE, r.getComparability());
        assertTrue(r.getMismatches().isEmpty());
        assertFalse(r.getComparabilityNote().isBlank());
    }

    @Test
    void aDifferentDatasetIsNotComparableEvenWithAGlowingNarrative() throws Exception {
        // the dangerous case: a fluent summary must not override the data
        ComparisonResult r = compare(paper("ImageNet", List.of("AUROC")), NARRATIVE_REPLY);

        assertEquals(Comparability.NOT_COMPARABLE, r.getComparability(),
                "the model's prose must not change the verdict");
        assertFalse(r.getMismatches().isEmpty());
    }

    @Test
    void anUnknownDatasetGivesCannotDetermine() throws Exception {
        ComparisonResult r =
                compare(paper(PaperAnalysis.UNKNOWN, List.of("AUROC")), NARRATIVE_REPLY);
        assertEquals(Comparability.CANNOT_DETERMINE, r.getComparability());
    }

    @Test
    void thePromptCarriesTheAlreadyDecidedVerdictSoTheModelCannotContradictIt() throws Exception {
        FakeLLMClient llm = new FakeLLMClient().respondWith(NARRATIVE_REPLY);
        ToolManager tools = new ToolManager(llm);
        new ResearchConsultantAgent(tools).compare(paper("ImageNet", List.of("AUROC")),
                myProject());

        String prompt = llm.promptsSeen().get(0);
        assertTrue(prompt.contains(Comparability.NOT_COMPARABLE.label()), prompt);
        assertTrue(prompt.contains("Do not contradict"), "the instruction must be explicit");
    }

    // --- the narrative -------------------------------------------------------

    @Test
    void readsSimilaritiesDifferencesAndNarrative() throws Exception {
        ComparisonResult r = compare(paper("MIMIC-CXR", List.of("AUROC")), NARRATIVE_REPLY);

        assertEquals(2, r.getSimilarities().size());
        assertEquals(1, r.getDifferences().size());
        assertTrue(r.getNarrative().contains("closest prior work"));
        assertFalse(r.isNarrativeMissing());
    }

    @Test
    void proseAroundTheJsonIsTolerated() throws Exception {
        ComparisonResult r = compare(paper("MIMIC-CXR", List.of("AUROC")),
                "Sure, here you go:\n" + NARRATIVE_REPLY + "\nLet me know.");
        assertFalse(r.getNarrative().isBlank());
    }

    @Test
    void blankEntriesAreDroppedRatherThanShownAsEmptyBullets() throws Exception {
        ComparisonResult r = compare(paper("MIMIC-CXR", List.of("AUROC")),
                "{\"similarities\":[\"real one\",\"\",\"  \"],\"differences\":[],"
                        + "\"narrative\":\"x\"}");
        assertEquals(List.of("real one"), r.getSimilarities());
    }

    // --- the degraded path (UC06 3a) ----------------------------------------

    @Test
    void whenTheModelIsUnavailableTheVerdictAndMismatchesStillStand() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().failWith("no key"));
        ComparisonResult r = new ResearchConsultantAgent(tools)
                .compare(paper("ImageNet", List.of("Dice")), myProject());

        assertEquals(Comparability.NOT_COMPARABLE, r.getComparability(),
                "F07 is deterministic and does not need the model at all");
        assertEquals(2, r.getMismatches().size());
        assertTrue(r.isNarrativeMissing(), "the UI must be able to say the prose is missing");
    }

    @Test
    void anUnparseableReplyCostsOnlyTheNarrative() throws Exception {
        ComparisonResult r = compare(paper("MIMIC-CXR", List.of("AUROC")), "I cannot do that");

        assertEquals(Comparability.COMPARABLE, r.getComparability());
        assertTrue(r.isNarrativeMissing());
    }

    // --- plumbing ------------------------------------------------------------

    @Test
    void thePlanRecordsItsThreeSteps() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith(NARRATIVE_REPLY));
        ResearchConsultantAgent agent = new ResearchConsultantAgent(tools);
        agent.compare(paper("MIMIC-CXR", List.of("AUROC")), myProject());

        assertEquals(3, agent.getLastPlan().getSteps().size());
    }

    @Test
    void theResultIsLabelledWithThePaperTitle() throws Exception {
        assertEquals("A relevant paper",
                compare(paper("MIMIC-CXR", List.of("AUROC")), NARRATIVE_REPLY).getPaperTitle());
    }

    @Test
    void refusesInputThatIsNotAComparison() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        assertThrows(AgentException.class, () -> new ResearchConsultantAgent(tools).run("nope"));
    }

    @Test
    void rejectsNullArguments() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        assertThrows(IllegalArgumentException.class,
                () -> new ResearchConsultantAgent(tools, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ResearchConsultantAgent(tools).compare(null, myProject()));
    }
}
