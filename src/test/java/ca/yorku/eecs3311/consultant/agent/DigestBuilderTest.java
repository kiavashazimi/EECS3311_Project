package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.domain.Digest;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F12, and the consumer side of the Observer.
 *
 * <p>The point of the pattern here is that the builder is <em>told</em> as each
 * paper is found, so by the time a digest is asked for it already has them and
 * re-queries nothing. A polling design would have to ask what changed.
 */
class DigestBuilderTest {

    private FakeLLMClient llm;
    private ProjectMemory memory;
    private DigestBuilder builder;

    @BeforeEach
    void setUp() {
        memory = ProjectMemory.getInstance();
        memory.clear();
        llm = new FakeLLMClient();
        builder = new DigestBuilder(new ToolManager(llm), memory);
    }

    private static PaperRecord paper(String id, String title) {
        PaperRecord p = new PaperRecord(id, title, "arXiv cs.CV", "2026-01-01");
        p.setSummary("abstract of " + title);
        return p;
    }

    private static PaperAnalysis analysed(String id, String title, String... tags) {
        PaperAnalysis a = new PaperAnalysis(paper(id, title));
        a.setStrategyTags(List.of(tags));
        return a;
    }

    // --- the Observer side ---------------------------------------------------

    @Test
    void papersArriveThroughTheListenerRatherThanBeingQueriedFor() throws Exception {
        builder.onNewPaper(paper("1", "First"));
        builder.onNewPaper(paper("2", "Second"));
        llm.respondWith("Two new papers this week.");

        Digest digest = builder.buildDigest();

        assertEquals(2, digest.getNewPapers().size(), "the builder was told, not asked");
    }

    @Test
    void itIsAPaperFeedListenerSoDiscoveryNeverKnowsAboutIt() {
        assertTrue(builder instanceof PaperFeedListener,
                "the agent must be able to broadcast to it through the interface alone");
    }

    @Test
    void theSamePaperFoundTwiceIsReportedOnce() throws Exception {
        builder.onNewPaper(paper("1", "First"));
        builder.onNewPaper(paper("1", "First"));
        llm.respondWith("one paper");

        assertEquals(1, builder.buildDigest().getNewPapers().size());
    }

    @Test
    void anObserverMustNotThrowBackAtTheSubject() {
        // PaperFeedListener forbids it: one failing listener must not stop the
        // others from being told
        builder.onNewPaper(null);
        assertEquals(0, builder.pendingCount());
    }

    @Test
    void collectedPapersSurviveIntoAnotherProcessThroughMemory() {
        // rc discover and rc digest are different JVMs
        builder.onNewPaper(paper("1", "First"));

        assertEquals(1, memory.getDigestPending().size(),
                "the pending papers live in project memory so they can be persisted");
    }

    // --- nothing new ---------------------------------------------------------

    @Test
    void anEmptyDigestIsNotAnError() throws Exception {
        Digest digest = builder.buildDigest();

        assertTrue(digest.isEmpty());
        assertEquals(0, llm.callCount(),
                "there is nothing to narrate, so the model is not called");
    }

    @Test
    void aDigestWithNothingToReportDoesNotMoveTheSinceMarker() throws Exception {
        // stamping it here made the first digest say "nothing new since <this
        // very minute>", claiming a previous digest that never ran
        assertTrue(memory.getLastDigestAt().isBlank());

        builder.buildDigest();

        assertTrue(memory.getLastDigestAt().isBlank(),
                "an empty digest is not a thing to be since");
    }

    @Test
    void aDigestThatReportedSomethingDoesMoveTheSinceMarker() throws Exception {
        builder.onNewPaper(paper("1", "First"));
        llm.respondWith("a summary");

        builder.buildDigest();

        assertFalse(memory.getLastDigestAt().isBlank(),
                "the next digest can now say what it is since");
    }

    @Test
    void papersAreNotReportedTwiceAcrossConsecutiveDigests() throws Exception {
        builder.onNewPaper(paper("1", "First"));
        llm.respondWith("a summary");
        assertEquals(1, builder.buildDigest().getNewPapers().size());

        Digest second = builder.buildDigest();

        assertTrue(second.isEmpty(), "the first digest already reported it");
    }

    // --- what the digest is made of ------------------------------------------

    @Test
    void theAnalysisOfANewPaperContributesItsStrategyTags() throws Exception {
        memory.addPaperAnalysis(analysed("1", "First", "diffusion model", "data augmentation"));
        builder.onNewPaper(paper("1", "First"));
        llm.respondWith("a summary");

        Digest digest = builder.buildDigest();

        assertTrue(digest.getStrategyHighlights().contains("diffusion model"),
                digest.getStrategyHighlights().toString());
    }

    @Test
    void anUnanalysedPaperStillAppearsWithNoHighlightsOfItsOwn() throws Exception {
        builder.onNewPaper(paper("9", "Never analysed"));
        llm.respondWith("a summary");

        Digest digest = builder.buildDigest();

        assertEquals(1, digest.getNewPapers().size());
        assertTrue(digest.getStrategyHighlights().isEmpty(),
                "a tag must not be invented for a paper nobody has analysed");
    }

    @Test
    void theLatestProjectScanIsIncludedWhenThereIsOne() throws Exception {
        ContextSnapshot snapshot = new ContextSnapshot("2026-11-03T10:00", "/home/me/study");
        snapshot.setRecentCommits(List.of("switch the metric from accuracy to AUROC"));
        memory.saveSnapshot(snapshot);
        llm.respondWith("a summary");

        Digest digest = builder.buildDigest();

        assertFalse(digest.isEmpty(), "a project change alone is worth a digest");
        assertTrue(digest.getProjectChanges().contains("AUROC"), digest.getProjectChanges());
    }

    @Test
    void anUnscannedProjectContributesNothingRatherThanABlankChange() throws Exception {
        builder.onNewPaper(paper("1", "First"));
        llm.respondWith("a summary");

        Digest digest = builder.buildDigest();

        assertTrue(digest.getProjectChanges().isBlank(),
                "never scanned is not the same as nothing changed");
    }

    // --- the model ------------------------------------------------------------

    @Test
    void anUnreachableModelStillLeavesAnAssembledDigest() throws Exception {
        builder.onNewPaper(paper("1", "First"));
        llm.failWith("HTTP 529 overloaded");

        Digest digest = builder.buildDigest();

        assertTrue(digest.isSummaryMissing());
        assertEquals(1, digest.getNewPapers().size(),
                "UC11 4a: the structured digest is shown without the narrative");
    }

    @Test
    void aFailedNarrativeStillCountsAsReported() throws Exception {
        builder.onNewPaper(paper("1", "First"));
        llm.failWith("HTTP 529");
        builder.buildDigest();

        assertTrue(builder.buildDigest().isEmpty(),
                "the papers were listed, so repeating them would be noise");
    }

    @Test
    void thePromptCarriesTheAssembledFactsNotJustTheTitles() throws Exception {
        memory.addPaperAnalysis(analysed("1", "First", "diffusion model"));
        builder.onNewPaper(paper("1", "First"));
        llm.respondWith("a summary");

        builder.buildDigest();

        String prompt = llm.promptsSeen().get(0);
        assertTrue(prompt.contains("First"), prompt);
        assertTrue(prompt.contains("diffusion model"), prompt);
    }

    @Test
    void theBuilderRefusesToBeConstructedWithoutItsCollaborators() {
        assertThrows(IllegalArgumentException.class,
                () -> new DigestBuilder(null, memory));
        assertThrows(IllegalArgumentException.class,
                () -> new DigestBuilder(new ToolManager(llm), null));
    }

    @Test
    void theMemoryItUsesIsTheSharedSingleton() {
        assertSame(ProjectMemory.getInstance(), memory);
    }
}
