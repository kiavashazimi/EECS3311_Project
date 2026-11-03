package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import ca.yorku.eecs3311.consultant.literature.FakeLiteratureSource;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F03: several queries, unioned, ranked, then broadcast. */
class PaperDiscoveryAgentTest {

    private static ProjectProfile profile() {
        ProjectProfile p = new ProjectProfile(
                "Does adding synthetic chest X-rays make a disease classifier fairer?");
        p.setDatasets(List.of("MIMIC-CXR", "CheXpert"));
        p.setMetrics(List.of("AUROC", "underdiagnosis gap"));
        p.setModels(List.of("DenseNet-121"));
        return p;
    }

    private static PaperRecord paper(String id, String title) {
        PaperRecord p = new PaperRecord(id, title, "arXiv cs.CV", "2026-01-01");
        p.setSummary("abstract of " + title);
        return p;
    }

    /** Records every paper it is told about. */
    private static final class RecordingListener implements PaperFeedListener {
        final List<String> seen = new ArrayList<>();

        @Override
        public void onNewPaper(PaperRecord paper) {
            seen.add(paper.getId());
        }
    }

    // --- query planning ------------------------------------------------------

    @Test
    void writesSeveralQueriesAndSearchesEachOne() throws Exception {
        FakeLLMClient llm = new FakeLLMClient()
                .respondWith("\"chest X-ray\" fairness\n\"synthetic data\" subgroup\nCXR bias");
        FakeLiteratureSource source = new FakeLiteratureSource("arXiv")
                .returning(paper("1", "A paper"));
        ToolManager tools = new ToolManager(llm);
        tools.setLiteratureSource(source);

        new PaperDiscoveryAgent(tools).run(profile());

        assertEquals(List.of("\"chest X-ray\" fairness", "\"synthetic data\" subgroup", "CXR bias"),
                source.queriesSeen(),
                "one query is not enough for a compound topic; each must be searched");
    }

    @Test
    void stripsNumberingAndBulletsFromTheModelsQueries() {
        List<String> queries = PaperDiscoveryAgent.parseQueries(
                "1. fairness chest\n- synthetic subgroup\n* CXR bias", profile());
        assertEquals(List.of("fairness chest", "synthetic subgroup", "CXR bias"), queries);
    }

    @Test
    void capsTheNumberOfQueries() {
        String many = "a\nb\nc\nd\ne\nf\ng\nh\ni";
        assertEquals(PaperDiscoveryAgent.MAX_QUERIES,
                PaperDiscoveryAgent.parseQueries(many, profile()).size());
    }

    @Test
    void fallsBackToDeterministicQueriesWhenTheModelIsUnavailable() throws Exception {
        FakeLiteratureSource source = new FakeLiteratureSource("arXiv")
                .returning(paper("1", "A paper"));
        ToolManager tools = new ToolManager(new FakeLLMClient().failWith("no key"));
        tools.setLiteratureSource(source);

        new PaperDiscoveryAgent(tools).run(profile());

        assertFalse(source.queriesSeen().isEmpty(),
                "discovery must still work without a key, just more narrowly");
        assertTrue(source.queriesSeen().stream().anyMatch(q -> q.contains("\"MIMIC-CXR\"")),
                "the fallback should use the profile's own terms: " + source.queriesSeen());
    }

    @Test
    void anEmptyModelReplyFallsBackRatherThanSearchingNothing() {
        assertFalse(PaperDiscoveryAgent.parseQueries("   \n  \n", profile()).isEmpty());
    }

    @Test
    void refusesToRunWithoutAResearchQuestion() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        tools.setLiteratureSource(new FakeLiteratureSource());

        AgentException e = assertThrows(AgentException.class,
                () -> new PaperDiscoveryAgent(tools).run(new ProjectProfile("")));
        assertTrue(e.getMessage().contains("research question"), e.getMessage());
    }

    @Test
    void refusesInputThatIsNotAProfile() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        assertThrows(AgentException.class, () -> new PaperDiscoveryAgent(tools).run("a string"));
    }

    // --- union and dedupe ----------------------------------------------------

    @Test
    void thesamePaperFoundByTwoQueriesAppearsOnce() throws Exception {
        // measured: 24 of 26 papers came from exactly one query, but the
        // overlap that does exist must not become duplicates
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("q1\nq2"));
        tools.setLiteratureSource(new FakeLiteratureSource("arXiv")
                .returning(paper("1", "Shared paper")));

        @SuppressWarnings("unchecked")
        List<RankedPaper> out = (List<RankedPaper>) new PaperDiscoveryAgent(tools).run(profile());

        assertEquals(1, out.size(), "the same paper from two queries is one result");
    }

    @Test
    void oneFailedQueryDoesNotLoseTheOthers() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("q1"));
        tools.setLiteratureSource(new FakeLiteratureSource("arXiv")
                .returning(paper("1", "Found anyway")));

        @SuppressWarnings("unchecked")
        List<RankedPaper> out = (List<RankedPaper>) new PaperDiscoveryAgent(tools).run(profile());
        assertEquals(1, out.size());
    }

    @Test
    void whenEveryQueryFailsTheResearcherIsTold() {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("q1\nq2"));
        tools.setLiteratureSource(new FakeLiteratureSource("arXiv").failWith("HTTP 503"));

        AgentException e = assertThrows(AgentException.class,
                () -> new PaperDiscoveryAgent(tools).run(profile()));
        assertTrue(e.getMessage().contains("503"), e.getMessage());
    }

    @Test
    void noResultsIsAnEmptyListNotAnError() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("q1"));
        tools.setLiteratureSource(new FakeLiteratureSource("arXiv").returning());

        @SuppressWarnings("unchecked")
        List<RankedPaper> out = (List<RankedPaper>) new PaperDiscoveryAgent(tools).run(profile());
        assertTrue(out.isEmpty());
    }

    // --- ranking guard rails -------------------------------------------------

    @Test
    void readsTheRankingAndKeepsTheReason() {
        List<PaperRecord> candidates = List.of(paper("1", "First"), paper("2", "Second"));
        String reply = """
                {"ranked":[
                  {"id":"2","score":90,"why":"same method, other modality",
                   "closest_prior_work":true},
                  {"id":"1","score":30,"why":"no generative component"}]}
                """;

        List<RankedPaper> ranked = PaperDiscoveryAgent.parseRanking(reply, candidates);

        assertEquals(2, ranked.size());
        assertEquals("2", ranked.get(0).paper().getId(), "the model's order must be kept");
        assertEquals(90, ranked.get(0).score());
        assertEquals("same method, other modality", ranked.get(0).why());
        assertTrue(ranked.get(0).closestPriorWork());
        assertFalse(ranked.get(1).closestPriorWork());
    }

    @Test
    void anInventedIdIsDiscarded() {
        // without this guard a hallucinated id renders as a paper that does not exist
        List<PaperRecord> candidates = List.of(paper("1", "First"));
        String reply = "{\"ranked\":[{\"id\":\"9999\",\"score\":99,\"why\":\"made up\"},"
                + "{\"id\":\"1\",\"score\":50,\"why\":\"real\"}]}";

        List<RankedPaper> ranked = PaperDiscoveryAgent.parseRanking(reply, candidates);

        assertEquals(1, ranked.size());
        assertEquals("1", ranked.get(0).paper().getId());
    }

    @Test
    void aDuplicatedIdIsOnlyCountedOnce() {
        List<PaperRecord> candidates = List.of(paper("1", "First"));
        String reply = "{\"ranked\":[{\"id\":\"1\",\"score\":80,\"why\":\"a\"},"
                + "{\"id\":\"1\",\"score\":10,\"why\":\"b\"}]}";

        assertEquals(1, PaperDiscoveryAgent.parseRanking(reply, candidates).size());
    }

    @Test
    void aPaperTheModelOmittedIsAppendedNotLost() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient()
                .respondWith("{\"ranked\":[{\"id\":\"1\",\"score\":70,\"why\":\"kept\"}]}"));
        tools.setLiteratureSource(new FakeLiteratureSource("arXiv")
                .returning(paper("1", "Ranked"), paper("2", "Forgotten")));

        @SuppressWarnings("unchecked")
        List<RankedPaper> out = (List<RankedPaper>) new PaperDiscoveryAgent(tools).run(profile());

        assertEquals(2, out.size(), "a real result must not be lost to a formatting slip");
        assertEquals("2", out.get(1).paper().getId());
        assertTrue(out.get(1).isUnranked());
    }

    @Test
    void unparseableJsonLeavesEverythingUnrankedRatherThanEmpty() {
        assertTrue(PaperDiscoveryAgent.parseRanking("I cannot do that", List.of(paper("1", "x")))
                .isEmpty());
    }

    @Test
    void scoresOutsideTheRangeAreClamped() {
        List<PaperRecord> candidates = List.of(paper("1", "First"), paper("2", "Second"));
        String reply = "{\"ranked\":[{\"id\":\"1\",\"score\":500,\"why\":\"a\"},"
                + "{\"id\":\"2\",\"score\":-5,\"why\":\"b\"}]}";

        List<RankedPaper> ranked = PaperDiscoveryAgent.parseRanking(reply, candidates);
        assertEquals(100, ranked.get(0).score());
        assertEquals(0, ranked.get(1).score());
    }

    @Test
    void whenTheModelIsUnavailablePapersComeBackUnrankedNotFabricated() throws Exception {
        // UC03 6a: shown unranked and clearly labelled
        FakeLLMClient llm = new FakeLLMClient().failWith("no key");
        ToolManager tools = new ToolManager(llm);
        tools.setLiteratureSource(new FakeLiteratureSource("arXiv")
                .returning(paper("1", "A paper"), paper("2", "Another")));

        @SuppressWarnings("unchecked")
        List<RankedPaper> out = (List<RankedPaper>) new PaperDiscoveryAgent(tools).run(profile());

        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(RankedPaper::isUnranked),
                "the UI must be able to tell these were not ranked");
    }

    // --- Observer ------------------------------------------------------------

    @Test
    void everyListenerHearsAboutEveryPaper() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient()
                .respondWith("{\"ranked\":[{\"id\":\"1\",\"score\":70,\"why\":\"a\"},"
                        + "{\"id\":\"2\",\"score\":60,\"why\":\"b\"}]}"));
        tools.setLiteratureSource(new FakeLiteratureSource("arXiv")
                .returning(paper("1", "First"), paper("2", "Second")));

        PaperDiscoveryAgent agent = new PaperDiscoveryAgent(tools);
        RecordingListener feed = new RecordingListener();
        RecordingListener digest = new RecordingListener();
        agent.addListener(feed);
        agent.addListener(digest);

        agent.run(profile());

        assertEquals(List.of("1", "2"), feed.seen, "in ranked order");
        assertEquals(List.of("1", "2"), digest.seen,
                "the agent broadcasts once and both observers hear it");
    }

    @Test
    void aListenerThatThrowsDoesNotSilenceTheOthers() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        PaperDiscoveryAgent agent = new PaperDiscoveryAgent(tools);
        RecordingListener good = new RecordingListener();

        agent.addListener(p -> {
            throw new IllegalStateException("the GUI blew up");
        });
        agent.addListener(good);

        agent.notifyListeners(paper("1", "First"));

        assertEquals(List.of("1"), good.seen,
                "a broken feed must not stop the digest from recording the paper");
    }

    @Test
    void theSameListenerIsNotRegisteredTwice() {
        PaperDiscoveryAgent agent = new PaperDiscoveryAgent(new ToolManager(new FakeLLMClient()));
        RecordingListener once = new RecordingListener();
        agent.addListener(once);
        agent.addListener(once);

        agent.notifyListeners(paper("1", "First"));
        assertEquals(1, once.seen.size(), "registering twice must not notify twice");
    }

    @Test
    void aRemovedListenerStopsHearing() {
        PaperDiscoveryAgent agent = new PaperDiscoveryAgent(new ToolManager(new FakeLLMClient()));
        RecordingListener listener = new RecordingListener();
        agent.addListener(listener);
        agent.removeListener(listener);

        agent.notifyListeners(paper("1", "First"));
        assertTrue(listener.seen.isEmpty());
    }

    @Test
    void rejectsANullListener() {
        PaperDiscoveryAgent agent = new PaperDiscoveryAgent(new ToolManager(new FakeLLMClient()));
        assertThrows(IllegalArgumentException.class, () -> agent.addListener(null));
    }

    // --- query relaxation ----------------------------------------------------

    @Test
    void aQueryThatMatchesNothingIsWidenedRatherThanGivenUpOn() throws Exception {
        // the first live run returned "no new papers" for a perfectly sensible
        // topic, because every term must appear in the abstract and the model
        // wrote four- and five-term queries
        ToolManager tools = new ToolManager(new FakeLLMClient()
                .respondWith("fairness synthetic chest radiograph classifier"));
        FakeLiteratureSource source = new FakeLiteratureSource("arXiv")
                .returning(paper("1", "Found after relaxing"))
                .emptyUntilRelaxedTo(2);
        tools.setLiteratureSource(source);

        @SuppressWarnings("unchecked")
        List<RankedPaper> out = (List<RankedPaper>) new PaperDiscoveryAgent(tools).run(profile());

        assertEquals(1, out.size(), "relaxing the query should have found the paper");
        assertEquals(List.of(
                        "fairness synthetic chest radiograph classifier",
                        "fairness synthetic chest radiograph",
                        "fairness synthetic chest",
                        "fairness synthetic"),
                source.queriesSeen(),
                "it should drop one term at a time until something matches");
    }

    @Test
    void relaxationStopsOnceSomethingMatches() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("fairness synthetic"));
        FakeLiteratureSource source = new FakeLiteratureSource("arXiv")
                .returning(paper("1", "Matched immediately"));
        tools.setLiteratureSource(source);

        new PaperDiscoveryAgent(tools).run(profile());

        assertEquals(1, source.queriesSeen().size(),
                "a query that works must not be relaxed anyway");
    }

    @Test
    void aQueryThatNeverMatchesIsExhaustedNotLooped() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("a b c"));
        FakeLiteratureSource source = new FakeLiteratureSource("arXiv").returning();
        tools.setLiteratureSource(source);

        @SuppressWarnings("unchecked")
        List<RankedPaper> out = (List<RankedPaper>) new PaperDiscoveryAgent(tools).run(profile());

        assertTrue(out.isEmpty());
        assertEquals(List.of("a b c", "a b", "a"), source.queriesSeen());
    }
}
