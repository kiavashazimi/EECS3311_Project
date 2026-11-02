package ca.yorku.eecs3311.consultant.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryStoreTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateEachTest() {
        ProjectMemory.getInstance().clear();
    }

    private static ProjectProfile profile() {
        ProjectProfile p = new ProjectProfile("Efficient retrieval for long-context QA");
        p.setDatasets(List.of("NaturalQuestions-LC"));
        p.setMetrics(List.of("nDCG@10"));
        p.setModels(List.of("Llama-3-8B"));
        return p;
    }

    private static PaperAnalysis analysis() {
        PaperAnalysis a = new PaperAnalysis(
                new PaperRecord("p1", "Chunk-free Retrieval", "NeurIPS 25", "2025-11"));
        a.setDataset("NaturalQuestions-LC");
        a.setMetrics(List.of("Recall@20"));
        a.addStrategyTag("retrieval");
        return a;
    }

    @Test
    void savingThenLoadingRestoresEveryField() throws IOException {
        MemoryStore store = new MemoryStore(tempDir);
        ProjectMemory m = ProjectMemory.getInstance();
        m.saveProfile(profile());
        m.addPaperAnalysis(analysis());
        ContextSnapshot snap = new ContextSnapshot("2026-11-02", "/home/kia/project");
        snap.setRecentCommits(List.of("c1", "c2"));
        snap.setConfigEntries(Map.of("chunk_size", "256"));
        m.saveSnapshot(snap);

        store.save(m);
        m.clear();
        assertFalse(m.getProfile().isComplete(), "cleared before loading, so the load is real");

        store.load(m);

        assertEquals("Efficient retrieval for long-context QA", m.getProfile().getResearchQuestion());
        assertEquals(List.of("NaturalQuestions-LC"), m.getProfile().getDatasets());
        assertEquals(1, m.getPaperAnalyses().size());
        assertEquals("Chunk-free Retrieval", m.getPaperAnalyses().get(0).getPaper().getTitle());
        assertEquals(List.of("retrieval"), m.getPaperAnalyses().get(0).getStrategyTags());
        assertEquals("2026-11-02", m.getLastSnapshot().getScannedAt());
        assertEquals(List.of("c1", "c2"), m.getLastSnapshot().getRecentCommits());
        assertEquals("256", m.getLastSnapshot().getConfigEntries().get("chunk_size"));
    }

    @Test
    void loadingWhenNoFileExistsIsACleanFirstRun() throws IOException {
        MemoryStore store = new MemoryStore(tempDir.resolve("never-used"));
        ProjectMemory m = ProjectMemory.getInstance();

        assertFalse(store.exists());
        store.load(m);

        assertFalse(m.getProfile().isComplete());
        assertTrue(m.getPaperAnalyses().isEmpty());
    }

    @Test
    void aCorruptFileDoesNotCrashTheApplication() throws IOException {
        MemoryStore store = new MemoryStore(tempDir);
        Files.writeString(store.getFile(), "{ this is not valid json");

        ProjectMemory m = ProjectMemory.getInstance();
        boolean loaded = store.load(m);

        assertFalse(loaded, "a corrupt file must be reported, not thrown");
        assertTrue(m.getPaperAnalyses().isEmpty(), "memory stays empty rather than half-filled");
    }

    @Test
    void aFileMissingFieldsLoadsWithDefaultsRatherThanFailing() throws IOException {
        MemoryStore store = new MemoryStore(tempDir);
        Files.writeString(store.getFile(), "{\"profile\":{\"researchQuestion\":\"only this\"}}");

        ProjectMemory m = ProjectMemory.getInstance();
        assertTrue(store.load(m));
        assertEquals("only this", m.getProfile().getResearchQuestion());
        assertTrue(m.getPaperAnalyses().isEmpty());
        assertTrue(m.getLastSnapshot().isEmpty());
    }

    @Test
    void savingCreatesTheDirectoryIfItIsNotThereYet() throws IOException {
        Path nested = tempDir.resolve("a/b/c");
        MemoryStore store = new MemoryStore(nested);
        assertFalse(Files.exists(nested));

        ProjectMemory m = ProjectMemory.getInstance();
        m.saveProfile(profile());
        store.save(m);

        assertTrue(Files.exists(store.getFile()), "first run must not fail for a missing directory");
    }

    @Test
    void theWrittenFileIsReadableJsonNotAnOpaqueBlob() throws IOException {
        MemoryStore store = new MemoryStore(tempDir);
        ProjectMemory m = ProjectMemory.getInstance();
        m.saveProfile(profile());
        store.save(m);

        String written = Files.readString(store.getFile());
        assertTrue(written.contains("researchQuestion"), "a human must be able to inspect it");
        assertTrue(written.contains("\n"), "stored pretty-printed so diffs stay readable");
    }

    @Test
    void savingTwiceLeavesOnlyTheLatestState() throws IOException {
        MemoryStore store = new MemoryStore(tempDir);
        ProjectMemory m = ProjectMemory.getInstance();
        m.saveProfile(new ProjectProfile("first"));
        store.save(m);
        m.saveProfile(new ProjectProfile("second"));
        store.save(m);

        m.clear();
        store.load(m);
        assertEquals("second", m.getProfile().getResearchQuestion());
    }

    @Test
    void derivedValuesAreNotWrittenToDisk() throws IOException {
        MemoryStore store = new MemoryStore(tempDir);
        ProjectMemory m = ProjectMemory.getInstance();
        m.saveProfile(profile());
        m.addPaperAnalysis(analysis());
        store.save(m);

        String written = Files.readString(store.getFile());
        assertFalse(written.contains("\"complete\""),
                "isComplete() is computed, not state - storing it invites a hand-edit that means nothing");
        assertFalse(written.contains("\"empty\""),
                "isEmpty() is computed, not state");
    }
}
