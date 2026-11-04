package ca.yorku.eecs3311.consultant.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectMemoryTest {

    @BeforeEach
    void isolateEachTest() {
        ProjectMemory.getInstance().clear();
    }

    private static PaperAnalysis analysis(String id, String title) {
        PaperAnalysis a = new PaperAnalysis(new PaperRecord(id, title, "ICLR 25", "2025-05"));
        a.setDataset("NaturalQuestions-LC");
        return a;
    }

    // --- the pattern itself -------------------------------------------------

    @Test
    void everyCallerReceivesTheSameInstance() {
        assertSame(ProjectMemory.getInstance(), ProjectMemory.getInstance());
    }

    @Test
    void theConstructorIsPrivateSoNoSecondInstanceCanBeMade() throws Exception {
        Constructor<?>[] constructors = ProjectMemory.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()),
                "a public constructor would defeat the singleton");
    }

    @Test
    void stateWrittenByOneCallerIsVisibleToAnother() {
        // stands in for the GUI writing and the CLI reading
        ProjectMemory.getInstance().saveProfile(new ProjectProfile("retrieval for long-context QA"));
        assertEquals("retrieval for long-context QA",
                ProjectMemory.getInstance().getProfile().getResearchQuestion(),
                "the GUI and the CLI must never drift apart");
    }

    // --- profile ------------------------------------------------------------

    @Test
    void aFreshMemoryHasAnEmptyProfileRatherThanNull() {
        ProjectProfile p = ProjectMemory.getInstance().getProfile();
        assertNotNull(p);
        assertFalse(p.isComplete());
    }

    @Test
    void savingANullProfileIsRejectedRatherThanWipingTheStoredOne() {
        ProjectMemory m = ProjectMemory.getInstance();
        m.saveProfile(new ProjectProfile("kept"));
        assertThrows(IllegalArgumentException.class, () -> m.saveProfile(null));
        assertEquals("kept", m.getProfile().getResearchQuestion());
    }

    // --- paper analyses -----------------------------------------------------

    @Test
    void analysesAccumulateAndAreReturnedInInsertionOrder() {
        ProjectMemory m = ProjectMemory.getInstance();
        m.addPaperAnalysis(analysis("p1", "First"));
        m.addPaperAnalysis(analysis("p2", "Second"));
        assertEquals(List.of("First", "Second"),
                m.getPaperAnalyses().stream().map(a -> a.getPaper().getTitle()).toList());
    }

    @Test
    void reanalysingTheSamePaperReplacesItRatherThanDuplicatingIt() {
        ProjectMemory m = ProjectMemory.getInstance();
        m.addPaperAnalysis(analysis("p1", "A Paper"));
        PaperAnalysis redone = analysis("p1", "A Paper");
        redone.setDataset("MS MARCO");
        m.addPaperAnalysis(redone);

        assertEquals(1, m.getPaperAnalyses().size(), "F04 can be run twice on one paper");
        assertEquals("MS MARCO", m.getPaperAnalyses().get(0).getDataset(),
                "the newer analysis must win");
    }

    @Test
    void findByPaperIdReturnsEmptyWhenNothingMatches() {
        ProjectMemory m = ProjectMemory.getInstance();
        m.addPaperAnalysis(analysis("p1", "A Paper"));
        assertTrue(m.findAnalysis("p1").isPresent());
        assertTrue(m.findAnalysis("missing").isEmpty(),
                "F11 says it has no relevant record rather than inventing one");
    }

    // --- snapshot -----------------------------------------------------------

    @Test
    void thereIsNoSnapshotUntilOneIsImported() {
        assertTrue(ProjectMemory.getInstance().getLastSnapshot().isEmpty(),
                "F10 must be able to tell that no prior scan exists");
    }

    @Test
    void savingASnapshotReplacesThePreviousOne() {
        ProjectMemory m = ProjectMemory.getInstance();
        m.saveSnapshot(new ContextSnapshot("2026-11-01", "/p"));
        m.saveSnapshot(new ContextSnapshot("2026-11-02", "/p"));
        assertEquals("2026-11-02", m.getLastSnapshot().getScannedAt());
    }

    // --- isolation ----------------------------------------------------------

    @Test
    void theStoredListCannotBeMutatedThroughTheGetter() {
        ProjectMemory m = ProjectMemory.getInstance();
        m.addPaperAnalysis(analysis("p1", "A Paper"));
        assertThrows(UnsupportedOperationException.class,
                () -> m.getPaperAnalyses().add(analysis("p2", "Sneaked in")),
                "shared state must not be writable by accident");
    }

    // --- the last discovery listing -----------------------------------------

    @Test
    void thereIsNoListingUntilADiscoveryRunRecordsOne() {
        assertTrue(ProjectMemory.getInstance().getLastListing().isEmpty());
    }

    @Test
    void theListingIsKeptInTheOrderItWasShownIn() {
        ProjectMemory.getInstance().saveLastListing(
                List.of("2304.09218v1", "2412.20374v2", "2311.09402v2"));

        assertEquals(List.of("2304.09218v1", "2412.20374v2", "2311.09402v2"),
                ProjectMemory.getInstance().getLastListing());
    }

    @Test
    void aNewListingReplacesTheOldOneRatherThanAppending() {
        ProjectMemory.getInstance().saveLastListing(List.of("a", "b", "c"));
        ProjectMemory.getInstance().saveLastListing(List.of("x"));

        assertEquals(List.of("x"), ProjectMemory.getInstance().getLastListing());
    }

    @Test
    void aNullListingClearsItRatherThanThrowing() {
        ProjectMemory.getInstance().saveLastListing(List.of("a"));
        ProjectMemory.getInstance().saveLastListing(null);

        assertTrue(ProjectMemory.getInstance().getLastListing().isEmpty());
    }

    @Test
    void theListingCannotBeMutatedThroughTheGetter() {
        ProjectMemory.getInstance().saveLastListing(List.of("a"));

        assertThrows(UnsupportedOperationException.class,
                () -> ProjectMemory.getInstance().getLastListing().add("b"));
    }

    @Test
    void clearResetsEverySlot() {
        ProjectMemory m = ProjectMemory.getInstance();
        m.saveProfile(new ProjectProfile("q"));
        m.addPaperAnalysis(analysis("p1", "A Paper"));
        m.saveSnapshot(new ContextSnapshot("2026-11-02", "/p"));
        m.saveLastListing(List.of("p1"));

        m.clear();

        assertFalse(m.getProfile().isComplete());
        assertTrue(m.getPaperAnalyses().isEmpty());
        assertTrue(m.getLastSnapshot().isEmpty());
        assertTrue(m.getLastListing().isEmpty());
    }
}
