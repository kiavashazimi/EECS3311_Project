package ca.yorku.eecs3311.consultant.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.literature.FakeLiteratureSource;
import ca.yorku.eecs3311.consultant.repo.GitHubClient;
import ca.yorku.eecs3311.consultant.controller.AppController;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import ca.yorku.eecs3311.consultant.memory.MemoryStore;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private AppController controller;
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
        controller = new AppController(ProjectMemory.getInstance(),
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
    void aProfileWithNoQuestionFailsAndNamesTheMissingField() {
        // this used to assert the same for a question with no datasets or
        // metrics; those are recommended rather than required now, so the
        // test moved to the field that still blocks a save
        int code = cli.run(new String[] {
            "profile", "set", "--datasets", "CheXpert", "--metrics", "AUC",
        });

        assertNotEquals(0, code);
        assertTrue(stderr().contains("researchQuestion"), stderr());
    }

    @Test
    void aProfileWithNoQuestionChangesNothing() {
        // SD01: a validation failure persists nothing at all
        cli.run(new String[] {
            "profile", "set", "--datasets", "CheXpert", "--metrics", "AUC",
        });

        assertTrue(ProjectMemory.getInstance().getProfile().getResearchQuestion().isBlank());
        assertTrue(ProjectMemory.getInstance().getProfile().getDatasets().isEmpty(),
                "a rejected save must not leak the fields that were filled in");
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

    // --- import / changes ----------------------------------------------------

    private Path aProjectWith(String configBody) throws Exception {
        Path project = tempDir.resolve("proj-" + configBody.hashCode());
        Files.createDirectories(project);
        Files.writeString(project.resolve("run.yaml"), configBody);
        return project;
    }

    @Test
    void importReadsADirectoryAndSaysWhichProviderAnswered() throws Exception {
        givenAProfile();
        llm.respondWith("a readable summary");

        Path project = aProjectWith("model: tcn\nseed: 20\n");
        assertEquals(0, cli.run(new String[] {"import", project.toString()}), stderr());

        assertTrue(stdout().contains("FileBasedContextProvider"),
                "the researcher must be told where the facts came from: " + stdout());
        assertTrue(stdout().contains("run.yaml"), stdout());
        assertTrue(stdout().contains("a readable summary"), stdout());
    }

    @Test
    void importPrefersTheCodingAgentWhenCLAUDEmdIsPresent() throws Exception {
        givenAProfile();
        llm.respondWith("a readable summary");

        Path project = aProjectWith("model: tcn\n");
        Files.writeString(project.resolve("CLAUDE.md"), "# CLAUDE.md\n\nAbout this study.");

        cli.run(new String[] {"import", project.toString()});
        assertTrue(stdout().contains("ClaudeCodeContextProvider"), stdout());
        assertTrue(stdout().contains("About this study"), stdout());
    }

    @Test
    void importKeepsTheFactsWhenTheModelIsUnavailable() throws Exception {
        givenAProfile();
        llm.failWith("no key");

        Path project = aProjectWith("model: tcn\nseed: 20\n");
        assertEquals(0, cli.run(new String[] {"import", project.toString()}), stderr());

        assertTrue(stdout().contains("run.yaml"), "the facts stand: " + stdout());
        assertTrue(stdout().contains("no summary"), stdout());
    }

    @Test
    void importReportsAnUnreadableDirectoryAndStoresNothing() {
        givenAProfile();
        assertNotEquals(0, cli.run(new String[] {"import", "/definitely/not/here"}));
        assertTrue(stderr().contains("/definitely/not/here"), stderr());
    }

    @Test
    void importNeedsADirectory() {
        assertNotEquals(0, cli.run(new String[] {"import"}));
        assertTrue(stderr().contains("directory"), stderr());
    }

    @Test
    void changesWithoutAPriorImportPointsAtTheImport() {
        // UC09 2a: not an empty panel
        givenAProfile();
        assertNotEquals(0, cli.run(new String[] {"changes"}));
        assertTrue(stderr().contains("import"), stderr());
    }

    @Test
    void changesListsWhatChangedAndSaysWhat() throws Exception {
        givenAProfile();
        llm.respondWith("a summary");
        Path project = aProjectWith("model: tcn\nseed: 20\n");
        cli.run(new String[] {"import", project.toString()});
        out.reset();

        Files.writeString(project.resolve("run.yaml"), "model: tcn\nseed: 20\ndropout: 0.3\n");
        Files.writeString(project.resolve("results.csv"), "auc\n0.9\n");

        assertEquals(0, cli.run(new String[] {"changes"}), stderr());
        String printed = stdout();
        assertTrue(printed.contains("dropout"),
                "the diff must say what changed, not just that it did: " + printed);
        assertTrue(printed.contains("results.csv"), printed);
    }

    @Test
    void noChangesIsStatedExplicitly() throws Exception {
        givenAProfile();
        llm.respondWith("a summary");
        Path project = aProjectWith("model: tcn\nseed: 20\n");
        cli.run(new String[] {"import", project.toString()});
        out.reset();

        assertEquals(0, cli.run(new String[] {"changes"}));
        assertTrue(stdout().contains("No changes since"), stdout());
    }

    @Test
    void theUsageMentionsImportAndChanges() {
        cli.run(new String[] {"help"});
        assertTrue(stdout().contains("import"), stdout());
        assertTrue(stdout().contains("changes"), stdout());
    }

    // --- landscape -----------------------------------------------------------

    private void givenAnalysedPapers(String... tagPerPaper) {
        givenAProfile();
        for (int i = 0; i < tagPerPaper.length; i++) {
            String id = "p" + i;
            source.returning(paper(id, "Paper " + i));
            llm.respondWith("{\"problem\":\"p\",\"method\":\"m\",\"dataset\":\"MIMIC-CXR\","
                    + "\"model\":\"m\",\"metrics\":[\"AUROC\"],\"results\":\"r\","
                    + "\"limitations\":\"l\",\"strategy_tags\":[\"" + tagPerPaper[i] + "\"]}");
            cli.run(new String[] {"analyze", id});
        }
        out.reset();
        err.reset();
    }

    @Test
    void landscapeGroupsPapersUnderSeededFamilies() {
        givenAnalysedPapers("diffusion", "resampling", "data augmentation");
        llm.respondWith("{\"Generative approaches\":\"they synthesise examples\","
                + "\"Data-level approaches\":\"they change the training data\"}");

        assertEquals(0, cli.run(new String[] {"landscape"}), stderr());

        String printed = stdout();
        assertTrue(printed.contains("Generative approaches"), printed);
        assertTrue(printed.contains("Data-level approaches"), printed);
        assertTrue(printed.contains("they synthesise examples"), printed);
        assertTrue(printed.contains("3 analysed paper"), printed);
    }

    @Test
    void landscapeSaysSoWhenNothingHasBeenAnalysed() {
        givenAProfile();
        assertEquals(0, cli.run(new String[] {"landscape"}));
        assertTrue(stdout().toLowerCase().contains("nothing to map"), stdout());
    }

    @Test
    void landscapeStatesWhenThereAreTooFewPapersForSummaries() {
        givenAnalysedPapers("diffusion");
        assertEquals(0, cli.run(new String[] {"landscape"}));
        assertTrue(stdout().contains("too few papers"),
                "UC12 3a: say it rather than leave the gap unexplained: " + stdout());
    }

    @Test
    void landscapeMarksTheResearchersOwnBranch() {
        // the profile's models mention a diffusion model
        cli.run(new String[] {
            "profile", "set", "--question", "do synthetic X-rays help?",
            "--datasets", "MIMIC-CXR", "--metrics", "AUROC",
            "--models", "conditional diffusion model",
        });
        for (int i = 0; i < 3; i++) {
            source.returning(paper("q" + i, "Paper " + i));
            llm.respondWith("{\"method\":\"m\",\"dataset\":\"MIMIC-CXR\","
                    + "\"metrics\":[\"AUROC\"],\"strategy_tags\":[\"diffusion\"]}");
            cli.run(new String[] {"analyze", "q" + i});
        }
        out.reset();
        llm.respondWith("{}");

        cli.run(new String[] {"landscape"});
        assertTrue(stdout().contains("your approach"), stdout());
    }

    @Test
    void landscapeKeepsTheTreeWhenTheModelIsUnavailable() {
        givenAnalysedPapers("diffusion", "resampling", "data augmentation");
        llm.failWith("no key");

        assertEquals(0, cli.run(new String[] {"landscape"}));
        assertTrue(stdout().contains("Generative approaches"),
                "the structure is deterministic: " + stdout());
    }

    @Test
    void theUsageMentionsLandscape() {
        cli.run(new String[] {"help"});
        assertTrue(stdout().contains("landscape"), stdout());
    }

    // --- repo ----------------------------------------------------------------

    /** Points the tool manager's GitHub client at a canned listing. */
    private void givenARepositoryListing(String... entries) {
        StringBuilder contents = new StringBuilder("[");
        for (String name : entries) {
            contents.append("{\"name\":\"").append(name).append("\"},");
        }
        if (entries.length > 0) {
            contents.setLength(contents.length() - 1);
        }
        contents.append("]");
        tools.setGitHubClient(new GitHubClient(url -> url.endsWith("/contents/")
                ? contents.toString()
                : "{\"license\":{\"spdx_id\":\"MIT\"}}"));
    }

    private void givenAnAnalysedPaperWithRepo(String id, String repoUrl) {
        givenAProfile();
        PaperRecord record = paper(id, "A paper with code");
        record.setRepoUrl(repoUrl);
        source.returning(record);
        llm.respondWith("{\"method\":\"m\",\"dataset\":\"MIMIC-CXR\","
                + "\"metrics\":[\"AUROC\"],\"strategy_tags\":[\"diffusion\"]}");
        cli.run(new String[] {"analyze", id});
        out.reset();
        err.reset();
    }

    @Test
    void repoReportsWhatTheListingShows() {
        givenAnAnalysedPaperWithRepo("1", "https://github.com/example/xrag");
        givenARepositoryListing("README.md", "train.py", "evaluate.py", "data");
        llm.respondWith("{\"readme_quality\":\"adequate\",\"note\":\"scripts are present\"}");

        assertEquals(0, cli.run(new String[] {"repo", "1"}), stderr());

        String printed = stdout();
        assertTrue(printed.contains("code         present"), printed);
        assertTrue(printed.contains("evaluation   scripts present"), printed);
        assertTrue(printed.contains("MIT"), printed);
        assertTrue(printed.contains("plausibly"), "code, data and evaluation are all there");
    }

    @Test
    void repoSaysSoWhenTheListingShowsNoCode() {
        givenAnAnalysedPaperWithRepo("1", "https://github.com/example/papers");
        givenARepositoryListing("README.md", "paper.pdf");
        llm.respondWith("{\"readme_quality\":\"minimal\",\"note\":\"documents only\"}");

        cli.run(new String[] {"repo", "1"});
        assertTrue(stdout().contains("code         not found"), stdout());
        assertTrue(stdout().contains("not from this listing alone"), stdout());
    }

    @Test
    void repoStatesPlainlyWhenThePaperLinksNoRepository() {
        // UC08 2a
        givenAnAnalysedPaperWithRepo("1", "");

        assertEquals(0, cli.run(new String[] {"repo", "1"}), stderr());
        assertTrue(stdout().contains("no repository linked"), stdout());
    }

    @Test
    void repoReportsTheReasonWhenTheRepositoryCannotBeRead() {
        // UC08 3a
        givenAnAnalysedPaperWithRepo("1", "https://github.com/example/gone");
        tools.setGitHubClient(new GitHubClient(url -> null));
        llm.respondWith("{}");

        assertEquals(0, cli.run(new String[] {"repo", "1"}), stderr());
        assertTrue(stdout().contains("private") || stdout().contains("not found"), stdout());
    }

    @Test
    void repoKeepsTheFactsWhenTheModelIsUnavailable() {
        givenAnAnalysedPaperWithRepo("1", "https://github.com/example/xrag");
        givenARepositoryListing("README.md", "train.py", "evaluate.py", "data");
        llm.failWith("no key");

        cli.run(new String[] {"repo", "1"});
        assertTrue(stdout().contains("code         present"),
                "derived from the listing, not the model: " + stdout());
        assertTrue(stdout().contains("README       unknown"),
                "an unjudged README must never read as good: " + stdout());
    }

    @Test
    void repoSaysNoCodeWasRun() {
        givenAnAnalysedPaperWithRepo("1", "https://github.com/example/xrag");
        givenARepositoryListing("README.md", "train.py");
        llm.respondWith("{\"readme_quality\":\"adequate\"}");

        cli.run(new String[] {"repo", "1"});
        assertTrue(stdout().contains("no code was downloaded or run"),
                "the researcher should be told: " + stdout());
    }

    @Test
    void repoRefusesAPaperThatHasNotBeenAnalysed() {
        givenAProfile();
        assertNotEquals(0, cli.run(new String[] {"repo", "9999"}));
        assertTrue(stderr().contains("analyze 9999"), stderr());
    }

    @Test
    void repoNeedsAPaperId() {
        assertNotEquals(0, cli.run(new String[] {"repo"}));
        assertTrue(stderr().contains("paper id"), stderr());
    }

    @Test
    void theUsageMentionsRepo() {
        cli.run(new String[] {"help"});
        assertTrue(stdout().contains("repo"), stdout());
    }

    // --- row numbers from the listing ---------------------------------------

    /**
     * Discovery that leaves a two-row listing behind, with ids shaped like real
     * arXiv ids so they cannot be confused with row numbers.
     */
    private void givenATwoRowListing() {
        givenAProfile();
        source.returning(paper("2304.09218v1", "Generative models improve fairness"),
                paper("2412.20374v2", "FairDiffusion"));
        llm.respondWith("{\"ranked\":[{\"id\":\"2304.09218v1\",\"score\":96,"
                + "\"why\":\"closest\"},{\"id\":\"2412.20374v2\",\"score\":93,"
                + "\"why\":\"fair diffusion\"}]}");
        cli.run(new String[] {"discover"});
        out.reset();
        err.reset();
    }

    @Test
    void analyzeAcceptsTheRowNumberShownByDiscover() {
        givenATwoRowListing();
        llm.respondWith(ANALYSIS_REPLY);

        assertEquals(0, cli.run(new String[] {"analyze", "2"}), stderr());

        assertTrue(source.queriesSeen().contains("id:2412.20374v2"),
                "row 2 must become the second listed id, not be sent as the id \"2\": "
                        + source.queriesSeen());
    }

    @Test
    void theRowNumbersMatchThePositionsThatWerePrinted() {
        givenATwoRowListing();
        llm.respondWith(ANALYSIS_REPLY);

        cli.run(new String[] {"analyze", "1"});

        assertTrue(source.queriesSeen().contains("id:2304.09218v1"),
                "numbering starts at 1, as the listing shows it: " + source.queriesSeen());
    }

    @Test
    void aRowNumberPastTheEndOfTheListingSaysHowManyThereAre() {
        givenATwoRowListing();

        assertNotEquals(0, cli.run(new String[] {"analyze", "7"}));
        String complaint = stderr();
        assertTrue(complaint.contains("2"), "it should say how long the listing is: " + complaint);
        assertFalse(source.queriesSeen().contains("id:7"),
                "an out-of-range row must not be sent to the literature source");
    }

    @Test
    void aNumberWithNoListingIsLookedUpAsAnIdAndThenExplained() {
        // it cannot be a row number when nothing has been numbered, so it is
        // tried as an id; only once that fails is the likely mistake named
        givenAProfile();
        source.returning();

        assertNotEquals(0, cli.run(new String[] {"analyze", "2"}));
        assertTrue(source.queriesSeen().contains("id:2"),
                "with no listing the number must be tried as an id: " + source.queriesSeen());
        assertTrue(stderr().contains("rc discover"),
                "the likely mistake should be named: " + stderr());
    }

    @Test
    void anIdThatHappensToBeDigitsIsStillTreatedAsAnId() {
        // ambiguity is resolved towards the id, because an id is what the user
        // literally typed; a row number is only a shorthand for one
        givenAProfile();
        source.returning(paper("2", "A paper whose id really is 2"));
        llm.respondWith("{\"ranked\":[{\"id\":\"2\",\"score\":50,\"why\":\"ok\"}]}");
        cli.run(new String[] {"discover"});
        out.reset();
        llm.respondWith(ANALYSIS_REPLY);

        assertEquals(0, cli.run(new String[] {"analyze", "2"}), stderr());

        assertTrue(source.queriesSeen().contains("id:2"),
                "the listed id wins over the row number: " + source.queriesSeen());
    }

    @Test
    void anUnusableReferenceNamesBothFormsThatAreAccepted() {
        givenATwoRowListing();

        assertNotEquals(0, cli.run(new String[] {"analyze", "99"}));
        String complaint = stderr();
        assertTrue(complaint.contains("rc discover"),
                "the message must say where the numbers come from: " + complaint);
    }

    @Test
    void compareAcceptsARowNumberToo() {
        givenATwoRowListing();
        source.returning(paper("2412.20374v2", "FairDiffusion"));
        llm.respondWith(ANALYSIS_REPLY);
        cli.run(new String[] {"analyze", "2"});
        out.reset();
        err.reset();

        // row 2 must resolve to the paper that was just analysed, so compare
        // finds it rather than reporting it unanalysed
        llm.respondWith("{\"comparable\":false,\"reasons\":[\"different data\"],"
                + "\"similarities\":[],\"differences\":[],\"summary\":\"n/a\"}");
        assertEquals(0, cli.run(new String[] {"compare", "2"}), stderr());
    }

    @Test
    void repoAcceptsARowNumberToo() {
        givenATwoRowListing();
        source.returning(paper("2412.20374v2", "FairDiffusion"));
        llm.respondWith(ANALYSIS_REPLY);
        cli.run(new String[] {"analyze", "2"});
        out.reset();
        err.reset();

        assertEquals(0, cli.run(new String[] {"repo", "2"}), stderr());
        assertFalse(stderr().contains("not been analysed"),
                "row 2 should resolve before the analysed check runs: " + stderr());
    }

    // --- F11 ask / F08 prior work -------------------------------------------

    /** Gives the CLI one analysed paper to retrieve against. */
    private void givenAnAnalysedPaper() {
        givenAProfile();
        source.returning(paper("2412.20374v2", "FairDiffusion on CheXpert"));
        llm.respondWith(ANALYSIS_REPLY);
        cli.run(new String[] {"analyze", "2412.20374v2"});
        out.reset();
        err.reset();
    }

    @Test
    void askAnswersFromStoredRecordsAndListsThem() {
        givenAnAnalysedPaper();
        llm.respondWith("FairDiffusion uses a diffusion model on MIMIC-CXR.");

        assertEquals(0, cli.run(new String[] {"ask", "what does FairDiffusion do?"}), stderr());

        String printed = stdout();
        assertTrue(printed.contains("FairDiffusion uses a diffusion model"), printed);
        assertTrue(printed.contains("Based on:"), printed);
        assertTrue(printed.contains("2412.20374v2"),
                "every answer must be traceable: " + printed);
    }

    @Test
    void askSaysNothingIsStoredRatherThanAnsweringAnyway() {
        givenAProfile();
        llm.respondWith("Quadrupeds are usually tuned with a central pattern generator.");

        assertEquals(0, cli.run(new String[] {"ask", "how do quadruped gait controllers work?"}));

        String printed = stdout().toLowerCase();
        assertTrue(printed.contains("nothing relevant"), stdout());
        assertFalse(printed.contains("central pattern generator"),
                "the model's own knowledge must not reach the researcher: " + stdout());
    }

    @Test
    void anUnquotedQuestionIsJoinedRatherThanTruncatedToItsFirstWord() {
        givenAnAnalysedPaper();
        llm.respondWith("an answer");

        assertEquals(0, cli.run(new String[] {
            "ask", "what", "does", "FairDiffusion", "do", "on", "CheXpert?"}), stderr());

        assertTrue(llm.promptsSeen().get(0).contains("FairDiffusion"),
                "the whole question must reach the agent: " + llm.promptsSeen().get(0));
    }

    @Test
    void askNeedsAQuestion() {
        assertNotEquals(0, cli.run(new String[] {"ask"}));
        assertTrue(stderr().contains("question"), stderr());
    }

    @Test
    void priorWorkReportsFindingNothingWithoutClaimingNobodyTriedIt() {
        givenAProfile();
        source.returning();

        assertEquals(0, cli.run(new String[] {"prior", "quantum annealing for radiographs"}));

        String printed = stdout().toLowerCase();
        assertTrue(printed.contains("no matching evidence"), stdout());
        assertFalse(printed.contains("no one has"), stdout());
    }

    @Test
    void priorWorkCitesBothAStoredPaperAndALiveOne() {
        givenAnAnalysedPaper();
        PaperRecord live = paper("2508.16783v1",
                "Conditioning Synthetic CheXpert Radiographs");
        live.setSummary("FairDiffusion style conditioning of synthetic CheXpert radiographs");
        source.returning(live);
        llm.respondWith("Two lines of work are close.");

        assertEquals(0, cli.run(new String[] {
            "prior", "FairDiffusion style conditioning on CheXpert radiographs"}), stderr());

        String printed = stdout();
        assertTrue(printed.contains("2508.16783v1"), "the live result: " + printed);
        assertTrue(printed.contains("2412.20374v2"), "the remembered result: " + printed);
    }

    @Test
    void priorWorkCarriesAConfidenceNoteRatherThanAVerdict() {
        givenAnAnalysedPaper();
        source.returning(paper("2508.16783v1", "Controllable Synthetic Radiographs"));
        llm.respondWith("Yes, definitely done already.");

        cli.run(new String[] {"prior", "FairDiffusion conditioning on CheXpert radiographs"});

        assertTrue(stdout().contains("not proof"),
                "the note qualifies the result: " + stdout());
    }

    @Test
    void helpListsBothNewCommands() {
        assertEquals(0, cli.run(new String[] {"help"}));
        assertTrue(stdout().contains("ask "), stdout());
        assertTrue(stdout().contains("prior "), stdout());
    }

    // --- F12 digest ----------------------------------------------------------

    @Test
    void digestSaysNothingNewRatherThanPrintingAnEmptyReport() {
        givenAProfile();

        assertEquals(0, cli.run(new String[] {"digest"}), stderr());

        String printed = stdout();
        assertTrue(printed.contains("Digest"), printed);
        assertTrue(printed.toLowerCase().contains("nothing new"),
                "UC11 3a: said explicitly, not shown as a blank screen: " + printed);
    }

    @Test
    void digestReportsThePapersDiscoveryPushedToIt() {
        givenAProfile();
        source.returning(paper("2412.20374v2", "FairDiffusion"), paper("1", "Another"));
        llm.respondWith("{\"ranked\":[{\"id\":\"2412.20374v2\",\"score\":90,"
                + "\"why\":\"close\"},{\"id\":\"1\",\"score\":40,\"why\":\"ok\"}]}");
        cli.run(new String[] {"discover"});
        out.reset();

        llm.respondWith("Two papers worth a look this week.");
        assertEquals(0, cli.run(new String[] {"digest"}), stderr());

        String printed = stdout();
        assertTrue(printed.contains("New papers (2)"), printed);
        assertTrue(printed.contains("FairDiffusion"), printed);
        assertTrue(printed.contains("Two papers worth a look"), printed);
    }

    @Test
    void theSecondDigestDoesNotRepeatTheFirstOnesPapers() {
        givenAProfile();
        source.returning(paper("2412.20374v2", "FairDiffusion"));
        llm.respondWith("{\"ranked\":[{\"id\":\"2412.20374v2\",\"score\":90,"
                + "\"why\":\"close\"}]}");
        cli.run(new String[] {"discover"});
        llm.respondWith("a summary");
        cli.run(new String[] {"digest"});
        out.reset();

        assertEquals(0, cli.run(new String[] {"digest"}));

        assertTrue(stdout().toLowerCase().contains("nothing new since"),
                "the papers were already reported: " + stdout());
    }

    @Test
    void digestShowsTheAssembledReportWhenTheModelIsUnavailable() {
        givenAProfile();
        source.returning(paper("2412.20374v2", "FairDiffusion"));
        llm.respondWith("{\"ranked\":[{\"id\":\"2412.20374v2\",\"score\":90,"
                + "\"why\":\"close\"}]}");
        cli.run(new String[] {"discover"});
        out.reset();

        llm.failWith("HTTP 529 overloaded");
        assertEquals(0, cli.run(new String[] {"digest"}), stderr());

        String printed = stdout();
        assertTrue(printed.contains("FairDiffusion"),
                "UC11 4a: the structured digest stands without the narrative: " + printed);
        assertTrue(printed.toLowerCase().contains("no summary"), printed);
    }

    @Test
    void digestListsTheApproachesOfPapersThatHaveBeenAnalysed() {
        givenAProfile();
        source.returning(paper("2412.20374v2", "FairDiffusion"));
        llm.respondWith("{\"ranked\":[{\"id\":\"2412.20374v2\",\"score\":90,"
                + "\"why\":\"close\"}]}");
        cli.run(new String[] {"discover"});
        llm.respondWith(ANALYSIS_REPLY);
        cli.run(new String[] {"analyze", "2412.20374v2"});
        out.reset();

        llm.respondWith("a summary");
        cli.run(new String[] {"digest"});

        assertTrue(stdout().contains("Approaches seen"), stdout());
        assertTrue(stdout().contains("diffusion model"), stdout());
    }

    @Test
    void helpListsTheDigestCommand() {
        assertEquals(0, cli.run(new String[] {"help"}));
        assertTrue(stdout().contains("digest"), stdout());
    }

    // --- telling two ranking failures apart ----------------------------------

    @Test
    void anUnreachableModelIsReportedAsUnavailable() {
        givenAProfile();
        source.returning(paper("1", "A paper"));
        llm.failWith("HTTP 529 overloaded");

        assertEquals(0, cli.run(new String[] {"discover"}));

        assertTrue(stdout().contains("the language model was unavailable"), stdout());
    }

    @Test
    void anUnreadableRankingIsNotReportedAsAnOutage() {
        // a reply truncated at the output-token limit parses as nothing. Saying
        // "unavailable" sent the reader looking for a service problem that was
        // not there - this is what a 2048-token cap actually did.
        givenAProfile();
        source.returning(paper("1", "A paper"));
        llm.respondWith("{\"ranked\":[{\"id\":\"1\",\"score\":90,\"why\":\"cut off mid-str");

        assertEquals(0, cli.run(new String[] {"discover"}));

        String printed = stdout();
        assertTrue(printed.contains("could not be read"), printed);
        assertFalse(printed.contains("unavailable"),
                "the model answered; its answer was unusable: " + printed);
    }

    // --- looking a paper up by id -------------------------------------------

    @Test
    void aPaperIsNeverAnalysedUnderAnIdThatIsNotItsOwn() {
        // the real failure: an id the source did not understand was read as
        // free text, an unrelated paper came back, and it was analysed and
        // stored as the paper that had been asked for
        givenAProfile();
        source.returning(paper("2506.01375", "Generative Next POI Recommendation"));
        llm.respondWith(ANALYSIS_REPLY);

        assertNotEquals(0, cli.run(new String[] {
            "analyze", "534f4abde70ca89412e269c14ac6f3c1a6502478"}));

        assertTrue(stderr().contains("no paper found"), stderr());
        assertTrue(controller.getAnalyses().isEmpty(),
                "nothing may be stored when the wrong paper came back");
    }

    @Test
    void theRightPaperIsStillFoundAmongSeveral() {
        givenAProfile();
        source.returning(paper("1", "Something else"),
                paper("2412.20374v2", "FairDiffusion"),
                paper("9", "Another thing"));
        llm.respondWith(ANALYSIS_REPLY);

        assertEquals(0, cli.run(new String[] {"analyze", "2412.20374v2"}), stderr());

        assertEquals("2412.20374v2", controller.getAnalyses().get(0).getPaper().getId());
    }

    // --- starting a new project ----------------------------------------------

    @Test
    void resetWithoutConfirmationChangesNothingAndSaysWhatWouldBeLost() {
        cli.run(new String[] {
            "profile", "set", "--question", "a real question",
            "--datasets", "CheXpert", "--metrics", "AUC",
        });
        out.reset();

        int code = cli.run(new String[] {"reset"});

        assertNotEquals(0, code, "a destructive command must not succeed by default");
        assertEquals("a real question",
                ProjectMemory.getInstance().getProfile().getResearchQuestion(),
                "nothing may be discarded without --confirm");
        assertTrue(stderr().contains("--confirm"), stderr());
    }

    @Test
    void resetWithConfirmationClearsTheProject() {
        cli.run(new String[] {
            "profile", "set", "--question", "a real question",
            "--datasets", "CheXpert", "--metrics", "AUC",
        });
        out.reset();

        assertEquals(0, cli.run(new String[] {"reset", "--confirm"}), stderr());
        assertTrue(ProjectMemory.getInstance().getProfile().getResearchQuestion().isBlank());
        assertTrue(ProjectMemory.getInstance().getPaperAnalyses().isEmpty());
    }

    @Test
    void aProfileWithNoDatasetsSavesAndTheMissingFieldsAreNamed() {
        int code = cli.run(new String[] {
            "profile", "set", "--question", "does concept mapping aid recall?",
        });

        assertEquals(0, code, stderr());
        assertEquals("does concept mapping aid recall?",
                ProjectMemory.getInstance().getProfile().getResearchQuestion(),
                "a question with no datasets must still be savable");
        assertTrue(stdout().contains("datasets") && stdout().contains("metrics"),
                "the output must name what was left out: " + stdout());
    }
}
