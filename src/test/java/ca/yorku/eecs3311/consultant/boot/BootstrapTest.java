package ca.yorku.eecs3311.consultant.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.config.Config;
import ca.yorku.eecs3311.consultant.controller.AppController;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The composition root.
 *
 * <p>These tests exist because the two clients wired themselves up separately
 * and drifted: {@code Cli.main} called {@code loadStoredProject()} and
 * {@code App.start} did not, so the GUI started blank every time and its first
 * save wrote an empty memory over every stored analysis. One shared bootstrap
 * means a client cannot forget a step it never performs.
 */
class BootstrapTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void isolateEachTest() {
        ProjectMemory.getInstance().clear();
    }

    private Config configPointingAtTempDir() {
        return Config.load(tempDir.resolve("no-such.env"),
                Map.of("RC_DATA_DIR", tempDir.toString()));
    }

    private void givenAStoredProject() throws IOException {
        Files.writeString(tempDir.resolve("project-memory.json"), """
                {"profile":{"researchQuestion":"Do synthetic X-rays close the gap?",
                            "datasets":["MIMIC-CXR"],"metrics":["AUROC"]},
                 "papers":[{"paper":{"id":"2304.09218v1","title":"A Stored Paper"}}],
                 "lastListing":["2304.09218v1","2412.20374v2"]}
                """);
    }

    @Test
    void theBootstrappedControllerHasTheStoredProjectAlreadyLoaded() throws IOException {
        givenAStoredProject();

        AppController controller = Bootstrap.application(configPointingAtTempDir());

        assertEquals("Do synthetic X-rays close the gap?",
                controller.getProfile().getResearchQuestion(),
                "a client must not have to remember to load the project itself");
    }

    @Test
    void storedAnalysesAreVisibleThroughTheBootstrappedController() throws IOException {
        givenAStoredProject();

        AppController controller = Bootstrap.application(configPointingAtTempDir());

        assertEquals(1, controller.getAnalyses().size());
        assertEquals("A Stored Paper",
                controller.getAnalyses().get(0).getPaper().getTitle());
    }

    @Test
    void theRowNumberListingSurvivesTheBootstrapToo() throws IOException {
        givenAStoredProject();

        AppController controller = Bootstrap.application(configPointingAtTempDir());

        assertEquals(2, controller.getLastListing().size());
    }

    @Test
    void aFirstRunWithNothingStoredStartsEmptyRatherThanFailing() {
        AppController controller = Bootstrap.application(configPointingAtTempDir());

        assertFalse(controller.getProfile().isComplete());
        assertTrue(controller.getAnalyses().isEmpty());
    }

    @Test
    void savingAfterABootstrapIsNotRefusedByTheStoreGuard() throws Exception {
        // the guard must stop an unloaded client, not a correctly started one
        givenAStoredProject();
        AppController controller = Bootstrap.application(configPointingAtTempDir());

        controller.saveProfile(controller.getProfile());

        assertTrue(Files.readString(tempDir.resolve("project-memory.json"))
                .contains("A Stored Paper"), "the stored work must survive a profile save");
    }

    // --- which literature Strategy gets chosen -------------------------------

    @Test
    void withNoSemanticScholarKeyArxivIsUsedAloneWithNoFallback() {
        Bootstrap.application(configPointingAtTempDir());

        // leading with the anonymous S2 endpoint would buy a guaranteed 429
        // round trip before every search
        assertEquals("arXiv", Bootstrap.literatureSourceName(configPointingAtTempDir()));
        assertNull(Bootstrap.fallbackSourceName(configPointingAtTempDir()));
    }

    @Test
    void withASemanticScholarKeyItLeadsAndArxivCatchesIt() {
        Config config = Config.load(tempDir.resolve("no-such.env"),
                Map.of("RC_DATA_DIR", tempDir.toString(), "S2_API_KEY", "a-real-looking-key"));

        assertEquals("Semantic Scholar", Bootstrap.literatureSourceName(config));
        assertEquals("arXiv", Bootstrap.fallbackSourceName(config),
                "S2 rate limits hard enough that 429 is its ordinary failure");
    }

    @Test
    void theExamplePlaceholderDoesNotCountAsAConfiguredKey() {
        Config config = Config.load(tempDir.resolve("no-such.env"),
                Map.of("RC_DATA_DIR", tempDir.toString(), "S2_API_KEY", "YOUR_S2_KEY_HERE"));

        assertEquals("arXiv", Bootstrap.literatureSourceName(config),
                "copying .env.example without editing it must not look configured");
    }
}
