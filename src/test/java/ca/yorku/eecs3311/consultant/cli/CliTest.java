package ca.yorku.eecs3311.consultant.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.controller.AppController;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import ca.yorku.eecs3311.consultant.memory.MemoryStore;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CLI is tested through a real {@link AppController}, writing to captured
 * streams rather than {@code System.out}. Both clients must reach every
 * feature, so the CLI needs the same test pressure as the rest.
 */
class CliTest {

    @TempDir
    Path tempDir;

    private Cli cli;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void setUp() {
        ProjectMemory.getInstance().clear();
        AppController controller = new AppController(ProjectMemory.getInstance(),
                new ToolManager(new FakeLLMClient()), new MemoryStore(tempDir));
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        cli = new Cli(controller,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }

    // --- usage ---------------------------------------------------------------

    @Test
    void noArgumentsPrintsUsageAndSucceeds() {
        assertEquals(0, cli.run(new String[] {}));
        assertTrue(stdout().contains("Usage:"), stdout());
    }

    @Test
    void helpIsARealCommandNotAnUnknownOne() {
        // it previously printed "Unknown command: help" before the usage text
        assertEquals(0, cli.run(new String[] {"help"}));
        assertFalse(stdout().contains("Unknown"), stdout());
        assertFalse(stderr().contains("Unknown"), stderr());
    }

    @Test
    void anUnknownCommandFailsAndSaysSoOnStderr() {
        assertNotEquals(0, cli.run(new String[] {"frobnicate"}));
        assertTrue(stderr().contains("frobnicate"), stderr());
    }

    @Test
    void theUsageListsEveryCommandItAccepts() {
        cli.run(new String[] {"help"});
        for (String command : List.of("profile set", "profile show", "where")) {
            assertTrue(stdout().contains(command), command + " missing from usage:\n" + stdout());
        }
    }

    // --- profile show --------------------------------------------------------

    @Test
    void showingAnEmptyProfileSaysSoRatherThanPrintingBlanks() {
        assertEquals(0, cli.run(new String[] {"profile", "show"}));
        assertTrue(stdout().toLowerCase().contains("no project profile"), stdout());
    }

    // --- profile set ---------------------------------------------------------

    @Test
    void settingACompleteProfileSucceedsAndConfirms() {
        int code = cli.run(new String[] {
            "profile", "set",
            "--question", "Does conditioning on demographics close the fairness gap?",
            "--datasets", "CheXpert, MIMIC-CXR",
            "--metrics", "AUC, subgroup AUC gap",
            "--models", "conditional diffusion",
        });

        assertEquals(0, code, stderr());
        assertTrue(stdout().toLowerCase().contains("saved"), stdout());
        assertEquals(List.of("CheXpert", "MIMIC-CXR"),
                ProjectMemory.getInstance().getProfile().getDatasets(),
                "a comma-separated list must become separate entries, trimmed");
    }

    @Test
    void aProfileSetThisWayIsVisibleToProfileShow() {
        cli.run(new String[] {
            "profile", "set", "--question", "a real question",
            "--datasets", "CheXpert", "--metrics", "AUC",
        });
        out.reset();

        cli.run(new String[] {"profile", "show"});
        assertTrue(stdout().contains("a real question"), stdout());
        assertTrue(stdout().contains("CheXpert"), stdout());
    }

    @Test
    void anIncompleteProfileFailsAndNamesTheMissingFields() {
        int code = cli.run(new String[] {"profile", "set", "--question", "only a question"});

        assertNotEquals(0, code);
        assertTrue(stderr().contains("datasets"), stderr());
        assertTrue(stderr().contains("metrics"), stderr());
    }

    @Test
    void anIncompleteProfileChangesNothing() {
        cli.run(new String[] {"profile", "set", "--question", "only a question"});
        assertTrue(ProjectMemory.getInstance().getProfile().getResearchQuestion().isBlank());
    }

    @Test
    void aFlagWithNoValueIsAnErrorRatherThanASilentEmptyField() {
        int code = cli.run(new String[] {"profile", "set", "--question"});
        assertNotEquals(0, code);
        assertTrue(stderr().contains("--question"), stderr());
    }

    @Test
    void anUnknownFlagIsRejectedRatherThanIgnored() {
        // silently ignoring a typo'd flag would discard the value the user typed
        int code = cli.run(new String[] {
            "profile", "set", "--question", "q", "--datasets", "d",
            "--metrics", "m", "--dataset", "typo",
        });
        assertNotEquals(0, code);
        assertTrue(stderr().contains("--dataset"), stderr());
    }

    @Test
    void profileWithNoSubcommandExplainsItself() {
        assertNotEquals(0, cli.run(new String[] {"profile"}));
        assertTrue(stderr().contains("profile"), stderr());
    }

    // --- where ---------------------------------------------------------------

    @Test
    void whereReportsTheStorageFile() {
        assertEquals(0, cli.run(new String[] {"where"}));
        assertTrue(stdout().contains("project-memory.json"), stdout());
    }
}
