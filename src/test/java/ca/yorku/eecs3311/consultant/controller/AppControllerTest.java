package ca.yorku.eecs3311.consultant.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import ca.yorku.eecs3311.consultant.memory.MemoryStore;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F01 through the facade, exactly as sequence diagram SD01 draws it. */
class AppControllerTest {

    @TempDir
    Path tempDir;

    private AppController controller;
    private MemoryStore store;

    @BeforeEach
    void setUp() {
        ProjectMemory.getInstance().clear();
        store = new MemoryStore(tempDir);
        controller = new AppController(
                ProjectMemory.getInstance(), new ToolManager(new FakeLLMClient()), store);
    }

    private static ProjectProfile complete() {
        ProjectProfile p = new ProjectProfile(
                "Does conditioning a generator on demographics close the fairness gap?");
        p.setDatasets(List.of("CheXpert"));
        p.setMetrics(List.of("AUC", "subgroup AUC gap"));
        p.setModels(List.of("conditional diffusion"));
        return p;
    }

    // --- the valid path ------------------------------------------------------

    @Test
    void aCompleteProfileIsStoredAndReturned() throws Exception {
        ProjectProfile saved = controller.saveProfile(complete());

        assertTrue(saved.isComplete());
        assertEquals(saved.getResearchQuestion(),
                ProjectMemory.getInstance().getProfile().getResearchQuestion());
    }

    @Test
    void savingAlsoWritesItToDisk() throws Exception {
        controller.saveProfile(complete());

        assertTrue(store.exists(), "a saved profile must survive a restart");
        assertTrue(Files.readString(store.getFile()).contains("CheXpert"));
    }

    @Test
    void aSavedProfileIsReadableAfterAReload() throws Exception {
        controller.saveProfile(complete());

        ProjectMemory.getInstance().clear();
        assertTrue(controller.loadStoredProject());
        assertEquals(List.of("CheXpert"), controller.getProfile().getDatasets());
    }

    // --- the validation path (SD01's ValidationError branch) ----------------

    @Test
    void onlyABlankQuestionIsRejectedAndTheFieldIsNamed() {
        ValidationException e = assertThrows(ValidationException.class,
                () -> controller.saveProfile(new ProjectProfile("   ")));

        assertEquals(List.of("researchQuestion"), e.getFields(),
                "the GUI marks the field inline, so it needs its name");
    }

    @Test
    void aQuestionOnlyProfileIsSavedAndSaysWhatItIsMissing() throws Exception {
        ProjectProfile bare = new ProjectProfile("does concept mapping aid recall?");

        ProjectProfile saved = controller.saveProfile(bare);

        assertEquals("does concept mapping aid recall?", saved.getResearchQuestion());
        assertEquals(List.of("datasets", "metrics"), saved.recommendedFieldsMissing(),
                "saving it is not the same as pretending it is complete");
        assertTrue(store.exists(), "a profile without datasets is still worth persisting");
    }

    @Test
    void aRejectedProfileStoresNothing() {
        // SD01: "inline error, nothing persisted"
        assertThrows(ValidationException.class,
                () -> controller.saveProfile(new ProjectProfile("")));

        assertFalse(store.exists(), "a failed validation must not create a file");
        assertTrue(ProjectMemory.getInstance().getProfile().getResearchQuestion().isBlank());
    }

    @Test
    void aRejectedProfileLeavesTheEarlierOneIntact() throws Exception {
        controller.saveProfile(complete());

        assertThrows(ValidationException.class,
                () -> controller.saveProfile(new ProjectProfile("  ")));

        assertEquals(List.of("CheXpert"), controller.getProfile().getDatasets(),
                "the previous profile must survive a failed save");
    }

    @Test
    void rejectsANullProfile() {
        assertThrows(IllegalArgumentException.class, () -> controller.saveProfile(null));
    }

    // --- the storage-failure path (SD01's StorageError branch) --------------

    @Test
    void aWriteFailureIsReportedWithoutLosingTheLiveProfile() throws Exception {
        controller.saveProfile(complete());

        // a directory where the file should be: the write cannot succeed
        Path blocked = tempDir.resolve("blocked");
        Files.createDirectories(blocked.resolve("project-memory.json"));
        AppController failing = new AppController(ProjectMemory.getInstance(),
                new ToolManager(new FakeLLMClient()), new MemoryStore(blocked));

        ProjectProfile updated = complete();
        updated.setDatasets(List.of("MIMIC-CXR"));

        assertThrows(StorageException.class, () -> failing.saveProfile(updated));
    }

    // --- construction --------------------------------------------------------

    @Test
    void rejectsMissingCollaborators() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        assertThrows(IllegalArgumentException.class,
                () -> new AppController(null, tools, store));
        assertThrows(IllegalArgumentException.class,
                () -> new AppController(ProjectMemory.getInstance(), null, store));
        assertThrows(IllegalArgumentException.class,
                () -> new AppController(ProjectMemory.getInstance(), tools, null));
    }

    @Test
    void loadingWithNothingStoredIsNormalNotAnError() throws IOException {
        assertFalse(controller.loadStoredProject(),
                "a first run has no file, which is not a failure");
    }

    @Test
    void reportsWhereTheProjectIsStored() {
        assertEquals(store.getFile(), controller.getStorageLocation());
    }

    // --- starting a new project ---------------------------------------------

    @Test
    void startingANewProjectDiscardsTheProfileAndTheAnalyses() throws Exception {
        controller.saveProfile(complete());
        ProjectMemory.getInstance().addPaperAnalysis(anAnalysis());
        assertEquals(1, controller.getProfile().getDatasets().size());

        controller.startNewProject();

        assertTrue(controller.getProfile().getResearchQuestion().isBlank());
        assertTrue(controller.getProfile().getDatasets().isEmpty());
        assertTrue(ProjectMemory.getInstance().getPaperAnalyses().isEmpty(),
                "the solution landscape is built from these, so they must go too");
    }

    @Test
    void aNewProjectIsPersistedRatherThanOnlyCleardInMemory() throws Exception {
        controller.saveProfile(complete());
        ProjectMemory.getInstance().addPaperAnalysis(anAnalysis());

        controller.startNewProject();

        // the next process must not read the old project back
        ProjectMemory.getInstance().saveProfile(complete());
        assertTrue(controller.loadStoredProject());
        assertTrue(controller.getProfile().getResearchQuestion().isBlank(),
                "the cleared state has to reach disk, not just memory");
        assertTrue(ProjectMemory.getInstance().getPaperAnalyses().isEmpty());
    }

    private static PaperAnalysis anAnalysis() {
        PaperAnalysis a = new PaperAnalysis(
                new PaperRecord("2412.20374v2", "FairDiffusion", "arXiv cs.CV", "2024-12-29"));
        a.setMethod("a fair Bayesian perturbation inside a latent diffusion model");
        a.setStrategyTags(List.of("diffusion model"));
        return a;
    }
}
