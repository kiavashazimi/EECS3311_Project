package ca.yorku.eecs3311.consultant.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.literature.FakeLiteratureSource;
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
    private ToolManager tools;
    private FakeLLMClient llm;
    private FakeLiteratureSource source;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void setUp() {
        ProjectMemory.getInstance().clear();
        llm = new FakeLLMClient();
        source = new FakeLiteratureSource("arXiv");
        tools = new ToolManager(llm);
        tools.setLiteratureSource(source);
        AppController controller = new AppController(ProjectMemory.getInstance(),
                tools, new MemoryStore(tempDir));
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

    // --- discover ------------------------------------------------------------

    private void givenAProfile() {
        cli.run(new String[] {
            "profile", "set", "--question", "Do synthetic chest X-rays improve fairness?",
            "--datasets", "MIMIC-CXR", "--metrics", "AUROC",
        });
        out.reset();
        err.reset();
    }

    private static PaperRecord paper(String id, String title) {
        PaperRecord p = new PaperRecord(id, title, "arXiv cs.CV", "2026-02-01");
        p.setUrl("http://arxiv.org/abs/" + id);
        p.setSummary("abstract of " + title);
        return p;
    }

    @Test
    void discoverPrintsRankedPapersWithTheirReasons() {
        givenAProfile();
        source.returning(paper("1", "Fair synthetic CXR"), paper("2", "Unrelated"));
        llm.respondWith("{\"ranked\":[{\"id\":\"1\",\"score\":91,"
                + "\"why\":\"same method on CXR\",\"closest_prior_work\":true},"
                + "{\"id\":\"2\",\"score\":12,\"why\":\"no fairness angle\"}]}");

        assertEquals(0, cli.run(new String[] {"discover"}), stderr());

        String printed = stdout();
        assertTrue(printed.contains("Fair synthetic CXR"), printed);
        assertTrue(printed.contains("same method on CXR"),
                "the reason is the product, not the score: " + printed);
        assertTrue(printed.contains("closest prior work"), printed);
    }

    @Test
    void discoverSaysSoWhenNothingMatched() {
        givenAProfile();
        source.returning();

        assertEquals(0, cli.run(new String[] {"discover"}));
        assertTrue(stdout().toLowerCase().contains("no new papers"), stdout());
    }

    @Test
    void discoverLabelsResultsAsUnrankedWhenTheModelIsUnavailable() {
        givenAProfile();
        source.returning(paper("1", "A paper"));
        llm.failWith("no key configured");

        assertEquals(0, cli.run(new String[] {"discover"}));
        assertTrue(stdout().contains("NOT RANKED"),
                "an unranked list must say so rather than imply an order: " + stdout());
    }

    @Test
    void discoverReportsAFailedSearchAndOffersARetry() {
        givenAProfile();
        source.failWith("HTTP 503");

        assertNotEquals(0, cli.run(new String[] {"discover"}));
        assertTrue(stderr().contains("503"), stderr());
        assertTrue(stderr().toLowerCase().contains("retry"), stderr());
    }

    @Test
    void discoverWithoutAProfileExplainsWhatIsMissing() {
        assertNotEquals(0, cli.run(new String[] {"discover"}));
        assertTrue(stderr().contains("research question"), stderr());
    }

    @Test
    void theUsageMentionsDiscover() {
        cli.run(new String[] {"help"});
        assertTrue(stdout().contains("discover"), stdout());
    }

    // --- analyze -------------------------------------------------------------

    private static final String ANALYSIS_REPLY = """
            {"problem":"subgroup underperformance","method":"a diffusion model makes synthetic data",
             "dataset":"MIMIC-CXR","model":"DenseNet-121","metrics":["AUROC"],
             "results":"the gap narrows","limitations":"one modality",
             "strategy_tags":["diffusion"]}
            """;

    @Test
    void analyzePrintsTheStructuredRecord() {
        givenAProfile();
        source.returning(paper("2304.09218", "Generative models improve fairness"));
        llm.respondWith(ANALYSIS_REPLY);

        assertEquals(0, cli.run(new String[] {"analyze", "2304.09218"}), stderr());

        String printed = stdout();
        assertTrue(printed.contains("MIMIC-CXR"), printed);
        assertTrue(printed.contains("DenseNet-121"), printed);
        assertTrue(printed.contains("diffusion model"),
                "the tag should be canonical, not the model's wording: " + printed);
    }

    @Test
    void analyseSpeltTheBritishWayAlsoWorks() {
        givenAProfile();
        source.returning(paper("1", "A paper"));
        llm.respondWith(ANALYSIS_REPLY);
        assertEquals(0, cli.run(new String[] {"analyse", "1"}), stderr());
    }

    @Test
    void analyzeNeedsAPaperId() {
        assertNotEquals(0, cli.run(new String[] {"analyze"}));
        assertTrue(stderr().contains("paper id"), stderr());
    }

    @Test
    void analyzeReportsAnIdThatMatchesNothing() {
        givenAProfile();
        source.returning();

        assertNotEquals(0, cli.run(new String[] {"analyze", "9999.99999"}));
        assertTrue(stderr().contains("9999.99999"), stderr());
    }

    @Test
    void analyzeFlagsAPaperWithNoAbstract() {
        givenAProfile();
        PaperRecord titleOnly = new PaperRecord("1", "Title Only", "arXiv", "2026-01-01");
        source.returning(titleOnly);

        assertNotEquals(0, cli.run(new String[] {"analyze", "1"}));
        assertTrue(stderr().contains("Title Only"), stderr());
    }

    @Test
    void anUnrecognisedStrategyIsPrintedAsUncertain() {
        givenAProfile();
        source.returning(paper("1", "A paper"));
        llm.respondWith("{\"method\":\"a wholly new procedure\","
                + "\"strategy_tags\":[\"quantum fairness\"]}");

        cli.run(new String[] {"analyze", "1"});
        assertTrue(stdout().contains("UNCERTAIN"), stdout());
    }

    @Test
    void analysesListsWhatHasBeenStored() {
        givenAProfile();
        source.returning(paper("1", "First paper"));
        llm.respondWith(ANALYSIS_REPLY);
        cli.run(new String[] {"analyze", "1"});
        out.reset();

        assertEquals(0, cli.run(new String[] {"analyses"}));
        assertTrue(stdout().contains("1 analysed paper"), stdout());
        assertTrue(stdout().contains("First paper"), stdout());
    }

    @Test
    void analysesSaysSoWhenNothingHasBeenAnalysed() {
        assertEquals(0, cli.run(new String[] {"analyses"}));
        assertTrue(stdout().toLowerCase().contains("no papers analysed"), stdout());
    }

    @Test
    void theUsageMentionsAnalyze() {
        cli.run(new String[] {"help"});
        assertTrue(stdout().contains("analyze"), stdout());
        assertTrue(stdout().contains("analyses"), stdout());
    }

    // --- compare -------------------------------------------------------------

    private void givenAnAnalysedPaper(String id, String dataset, String metrics) {
        givenAProfile();
        source.returning(paper(id, "A relevant paper"));
        llm.respondWith("{\"problem\":\"p\",\"method\":\"a diffusion model\","
                + "\"dataset\":\"" + dataset + "\",\"model\":\"DenseNet-121\","
                + "\"metrics\":[" + metrics + "],\"results\":\"0.86\","
                + "\"limitations\":\"l\",\"strategy_tags\":[\"diffusion\"]}");
        cli.run(new String[] {"analyze", id});
        out.reset();
        err.reset();
    }

    @Test
    void comparePrintsTheVerdictAndTheNarrative() {
        givenAnAnalysedPaper("1", "MIMIC-CXR", "\"AUROC\"");
        llm.respondWith("{\"similarities\":[\"both add synthetic data\"],"
                + "\"differences\":[\"different conditioning\"],"
                + "\"narrative\":\"closest prior work\"}");

        assertEquals(0, cli.run(new String[] {"compare", "1"}), stderr());

        String printed = stdout();
        assertTrue(printed.contains("COMPARABLE"), printed);
        assertTrue(printed.contains("both add synthetic data"), printed);
        assertTrue(printed.contains("closest prior work"), printed);
    }

    @Test
    void compareNamesTheMismatchesWhenTheDataDiffers() {
        givenAnAnalysedPaper("1", "ImageNet", "\"AUROC\"");
        llm.respondWith("{\"similarities\":[],\"differences\":[],\"narrative\":\"n\"}");

        cli.run(new String[] {"compare", "1"});
        assertTrue(stdout().contains("NOT COMPARABLE"), stdout());
        assertTrue(stdout().contains("ImageNet"), stdout());
    }

    @Test
    void compareSaysCannotDetermineWhenTheMetricsAreNotStated() {
        givenAnAnalysedPaper("1", "MIMIC-CXR", "");
        llm.respondWith("{\"similarities\":[],\"differences\":[],\"narrative\":\"n\"}");

        cli.run(new String[] {"compare", "1"});
        assertTrue(stdout().contains("CANNOT DETERMINE"),
                "an unstated metric is not a mismatch: " + stdout());
    }

    @Test
    void compareKeepsTheVerdictWhenTheModelIsUnavailable() {
        givenAnAnalysedPaper("1", "ImageNet", "\"AUROC\"");
        llm.failWith("no key");

        assertEquals(0, cli.run(new String[] {"compare", "1"}), stderr());
        assertTrue(stdout().contains("NOT COMPARABLE"),
                "F07 is deterministic and does not need the model");
        assertTrue(stdout().contains("no narrative"), stdout());
    }

    @Test
    void compareRefusesAPaperThatHasNotBeenAnalysed() {
        givenAProfile();
        assertNotEquals(0, cli.run(new String[] {"compare", "9999"}));
        assertTrue(stderr().contains("analyze 9999"),
                "it should say exactly what to run first: " + stderr());
    }

    @Test
    void compareNeedsAPaperId() {
        assertNotEquals(0, cli.run(new String[] {"compare"}));
        assertTrue(stderr().contains("paper id"), stderr());
    }

    @Test
    void theUsageMentionsCompare() {
        cli.run(new String[] {"help"});
        assertTrue(stdout().contains("compare"), stdout());
    }
}
