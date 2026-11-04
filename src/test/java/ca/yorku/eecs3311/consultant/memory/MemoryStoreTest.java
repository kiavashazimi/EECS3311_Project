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

    @Test
    void anUnsetSnapshotIsNotWrittenToDisk() throws IOException {
        // a snapshot of blank strings reads as "we scanned and found nothing",
        // which is a different claim from "no project was ever imported"
        ProjectMemory memory = ProjectMemory.getInstance();
        memory.clear();
        memory.saveProfile(new ProjectProfile("a question"));

        MemoryStore store = new MemoryStore(tempDir);
        store.save(memory);

        String json = Files.readString(store.getFile());
        assertFalse(json.contains("lastSnapshot"),
                "an empty snapshot must be absent from the file, not written blank:\n" + json);
    }

    @Test
    void aRealSnapshotStillRoundTrips() throws IOException {
        ProjectMemory memory = ProjectMemory.getInstance();
        memory.clear();
        ContextSnapshot snapshot = new ContextSnapshot("2026-11-03T10:00", "/home/me/project");
        snapshot.setProviderName("FileBasedContextProvider");
        memory.saveSnapshot(snapshot);

        MemoryStore store = new MemoryStore(tempDir);
        store.save(memory);
        assertTrue(Files.readString(store.getFile()).contains("lastSnapshot"));

        memory.clear();
        assertTrue(store.load(memory));
        assertEquals("/home/me/project", memory.getLastSnapshot().getProjectPath());
        assertEquals("FileBasedContextProvider", memory.getLastSnapshot().getProviderName());
    }

    @Test
    void aScanThatFoundNothingIsStillStored() throws IOException {
        // "we looked and there was nothing" is a finding; "we never looked" is
        // not. Only the second may be dropped from the file.
        ProjectMemory memory = ProjectMemory.getInstance();
        memory.clear();
        ContextSnapshot ranButFoundNothing =
                new ContextSnapshot("2026-11-03T10:00", "/home/me/empty-project");
        assertTrue(ranButFoundNothing.isEmpty(), "precondition: the scan found no content");
        memory.saveSnapshot(ranButFoundNothing);

        MemoryStore store = new MemoryStore(tempDir);
        store.save(memory);

        assertTrue(Files.readString(store.getFile()).contains("lastSnapshot"),
                "a scan that ran must be recorded even when it found nothing");

        memory.clear();
        assertTrue(store.load(memory));
        assertEquals("2026-11-03T10:00", memory.getLastSnapshot().getScannedAt());
    }

    // --- the last discovery listing -----------------------------------------

    @Test
    void theListingSurvivesAcrossProcesses() throws IOException {
        // every rc invocation is a fresh JVM, so a row number typed after
        // 'rc discover' can only be resolved from the file
        ProjectMemory memory = ProjectMemory.getInstance();
        memory.clear();
        memory.saveLastListing(List.of("2304.09218v1", "2412.20374v2", "2311.09402v2"));

        MemoryStore store = new MemoryStore(tempDir);
        store.save(memory);
        memory.clear();
        assertTrue(store.load(memory));

        assertEquals(List.of("2304.09218v1", "2412.20374v2", "2311.09402v2"),
                memory.getLastListing(),
                "the order shown on screen is the order the numbers refer to");
    }

    @Test
    void aFileWrittenBeforeListingsExistedStillLoads() throws IOException {
        // forward compatibility: files already on disk have no listing field
        Path file = tempDir.resolve("project-memory.json");
        Files.writeString(file, """
                {"profile":{"researchQuestion":"q"},"papers":[]}
                """);

        ProjectMemory memory = ProjectMemory.getInstance();
        MemoryStore store = new MemoryStore(tempDir);

        assertTrue(store.load(memory), "an older file must not be treated as corrupt");
        assertTrue(memory.getLastListing().isEmpty());
    }
}
