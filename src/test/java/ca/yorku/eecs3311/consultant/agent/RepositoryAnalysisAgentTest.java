package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.RepoMetadata;
import ca.yorku.eecs3311.consultant.domain.RepoSummary;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import ca.yorku.eecs3311.consultant.repo.GitHubClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F09. What is checkable comes from the listing; only the judgement is asked for. */
class RepositoryAnalysisAgentTest {

    private static PaperAnalysis paperWith(String repoUrl) {
        PaperRecord record = new PaperRecord("1", "A paper", "arXiv", "2026-01-01");
        record.setRepoUrl(repoUrl);
        return new PaperAnalysis(record);
    }

    private static final String JUDGEMENT =
            "{\"readme_quality\":\"thorough\",\"note\":\"training and evaluation scripts "
                    + "are both present\"}";

    /** A ToolManager whose GitHub client returns a canned listing. */
    private static ToolManager toolsReturning(FakeLLMClient llm, String... topLevel) {
        ToolManager tools = new ToolManager(llm);
        tools.setGitHubClient(new GitHubClient(url -> {
            if (url.endsWith("/contents/")) {
                StringBuilder json = new StringBuilder("[");
                for (String name : topLevel) {
                    json.append("{\"name\":\"").append(name).append("\"},");
                }
                if (topLevel.length > 0) {
                    json.setLength(json.length() - 1);
                }
                return json.append("]").toString();
            }
            return "{\"license\":{\"spdx_id\":\"MIT\"}}";
        }));
        return tools;
    }

    // --- the deterministic half ----------------------------------------------

    @Test
    void codeDataAndEvaluationAreDerivedFromTheListing() throws Exception {
        ToolManager tools = toolsReturning(new FakeLLMClient().respondWith(JUDGEMENT),
                "README.md", "train.py", "evaluate.py", "data", "LICENSE");

        RepoSummary s = new RepositoryAnalysisAgent(tools)
                .checkRepository(paperWith("https://github.com/example/xrag"));

        assertTrue(s.isCodeAvailable());
        assertTrue(s.isHasDatasets());
        assertTrue(s.isHasEvalScripts());
        assertEquals("MIT", s.getLicense());
    }

    @Test
    void aRepositoryWithNoCodeIsNotClaimedToHaveSome() throws Exception {
        ToolManager tools = toolsReturning(new FakeLLMClient().respondWith(JUDGEMENT),
                "README.md", "paper.pdf", "figures");

        RepoSummary s = new RepositoryAnalysisAgent(tools)
                .checkRepository(paperWith("https://github.com/example/papers-only"));

        assertFalse(s.isCodeAvailable());
        assertFalse(s.isHasEvalScripts());
    }

    @Test
    void aSourceDirectoryCountsAsCodeEvenWithNoTopLevelScripts() {
        RepoMetadata m = new RepoMetadata("https://github.com/example/x");
        m.setTopLevelFiles(List.of("README.md", "src", "pom.xml"));
        assertTrue(RepositoryAnalysisAgent.hasCode(m));
    }

    @Test
    void aWordLikeTestInAProseFileIsNotAnEvaluationScript() {
        // "testing-protocol.md" is documentation, not something you can run
        RepoMetadata m = new RepoMetadata("https://github.com/example/x");
        m.setTopLevelFiles(List.of("testing-protocol.md", "README.md"));
        assertFalse(RepositoryAnalysisAgent.hasEvaluation(m));
    }

    @Test
    void anEvaluationDirectoryCounts() {
        RepoMetadata m = new RepoMetadata("https://github.com/example/x");
        m.setTopLevelFiles(List.of("benchmarks", "README.md"));
        assertTrue(RepositoryAnalysisAgent.hasEvaluation(m));
    }

    @Test
    void aCsvAtTheTopLevelCountsAsData() {
        RepoMetadata m = new RepoMetadata("https://github.com/example/x");
        m.setTopLevelFiles(List.of("results.csv"));
        assertTrue(RepositoryAnalysisAgent.hasData(m));
    }

    @Test
    void reproducibleRequiresCodeAndDataAndEvaluation() throws Exception {
        // all three: code you can run, data to run it on, and a way to score it
        ToolManager all = toolsReturning(new FakeLLMClient().respondWith(JUDGEMENT),
                "train.py", "evaluate.py", "data");
        assertTrue(new RepositoryAnalysisAgent(all)
                .checkRepository(paperWith("https://github.com/e/x")).isReproducible());

        ToolManager noData = toolsReturning(new FakeLLMClient().respondWith(JUDGEMENT),
                "train.py", "evaluate.py");
        assertFalse(new RepositoryAnalysisAgent(noData)
                .checkRepository(paperWith("https://github.com/e/y")).isReproducible(),
                "code and a scorer are not enough without data to run them on");

        ToolManager codeOnly = toolsReturning(new FakeLLMClient().respondWith(JUDGEMENT),
                "train.py");
        assertFalse(new RepositoryAnalysisAgent(codeOnly)
                .checkRepository(paperWith("https://github.com/e/z")).isReproducible());
    }

    // --- the judged half -----------------------------------------------------

    @Test
    void theReadmeRatingAndNoteComeFromTheModel() throws Exception {
        ToolManager tools = toolsReturning(new FakeLLMClient().respondWith(JUDGEMENT),
                "README.md", "train.py", "evaluate.py");

        RepoSummary s = new RepositoryAnalysisAgent(tools)
                .checkRepository(paperWith("https://github.com/example/xrag"));

        assertEquals("thorough", s.getReadmeQuality());
        assertTrue(s.getNote().contains("evaluation scripts"));
    }

    @Test
    void anInventedRatingIsReducedToUnknown() throws Exception {
        ToolManager tools = toolsReturning(
                new FakeLLMClient().respondWith("{\"readme_quality\":\"spectacular\"}"),
                "README.md", "train.py");

        assertEquals("unknown", new RepositoryAnalysisAgent(tools)
                .checkRepository(paperWith("https://github.com/e/x")).getReadmeQuality());
    }

    @Test
    void whenTheModelIsUnavailableTheFactsStillStand() throws Exception {
        ToolManager tools = toolsReturning(new FakeLLMClient().failWith("no key"),
                "README.md", "train.py", "evaluate.py", "data");

        RepoSummary s = new RepositoryAnalysisAgent(tools)
                .checkRepository(paperWith("https://github.com/e/x"));

        assertTrue(s.isCodeAvailable(), "derived from the listing, not from the model");
        assertTrue(s.isHasEvalScripts());
        assertEquals("unknown", s.getReadmeQuality(),
                "an unjudged README must never be recorded as good");
    }

    // --- the two failure paths UC08 names ------------------------------------

    @Test
    void aPaperWithNoRepositoryIsStatedPlainly() throws Exception {
        // UC08 2a
        ToolManager tools = toolsReturning(new FakeLLMClient().respondWith(JUDGEMENT));

        RepoSummary s = new RepositoryAnalysisAgent(tools).checkRepository(paperWith(""));

        assertFalse(s.isCodeAvailable());
        assertTrue(s.getNote().contains("no repository linked"), s.getNote());
    }

    @Test
    void anInaccessibleRepositoryReportsTheReason() throws Exception {
        // UC08 3a
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith(JUDGEMENT));
        tools.setGitHubClient(new GitHubClient(url -> null));

        RepoSummary s = new RepositoryAnalysisAgent(tools)
                .checkRepository(paperWith("https://github.com/example/gone"));

        assertFalse(s.isCodeAvailable());
        assertTrue(s.getNote().contains("private") || s.getNote().contains("not found"),
                s.getNote());
    }

    @Test
    void refusesInputThatIsNotAnAnalysis() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        assertThrows(AgentException.class,
                () -> new RepositoryAnalysisAgent(tools).run("a string"));
    }

    @Test
    void theToolManagerRejectsANullGitHubClient() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        assertThrows(IllegalArgumentException.class, () -> tools.setGitHubClient(null));
    }
}
