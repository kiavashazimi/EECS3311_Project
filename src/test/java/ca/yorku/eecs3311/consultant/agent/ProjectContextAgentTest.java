package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.context.ClaudeCodeContextProvider;
import ca.yorku.eecs3311.consultant.context.FileBasedContextProvider;
import ca.yorku.eecs3311.consultant.context.ProjectContextProvider;
import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** F02 and F10, including the runtime provider choice the diagram cannot show. */
class ProjectContextAgentTest {

    @TempDir
    Path tempDir;

    private static Path fixture(String name) {
        return Path.of("src/test/resources/fixtures", name);
    }

    private static ProjectContextAgent agent(FakeLLMClient llm) {
        return new ProjectContextAgent(new ToolManager(llm),
                List.of(new ClaudeCodeContextProvider(), new FileBasedContextProvider()));
    }

    private static ProjectContextAgent.ImportedContext importFrom(
            ProjectContextAgent agent, Path path) throws AgentException {
        return (ProjectContextAgent.ImportedContext)
                agent.run(new ProjectContextAgent.Import(path, null));
    }

    // --- provider selection (UC02 3a / 3b) ----------------------------------

    @Test
    void theCodingAgentsOwnDescriptionIsUsedWhenItHasSomethingToSay() throws Exception {
        ProjectContextAgent agent = agent(new FakeLLMClient().respondWith("a summary"));

        var imported = importFrom(agent, fixture("agent-project"));

        assertTrue(imported.snapshot().getAgentDescription().contains("HVAC"));
        assertTrue(imported.snapshot().getProviderName().contains("ClaudeCodeContextProvider"),
                "the UI must still be able to say where the description came from: "
                        + imported.snapshot().getProviderName());
    }

    @Test
    void everyAvailableProviderContributesToOneSnapshot() throws Exception {
        // F02's Stage 1 contract is facts *plus* the description. First-wins
        // could only ever deliver one of the two.
        Path project = tempDir.resolve("both");
        Files.createDirectories(project);
        Files.writeString(project.resolve("CLAUDE.md"),
                "# CLAUDE.md\nRewriting the scheduler; the old one is deliberately broken.\n");
        Files.writeString(project.resolve("run.yaml"), "model: densenet\nlr: 0.0001\n");
        Files.writeString(project.resolve("metrics.csv"), "run,auc\n1,0.9\n");

        var imported = importFrom(agent(new FakeLLMClient().respondWith("a summary")), project);
        ContextSnapshot s = imported.snapshot();

        assertTrue(s.getAgentDescription().contains("scheduler"),
                "the intent half must survive");
        assertTrue(s.getConfigEntries().containsKey("run.yaml"),
                "the facts half must survive: " + s.getConfigEntries());
        assertTrue(s.getResultFiles().contains("metrics.csv"),
                "the facts half must survive: " + s.getResultFiles());
        assertTrue(s.getProviderName().contains("ClaudeCodeContextProvider")
                        && s.getProviderName().contains("FileBasedContextProvider"),
                "both contributors must be named: " + s.getProviderName());
    }

    @Test
    void addingACodingAgentDescriptionDoesNotReportTheFactsAsRemoved() throws Exception {
        // The defect this merge fixes: a CLAUDE.md appearing between two scans
        // switched providers, so files still on disk were reported as removed
        // and the stored facts were replaced with a description-only snapshot.
        Path project = tempDir.resolve("gains-a-description");
        Files.createDirectories(project);
        Files.writeString(project.resolve("run.yaml"), "model: densenet\nlr: 0.0001\n");
        Files.writeString(project.resolve("metrics.csv"), "run,auc\n1,0.9\n");

        ProjectContextAgent agent = agent(new FakeLLMClient().respondWith("a summary"));
        ContextSnapshot before = importFrom(agent, project).snapshot();

        Files.writeString(project.resolve("CLAUDE.md"), "# CLAUDE.md\nMid-refactor.\n");
        ContextSnapshot after = ((ProjectContextAgent.ImportedContext)
                agent.run(new ProjectContextAgent.Import(project, before))).snapshot();

        assertEquals(List.of(), after.changesSince(before),
                "nothing was added or deleted, so nothing may be reported");
        assertTrue(after.getResultFiles().contains("metrics.csv"),
                "a file still on disk must not vanish from the snapshot");
        assertFalse(after.getAgentDescription().isBlank(),
                "and the new description must still be picked up");
    }

    @Test
    void whenEveryAvailableProviderFailsTheImportIsReported() {
        ProjectContextProvider broken = new ProjectContextProvider() {
            @Override
            public boolean isAvailable(Path projectPath) {
                return true;
            }

            @Override
            public ContextSnapshot scan(Path projectPath) throws IOException {
                throw new IOException("the coding agent timed out");
            }

            @Override
            public String name() {
                return "BrokenProvider";
            }
        };

        ProjectContextAgent agent = new ProjectContextAgent(
                new ToolManager(new FakeLLMClient()), List.of(broken));

        AgentException thrown = assertThrows(AgentException.class,
                () -> importFrom(agent, fixture("plain-project")));
        assertTrue(thrown.getMessage().contains("timed out"), thrown.getMessage());
    }

    @Test
    void fallsBackToFilesWhenNoCodingAgentContextExists() throws Exception {
        ProjectContextAgent agent = agent(new FakeLLMClient().respondWith("a summary"));

        var imported = importFrom(agent, fixture("plain-project"));

        assertEquals("FileBasedContextProvider", imported.snapshot().getProviderName(),
                "the fallback must fire, and the UI must be able to say so");
    }

    @Test
    void selectProviderNamesTheOneThatWillBeUsed() {
        ProjectContextAgent agent = agent(new FakeLLMClient());

        assertEquals("ClaudeCodeContextProvider",
                agent.selectProvider(fixture("agent-project")).name());
        assertEquals("FileBasedContextProvider",
                agent.selectProvider(fixture("plain-project")).name());
    }

    @Test
    void aProviderThatIsAvailableButFailsFallsThroughToTheNext() throws Exception {
        // UC02 3b: a failing stretch-goal integration must never block an import
        ProjectContextProvider brokenButAvailable = new ProjectContextProvider() {
            @Override
            public boolean isAvailable(Path projectPath) {
                return true;
            }

            @Override
            public ContextSnapshot scan(Path projectPath) throws IOException {
                throw new IOException("the coding agent timed out");
            }

            @Override
            public String name() {
                return "BrokenProvider";
            }
        };

        ProjectContextAgent agent = new ProjectContextAgent(
                new ToolManager(new FakeLLMClient().respondWith("a summary")),
                List.of(brokenButAvailable, new FileBasedContextProvider()));

        var imported = importFrom(agent, fixture("plain-project"));

        assertEquals("FileBasedContextProvider", imported.snapshot().getProviderName());
    }

    @Test
    void anUnreadablePathIsReportedAndNothingIsStored() {
        ProjectContextAgent agent = agent(new FakeLLMClient());
        Path missing = tempDir.resolve("absent");

        AgentException e = assertThrows(AgentException.class,
                () -> importFrom(agent, missing));
        assertTrue(e.getMessage().contains("absent"), e.getMessage());
    }

    @Test
    void refusesInputThatIsNotAnImport() {
        assertThrows(AgentException.class, () -> agent(new FakeLLMClient()).run("a string"));
    }

    @Test
    void requiresAtLeastOneProvider() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectContextAgent(tools, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectContextAgent(tools, null));
    }

    // --- the summary ---------------------------------------------------------

    @Test
    void theProjectsOwnDescriptionIsPutInFrontOfTheModel() throws Exception {
        FakeLLMClient llm = new FakeLLMClient().respondWith("a summary");
        importFrom(agent(llm), fixture("agent-project"));

        String prompt = llm.promptsSeen().get(0);
        assertTrue(prompt.contains("HVAC"),
                "the agent's own description is the most valuable input: " + prompt);
    }

    @Test
    void whenTheModelIsUnavailableTheFactsStillStand() throws Exception {
        // UC02 5a
        ProjectContextAgent agent = agent(new FakeLLMClient().failWith("no key"));

        var imported = importFrom(agent, fixture("agent-project"));

        assertTrue(imported.isSummaryMissing(), "the UI must be able to offer a retry");
        assertFalse(imported.snapshot().getAgentDescription().isBlank(),
                "the snapshot is stored regardless");
    }

    // --- F10: what changed ---------------------------------------------------

    @Test
    void changesBetweenTwoSnapshotsAreComputedWithoutTheModel() {
        ProjectContextAgent agent = agent(new FakeLLMClient().failWith("no key"));

        ContextSnapshot before = new ContextSnapshot("2026-11-01T10:00", "/p");
        before.setRecentCommits(List.of("aaa first"));
        ContextSnapshot after = new ContextSnapshot("2026-11-03T10:00", "/p");
        after.setRecentCommits(List.of("bbb second", "aaa first"));

        List<String> changes = agent.changesBetween(before, after);

        assertFalse(changes.isEmpty(), "a new commit is a change");
        assertTrue(String.join(" ", changes).contains("bbb second"), changes.toString());
    }

    @Test
    void noChangesIsStatedExplicitlyRatherThanLeftBlank() {
        // UC09 4a
        ProjectContextAgent agent = agent(new FakeLLMClient().respondWith("unused"));
        ContextSnapshot same = new ContextSnapshot("2026-11-01T10:00", "/p");

        String summary = agent.summarizeChanges(same, same);

        assertTrue(summary.contains("No changes since 2026-11-01T10:00"), summary);
    }

    @Test
    void anUnavailableModelCostsOnlyTheProseNotTheDiff() {
        ProjectContextAgent agent = agent(new FakeLLMClient().failWith("no key"));

        ContextSnapshot before = new ContextSnapshot("2026-11-01T10:00", "/p");
        ContextSnapshot after = new ContextSnapshot("2026-11-03T10:00", "/p");
        after.setResultFiles(List.of("results.csv"));

        assertTrue(agent.summarizeChanges(before, after).isEmpty());
        assertFalse(agent.changesBetween(before, after).isEmpty(),
                "the diff is deterministic and must survive");
    }

    @Test
    void theDiffRequiresBothSnapshots() {
        ProjectContextAgent agent = agent(new FakeLLMClient());
        ContextSnapshot s = new ContextSnapshot("2026-11-01T10:00", "/p");
        assertThrows(IllegalArgumentException.class, () -> agent.changesBetween(null, s));
        assertThrows(IllegalArgumentException.class, () -> agent.changesBetween(s, null));
    }

    @Test
    void importingTwiceReportsWhatChangedBetweenTheTwoScans() throws Exception {
        Path project = tempDir.resolve("evolving");
        Files.createDirectories(project);
        Files.writeString(project.resolve("run.yaml"), "model: a\n");

        FakeLLMClient llm = new FakeLLMClient().respondWith("a summary");
        ProjectContextAgent agent = new ProjectContextAgent(new ToolManager(llm),
                List.of(new FileBasedContextProvider()));

        ContextSnapshot first = ((ProjectContextAgent.ImportedContext)
                agent.run(new ProjectContextAgent.Import(project, null))).snapshot();

        Files.writeString(project.resolve("results.csv"), "run,auc\n1,0.9\n");
        agent.run(new ProjectContextAgent.Import(project, first));

        String secondPrompt = llm.promptsSeen().get(1);
        assertTrue(secondPrompt.contains("Changes since"), secondPrompt);
        assertTrue(secondPrompt.contains("results.csv"), secondPrompt);
    }
}
