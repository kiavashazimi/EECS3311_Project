package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.Comparability;
import ca.yorku.eecs3311.consultant.domain.ComparisonResult;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.domain.StrategyNode;
import ca.yorku.eecs3311.consultant.analysis.StrategyTaxonomy;
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

    // --- F13: the solution landscape (UC12) ---------------------------------

    private static PaperAnalysis tagged(String id, String... tags) {
        PaperAnalysis a = new PaperAnalysis(
                new PaperRecord(id, "Paper " + id, "arXiv", "2026-01-01"));
        a.setStrategyTags(List.of(tags));
        a.setMethod("method of " + id);
        return a;
    }

    private static StrategyNode landscape(String llmReply, PaperAnalysis... papers) {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith(llmReply));
        return new ResearchConsultantAgent(tools).buildLandscape(List.of(papers), myProject());
    }

    @Test
    void papersArePlacedUnderTheFamilyOfTheirStrategy() {
        StrategyNode root = landscape("{}",
                tagged("1", "diffusion model"),
                tagged("2", "data augmentation"),
                tagged("3", "resampling"));

        assertEquals(2, root.getChildren().size(), root.getChildren().toString());
        assertEquals(2, root.find("Data-level approaches").paperCount());
        assertEquals(1, root.find("Generative approaches").paperCount());
        assertEquals(3, root.paperCount(), "every paper must be somewhere");
    }

    @Test
    void aPaperMatchingNoFamilyGoesUnderUnclassified() {
        // UC12 5a: never forced into the nearest branch
        StrategyNode root = landscape("{}", tagged("1"), tagged("2"), tagged("3"));

        StrategyNode unclassified = root.find(StrategyTaxonomy.UNCLASSIFIED);
        assertNotNull(unclassified, "the node must exist: " + root.getChildren());
        assertEquals(3, unclassified.paperCount());
    }

    @Test
    void emptySeededFamiliesArePruned() {
        StrategyNode root = landscape("{}", tagged("1", "diffusion model"));

        assertEquals(1, root.getChildren().size(),
                "an empty family is noise on screen: " + root.getChildren());
    }

    @Test
    void theResearchersOwnBranchIsMarked() {
        ProjectProfile mine = myProject();
        mine.setModels(List.of("conditional diffusion model"));

        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("{}"));
        StrategyNode root = new ResearchConsultantAgent(tools)
                .buildLandscape(List.of(tagged("1", "diffusion model"),
                        tagged("2", "resampling"), tagged("3", "data augmentation")), mine);

        assertTrue(root.find("Generative approaches").isOwnApproach());
        assertFalse(root.find("Data-level approaches").isOwnApproach());
    }

    @Test
    void noBranchIsMarkedWhenTheProfileSaysNothingAboutMethod() {
        // UC12 7a: not guessed
        ProjectProfile vague = new ProjectProfile("can this be improved somehow?");
        vague.setDatasets(List.of("MIMIC-CXR"));
        vague.setMetrics(List.of("AUROC"));

        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("{}"));
        StrategyNode root = new ResearchConsultantAgent(tools)
                .buildLandscape(List.of(tagged("1", "diffusion model")), vague);

        assertTrue(root.flatten().stream().noneMatch(StrategyNode::isOwnApproach));
    }

    @Test
    void branchSummariesComeFromOneCallKeyedByFamilyName() {
        StrategyNode root = landscape(
                "{\"Generative approaches\":\"they synthesise new examples\","
                        + "\"Data-level approaches\":\"they change what is trained on\"}",
                tagged("1", "diffusion model"), tagged("2", "resampling"),
                tagged("3", "data augmentation"));

        assertEquals("they synthesise new examples",
                root.find("Generative approaches").getMainIdea());
        assertFalse(root.find("Data-level approaches").isMainIdeaMissing());
    }

    @Test
    void tooFewPapersLeavesTheSummariesUnwrittenRatherThanInvented() {
        // UC12 3a: sub-branches from two papers would be noise as structure
        FakeLLMClient llm = new FakeLLMClient().respondWith("{\"x\":\"y\"}");
        ToolManager tools = new ToolManager(llm);
        StrategyNode root = new ResearchConsultantAgent(tools)
                .buildLandscape(List.of(tagged("1", "diffusion model")), myProject());

        assertEquals(1, root.paperCount());
        assertEquals(0, llm.callCount(), "the model must not be called for one paper");
        assertTrue(root.find("Generative approaches").isMainIdeaMissing());
    }

    @Test
    void whenTheModelIsUnavailableTheTreeStillStands() {
        // UC12 6a
        ToolManager tools = new ToolManager(new FakeLLMClient().failWith("no key"));
        StrategyNode root = new ResearchConsultantAgent(tools)
                .buildLandscape(List.of(tagged("1", "diffusion model"),
                        tagged("2", "resampling"), tagged("3", "data augmentation")),
                        myProject());

        assertEquals(3, root.paperCount(), "the structure is deterministic");
        assertTrue(root.find("Generative approaches").isMainIdeaMissing(),
                "the UI must be able to say the summary is unavailable");
    }

    @Test
    void anEmptyLandscapeIsNotAnError() {
        StrategyNode root = landscape("{}");
        assertEquals(0, root.paperCount());
        assertTrue(root.getChildren().isEmpty(), "everything was pruned");
    }

    @Test
    void summarizeBranchWorksOnALeafAndOnABranchAlike() {
        StrategyNode root = landscape(
                "{\"Generative approaches\":\"they synthesise new examples\"}",
                tagged("1", "diffusion model"), tagged("2", "diffusion model"),
                tagged("3", "diffusion model"));
        ResearchConsultantAgent agent =
                new ResearchConsultantAgent(new ToolManager(new FakeLLMClient()));

        String branch = agent.summarizeBranch(root);
        String leaf = agent.summarizeBranch(root.find("Generative approaches"));

        assertTrue(branch.contains("3 paper(s)"), branch);
        assertTrue(leaf.contains("3 paper(s)"), leaf);
        assertTrue(leaf.contains("they synthesise new examples"), leaf);
    }

    @Test
    void summarizeBranchMarksTheResearchersOwn() {
        ProjectProfile mine = myProject();
        mine.setModels(List.of("diffusion model"));
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("{}"));
        ResearchConsultantAgent agent = new ResearchConsultantAgent(tools);
        StrategyNode root = agent.buildLandscape(List.of(tagged("1", "diffusion model")), mine);

        assertTrue(agent.summarizeBranch(root.find("Generative approaches"))
                .contains("your approach"));
    }

    @Test
    void buildLandscapeRejectsNullArguments() {
        ResearchConsultantAgent agent =
                new ResearchConsultantAgent(new ToolManager(new FakeLLMClient()));
        assertThrows(IllegalArgumentException.class,
                () -> agent.buildLandscape(null, myProject()));
        assertThrows(IllegalArgumentException.class, () -> agent.buildLandscape(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> agent.summarizeBranch(null));
    }

    // --- F13: sub-branches proposed beneath the fixed families ---------------

    /** Scripts the family-summary call first, then each subdivision call. */
    private static StrategyNode landscapeScripted(String[] replies, PaperAnalysis... papers) {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondInOrder(replies));
        return new ResearchConsultantAgent(tools).buildLandscape(List.of(papers), myProject());
    }

    private static final String NO_SUMMARIES = "{}";

    @Test
    void aFamilyWithEnoughPapersIsSplitIntoSubBranches() {
        StrategyNode root = landscapeScripted(new String[] {NO_SUMMARIES, """
                {"groups":[
                  {"name":"Balanced oversampling","idea":"rebalance the classes","papers":[1,2]},
                  {"name":"Augmentation","idea":"transform what exists","papers":[3]}]}
                """},
                tagged("1", "resampling"), tagged("2", "resampling"),
                tagged("3", "data augmentation"));

        StrategyNode family = root.find("Data-level approaches");
        assertEquals(2, family.getChildren().size(), family.getChildren().toString());
        assertEquals(2, family.find("Balanced oversampling").paperCount());
        assertEquals(1, family.find("Augmentation").paperCount());
        assertEquals("rebalance the classes", family.find("Balanced oversampling").getMainIdea());
        assertEquals(3, root.paperCount(), "subdividing must not lose or duplicate a paper");
    }

    @Test
    void aPaperTheModelLeavesOutStaysOnItsFamilyRatherThanVanishing() {
        StrategyNode root = landscapeScripted(new String[] {NO_SUMMARIES, """
                {"groups":[
                  {"name":"One","idea":"x","papers":[1]},
                  {"name":"Two","idea":"y","papers":[2]}]}
                """},
                tagged("1", "resampling"), tagged("2", "resampling"),
                tagged("3", "resampling"));

        StrategyNode family = root.find("Data-level approaches");
        assertEquals(1, family.getPapers().size(),
                "the unplaced paper must stay on the family, not disappear");
        assertEquals(3, family.paperCount());
        assertEquals(3, root.paperCount());
    }

    @Test
    void aPaperNumberTheModelInventedIsIgnored() {
        StrategyNode root = landscapeScripted(new String[] {NO_SUMMARIES, """
                {"groups":[
                  {"name":"One","idea":"x","papers":[1,99]},
                  {"name":"Two","idea":"y","papers":[2,-4]}]}
                """},
                tagged("1", "resampling"), tagged("2", "resampling"),
                tagged("3", "resampling"));

        assertEquals(3, root.paperCount(), "an out-of-range number must not add a paper");
        StrategyNode family = root.find("Data-level approaches");
        assertEquals(1, family.find("One").paperCount());
        assertEquals(1, family.find("Two").paperCount());
    }

    @Test
    void aPaperIsNeverPlacedInTwoSubBranchesAtOnce() {
        StrategyNode root = landscapeScripted(new String[] {NO_SUMMARIES, """
                {"groups":[
                  {"name":"One","idea":"x","papers":[1,2]},
                  {"name":"Two","idea":"y","papers":[2,3]}]}
                """},
                tagged("1", "resampling"), tagged("2", "resampling"),
                tagged("3", "resampling"));

        assertEquals(3, root.paperCount(), "a paper claimed twice must still be counted once");
    }

    @Test
    void oneProposedGroupIsNotASubdivision() {
        // every paper in a single bucket says nothing the family did not
        StrategyNode root = landscapeScripted(new String[] {NO_SUMMARIES, """
                {"groups":[{"name":"Everything","idea":"x","papers":[1,2,3]}]}
                """},
                tagged("1", "resampling"), tagged("2", "resampling"),
                tagged("3", "resampling"));

        StrategyNode family = root.find("Data-level approaches");
        assertTrue(family.isLeaf(), "a single group must leave the family flat: "
                + family.getChildren());
        assertEquals(3, family.getPapers().size());
    }

    @Test
    void aFamilyWithTooFewPapersIsLeftFlat() {
        StrategyNode root = landscapeScripted(new String[] {NO_SUMMARIES, """
                {"groups":[
                  {"name":"One","idea":"x","papers":[1]},
                  {"name":"Two","idea":"y","papers":[2]}]}
                """},
                tagged("1", "resampling"), tagged("2", "resampling"),
                tagged("3", "diffusion model"));

        assertTrue(root.find("Data-level approaches").isLeaf(),
                "two papers cannot support sub-branches without inventing structure");
    }

    @Test
    void anUnreadableSubdivisionLeavesTheFamilyFlatRatherThanBroken() {
        StrategyNode root = landscapeScripted(
                new String[] {NO_SUMMARIES, "I could not do that."},
                tagged("1", "resampling"), tagged("2", "resampling"),
                tagged("3", "resampling"));

        assertTrue(root.find("Data-level approaches").isLeaf());
        assertEquals(3, root.paperCount());
    }

    @Test
    void anUnavailableModelStillLeavesEveryPaperInAFamily() {
        ToolManager tools = new ToolManager(new FakeLLMClient().failWith("no key"));
        StrategyNode root = new ResearchConsultantAgent(tools).buildLandscape(
                List.of(tagged("1", "resampling"), tagged("2", "resampling"),
                        tagged("3", "resampling")),
                myProject());

        assertEquals(3, root.find("Data-level approaches").paperCount());
        assertTrue(root.find("Data-level approaches").isLeaf());
    }

    // --- placement when a paper carries tags from several families -----------

    @Test
    void aPaperGoesToTheFamilyMostOfItsTagsPointAt() {
        // "From Fake to Real" is a pretraining paper that also generates data;
        // first-tag-wins filed it under Data-level because the taxonomy happens
        // to declare that family first
        StrategyNode root = landscape("{}",
                tagged("1", "synthetic data generation", "transfer learning", "fine-tuning"));

        assertEquals(1, root.find("Training-level approaches").paperCount(),
                "two training tags outweigh one data tag: " + root.getChildren());
    }

    @Test
    void anEvenSplitIsBrokenInTaxonomyOrderSoTheTreeIsReproducible() {
        StrategyNode first = landscape("{}",
                tagged("1", "data augmentation", "synthetic data generation",
                        "generative model", "conditional generation"));
        StrategyNode again = landscape("{}",
                tagged("1", "data augmentation", "synthetic data generation",
                        "generative model", "conditional generation"));

        assertEquals(1, first.find("Data-level approaches").paperCount(),
                "a tie goes to the family declared first: " + first.getChildren());
        assertEquals(first.getChildren().toString(), again.getChildren().toString(),
                "the same papers must give the same tree twice");
    }

    // --- when the built-in families are the wrong frame ----------------------

    private static final String FIVE_GROUPS = """
            {"groups":[
              {"name":"Classroom comparisons","idea":"map teaching vs conventional","papers":[1,2]},
              {"name":"Learner-built maps","idea":"students construct the map","papers":[3,4]},
              {"name":"Map design","idea":"how map features change learning","papers":[5,6]}]}
            """;

    private static PaperAnalysis untagged(String id) {
        PaperAnalysis a = new PaperAnalysis(
                new PaperRecord(id, "Paper " + id, "Instructional Science", "2026-01-01"));
        a.setMethod("method of " + id);
        return a;
    }

    @Test
    void whenAlmostNothingFitsTheBuiltInFamiliesTheyAreNotTheTopLevel() {
        // one ML paper among six: the ML families are the wrong frame for this
        // project, and showing Data-level as a peer of the real groups implies
        // the field is organised that way
        StrategyNode root = landscapeScripted(new String[] {FIVE_GROUPS},
                tagged("1", "resampling"),
                untagged("2"), untagged("3"), untagged("4"),
                untagged("5"), untagged("6"));

        assertEquals(null, root.find("Data-level approaches"),
                "a family explaining one paper in six must not head the tree: "
                        + root.getChildren());
        assertEquals(null, root.find(StrategyTaxonomy.UNCLASSIFIED));
        assertNotNull(root.find("Classroom comparisons"), root.getChildren().toString());
        assertNotNull(root.find("Map design"), root.getChildren().toString());
        assertEquals(6, root.paperCount(), "regrouping must not lose a paper");
    }

    @Test
    void whenMostPapersFitTheBuiltInFamiliesAreKept() {
        StrategyNode root = landscapeScripted(new String[] {NO_SUMMARIES, NO_SUMMARIES},
                tagged("1", "resampling"), tagged("2", "resampling"),
                tagged("3", "data augmentation"), untagged("4"));

        assertNotNull(root.find("Data-level approaches"),
                "three of four papers fit, so the taxonomy is the right frame");
        assertEquals(3, root.find("Data-level approaches").paperCount());
    }

    @Test
    void aPaperWhoseTagsTheTaxonomyDoesNotStandBehindIsNotFiledUnderThem() {
        // measured live: an education paper on "curriculum" was tagged
        // "curriculum learning" and filed under Training-level approaches.
        // classify() had already marked the tags uncertain; placement ignored it
        PaperAnalysis guessed = tagged("1", "curriculum learning");
        guessed.setTagsUncertain(true);

        StrategyNode root = landscapeScripted(new String[] {NO_SUMMARIES, NO_SUMMARIES},
                guessed, tagged("2", "resampling"), tagged("3", "resampling"),
                tagged("4", "resampling"));

        assertEquals(1, root.find(StrategyTaxonomy.UNCLASSIFIED).paperCount(),
                "an uncertain tag is not evidence of a family: " + root.getChildren());
        assertEquals(3, root.find("Data-level approaches").paperCount());
    }

    @Test
    void theUnclassifiedNodeIsRenamedOnceItHasStructureOfItsOwn() {
        StrategyNode root = landscapeScripted(new String[] {NO_SUMMARIES, """
                {"groups":[
                  {"name":"One","idea":"x","papers":[1]},
                  {"name":"Two","idea":"y","papers":[2,3]}]}
                """},
                tagged("1", "resampling"), tagged("2", "resampling"),
                tagged("3", "resampling"), tagged("4", "data augmentation"),
                untagged("5"), untagged("6"), untagged("7"));

        assertEquals(null, root.find(StrategyTaxonomy.UNCLASSIFIED),
                "a node with named sub-branches is not unclassified any more: "
                        + root.getChildren());
        assertEquals(3, root.find("Outside the built-in families").paperCount());
    }

    @Test
    void anUnclassifiedNodeThatStaysFlatKeepsItsName() {
        // UC12 5a: still an explicit Unclassified node, not a forced placement
        StrategyNode root = landscapeScripted(new String[] {NO_SUMMARIES, "{\"groups\":[]}"},
                tagged("1", "resampling"), tagged("2", "resampling"),
                tagged("3", "resampling"), untagged("4"), untagged("5"), untagged("6"));

        assertNotNull(root.find(StrategyTaxonomy.UNCLASSIFIED), root.getChildren().toString());
    }
}
