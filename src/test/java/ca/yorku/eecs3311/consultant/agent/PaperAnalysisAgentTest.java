package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.analysis.StrategyTaxonomy;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F04 extraction and F05 classification, as SD04 draws them. */
class PaperAnalysisAgentTest {

    private static PaperRecord paper() {
        PaperRecord p = new PaperRecord("2304.09218", "Generative models improve fairness",
                "arXiv cs.CV", "2023-04-18");
        p.setSummary("We add synthetic chest X-rays from a conditional diffusion model to "
                + "the training set of a DenseNet classifier and measure subgroup AUROC.");
        return p;
    }

    private static final String GOOD_REPLY = """
            {"problem":"classifiers underperform on some demographic subgroups",
             "method":"a conditional diffusion model generates synthetic chest X-rays which are added to training",
             "dataset":"MIMIC-CXR",
             "model":"DenseNet-121",
             "metrics":["AUROC","subgroup AUROC gap"],
             "results":"the subgroup gap narrows",
             "limitations":"one modality only",
             "strategy_tags":["diffusion","synthetic data"]}
            """;

    private static PaperAnalysis analyse(String llmReply) throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith(llmReply));
        return (PaperAnalysis) new PaperAnalysisAgent(tools).run(paper());
    }

    // --- F04: extraction -----------------------------------------------------

    @Test
    void extractsEveryStructuredField() throws Exception {
        PaperAnalysis a = analyse(GOOD_REPLY);

        assertEquals("MIMIC-CXR", a.getDataset());
        assertEquals("DenseNet-121", a.getModel());
        assertEquals(List.of("AUROC", "subgroup AUROC gap"), a.getMetrics());
        assertTrue(a.getProblem().contains("subgroups"));
        assertTrue(a.getLimitations().contains("one modality"));
        assertTrue(a.isComplete(), "nothing should be unknown: " + a.unknownFields());
    }

    @Test
    void keepsTheRecordLinkedToItsPaper() throws Exception {
        assertEquals("2304.09218", analyse(GOOD_REPLY).getPaper().getId());
    }

    @Test
    void aFieldTheModelOmitsStaysUnknownRatherThanInvented() throws Exception {
        // UC04 4a. A fabricated dataset name would make the F07 comparability
        // check silently wrong, which is worse than admitting ignorance.
        PaperAnalysis a = analyse("""
                {"problem":"stated","method":"stated","dataset":"unknown","model":"",
                 "metrics":[],"results":"unknown","limitations":"unknown",
                 "strategy_tags":["diffusion"]}
                """);

        assertTrue(PaperAnalysis.isUnknown(a.getDataset()));
        assertTrue(PaperAnalysis.isUnknown(a.getModel()), "an empty string is not a value");
        assertFalse(a.isComplete());
        assertTrue(a.unknownFields().contains("dataset"), a.unknownFields().toString());
    }

    @Test
    void aPartialExtractionIsStillAnAnalysis() throws Exception {
        PaperAnalysis a = analyse("{\"method\":\"a diffusion model\"}");
        assertFalse(a.getMethod().isBlank());
        assertTrue(a.unknownFields().contains("dataset"));
    }

    @Test
    void proseAroundTheJsonIsTolerated() throws Exception {
        PaperAnalysis a = analyse("Here is the analysis:\n" + GOOD_REPLY + "\nHope that helps.");
        assertEquals("MIMIC-CXR", a.getDataset());
    }

    @Test
    void anUnparseableReplyLeavesEverythingUnknownRatherThanHalfFilled() throws Exception {
        PaperAnalysis a = analyse("{not json at all");
        assertTrue(a.unknownFields().contains("dataset"));
        assertTrue(a.unknownFields().contains("method"));
    }

    @Test
    void whenTheModelIsUnavailableTheRecordIsStillCreated() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().failWith("no key"));
        PaperAnalysis a = (PaperAnalysis) new PaperAnalysisAgent(tools).run(paper());

        assertEquals("2304.09218", a.getPaper().getId());
        assertTrue(a.unknownFields().contains("method"),
                "an unavailable model means unknown fields, not a missing record");
    }

    @Test
    void aPaperWithNoAbstractIsFlaggedNotAnalysed() {
        // UC04 3a
        PaperRecord empty = new PaperRecord("1", "Title Only", "arXiv", "2026-01-01");
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith(GOOD_REPLY));

        AgentException e = assertThrows(AgentException.class,
                () -> new PaperAnalysisAgent(tools).run(empty));
        assertTrue(e.getMessage().contains("Title Only"), e.getMessage());
    }

    @Test
    void refusesInputThatIsNotAPaper() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        assertThrows(AgentException.class, () -> new PaperAnalysisAgent(tools).run("a string"));
    }

    @Test
    void thePlanNamesItsThreeSteps() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith(GOOD_REPLY));
        PaperAnalysisAgent agent = new PaperAnalysisAgent(tools);
        agent.run(paper());

        assertEquals(3, agent.getLastPlan().getSteps().size(),
                agent.getLastPlan().getSteps().toString());
    }

    // --- F05: classification -------------------------------------------------

    @Test
    void tagsComeFromTheMethodTextAndAreCanonical() throws Exception {
        PaperAnalysis a = analyse(GOOD_REPLY);

        assertTrue(a.getStrategyTags().contains("diffusion model"),
                "\"diffusion\" must resolve to the canonical tag: " + a.getStrategyTags());
        assertTrue(a.getStrategyTags().contains("synthetic data generation"),
                a.getStrategyTags().toString());
        assertFalse(a.isTagsUncertain(), "the taxonomy recognised these");
    }

    @Test
    void aTagTheTaxonomyDoesNotKnowIsDiscardedNotAsserted() throws Exception {
        PaperAnalysis a = analyse("""
                {"problem":"p","method":"we invented a wholly new procedure","dataset":"d",
                 "model":"m","metrics":["x"],"results":"r","limitations":"l",
                 "strategy_tags":["quantum fairness alignment"]}
                """);

        assertFalse(a.getStrategyTags().contains("quantum fairness alignment"),
                "an unrecognised tag must not be presented as a classification");
        assertTrue(a.isTagsUncertain(), "UC04 5a: marked uncertain rather than asserted");
    }

    @Test
    void noRecognisableStrategyMeansUncertain() throws Exception {
        PaperAnalysis a = analyse("""
                {"problem":"p","method":"something nobody has named","dataset":"d","model":"m",
                 "metrics":["x"],"results":"r","limitations":"l","strategy_tags":[]}
                """);

        assertTrue(a.getStrategyTags().isEmpty());
        assertTrue(a.isTagsUncertain());
    }

    @Test
    void theMethodTextAloneCanClassifyWithoutTheModelSuggestingTags() throws Exception {
        PaperAnalysis a = analyse("""
                {"problem":"p","method":"a GAN with adversarial training and resampling",
                 "dataset":"d","model":"m","metrics":["x"],"results":"r","limitations":"l",
                 "strategy_tags":[]}
                """);

        assertTrue(a.getStrategyTags().contains("generative adversarial network"),
                a.getStrategyTags().toString());
        assertTrue(a.getStrategyTags().contains("adversarial training"));
        assertFalse(a.isTagsUncertain(), "the taxonomy found these on its own");
    }

    @Test
    void reclassifyingAnExistingAnalysisDoesNotNeedTheModel() {
        PaperAnalysis a = new PaperAnalysis(paper());
        a.setMethod("a latent diffusion model and knowledge distillation");

        PaperAnalysisAgent agent = new PaperAnalysisAgent(new ToolManager(new FakeLLMClient()));
        List<String> tags = agent.classifyExisting(a);

        assertTrue(tags.contains("diffusion model"), tags.toString());
        assertTrue(tags.contains("knowledge distillation"), tags.toString());
    }

    @Test
    void aTaughtTaxonomyChangesTheClassification() throws Exception {
        StrategyTaxonomy taxonomy = new StrategyTaxonomy();
        taxonomy.addKnownStrategy("subgroup rebalancing", "subgroup balancing");

        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("""
                {"problem":"p","method":"we apply subgroup balancing","dataset":"d","model":"m",
                 "metrics":["x"],"results":"r","limitations":"l","strategy_tags":[]}
                """));
        PaperAnalysis a = (PaperAnalysis)
                new PaperAnalysisAgent(tools, taxonomy).run(paper());

        assertTrue(a.getStrategyTags().contains("subgroup rebalancing"),
                a.getStrategyTags().toString());
        assertFalse(a.isTagsUncertain());
    }

    @Test
    void rejectsANullTaxonomy() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaperAnalysisAgent(new ToolManager(new FakeLLMClient()), null));
    }

    @Test
    void rejectsReclassifyingNothing() {
        PaperAnalysisAgent agent = new PaperAnalysisAgent(new ToolManager(new FakeLLMClient()));
        assertThrows(IllegalArgumentException.class, () -> agent.classifyExisting(null));
    }

    @Test
    void theUnknownSentinelIsNeverStoredInsideAList() {
        // a metrics list holding "unknown" is not a metric called "unknown";
        // storing it that way made F07 report "different measures" instead of
        // "cannot determine"
        PaperAnalysis a = assertDoesNotThrowAnalysis("""
                {"problem":"p","method":"a diffusion model","dataset":"MIMIC-CXR","model":"m",
                 "metrics":["unknown"],"results":"r","limitations":"l","strategy_tags":[]}
                """);

        assertTrue(a.getMetrics().isEmpty(),
                "an unstated metric is an empty list, not a list containing a sentinel");
    }

    private static PaperAnalysis assertDoesNotThrowAnalysis(String reply) {
        try {
            return analyse(reply);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
