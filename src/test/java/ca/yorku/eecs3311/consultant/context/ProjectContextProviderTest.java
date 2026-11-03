package ca.yorku.eecs3311.consultant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Both adapters, driven by the committed fixtures under
 * {@code src/test/resources/fixtures} — fictional projects, because a test must
 * not depend on a real one that may live on another machine or hold unpublished
 * work.
 */
class ProjectContextProviderTest {

    @TempDir
    Path tempDir;

    private static Path fixture(String name) {
        return Path.of("src/test/resources/fixtures", name);
    }

    private final ClaudeCodeContextProvider agentProvider = new ClaudeCodeContextProvider();
    private final FileBasedContextProvider fileProvider = new FileBasedContextProvider();

    // --- the probe is what makes the choice real ----------------------------

    @Test
    void theAgentProviderIsAvailableOnlyWhereAgentContextExists() {
        assertTrue(agentProvider.isAvailable(fixture("agent-project")));
        assertTrue(agentProvider.isAvailable(fixture("thin-agent-project")));
        assertFalse(agentProvider.isAvailable(fixture("plain-project")),
                "without this the fallback would never be exercised");
    }

    @Test
    void theFileProviderIsAvailableForAnyReadableDirectory() {
        // it is the last resort, so it must say yes wherever it can read
        assertTrue(fileProvider.isAvailable(fixture("plain-project")));
        assertTrue(fileProvider.isAvailable(fixture("agent-project")));
    }

    @Test
    void neitherProviderIsAvailableForSomethingThatIsNotADirectory() {
        Path missing = tempDir.resolve("nope");
        assertFalse(agentProvider.isAvailable(missing));
        assertFalse(fileProvider.isAvailable(missing));
        assertFalse(agentProvider.isAvailable(null));
        assertFalse(fileProvider.isAvailable(null));
    }

    // --- the coding-agent adapter -------------------------------------------

    @Test
    void theAgentProviderReadsTheProjectsOwnDescription() throws IOException {
        ContextSnapshot s = agentProvider.scan(fixture("agent-project"));

        assertEquals("ClaudeCodeContextProvider", s.getProviderName(),
                "the snapshot must say where its facts came from");
        assertTrue(s.getAgentDescription().contains("Research question"),
                "the description is the point of this provider");
        assertTrue(s.getAgentDescription().contains("HVAC"));
        assertFalse(s.getScannedAt().isBlank());
    }

    @Test
    void aThinDescriptionIsReturnedAsItIsRatherThanEmbellished() {
        // "CLAUDE.md exists" does not mean "CLAUDE.md is useful"
        ContextSnapshot s = assertDoesNotThrowScan(agentProvider, fixture("thin-agent-project"));
        assertTrue(s.getAgentDescription().contains("make test"));
        assertFalse(s.getAgentDescription().contains("Research question"));
    }

    @Test
    void theAgentProviderRefusesADirectoryWithNoAgentContext() {
        assertThrows(IOException.class, () -> agentProvider.scan(fixture("plain-project")));
    }

    @Test
    void aVeryLongDescriptionIsTruncated() throws IOException {
        Path project = tempDir.resolve("huge");
        Files.createDirectories(project);
        Files.writeString(project.resolve("CLAUDE.md"), "x".repeat(20_000));

        ContextSnapshot s = agentProvider.scan(project);

        assertTrue(s.getAgentDescription().length()
                        <= ClaudeCodeContextProvider.MAX_DESCRIPTION_CHARS + 20,
                "a huge file would crowd everything else out of the prompt");
        assertTrue(s.getAgentDescription().endsWith("[truncated]"));
    }

    // --- the file-based adapter ---------------------------------------------

    @Test
    void theFileProviderFindsConfigAndResultFiles() throws IOException {
        Path project = tempDir.resolve("study");
        Files.createDirectories(project);
        Files.writeString(project.resolve("baseline.yaml"), "# a comment\nmodel: tcn\nseed: 20\n");
        Files.writeString(project.resolve("results.csv"), "run,auc\n1,0.84\n");
        Files.writeString(project.resolve("train.py"), "print('hi')\n");

        ContextSnapshot s = fileProvider.scan(project);

        assertEquals("FileBasedContextProvider", s.getProviderName());
        assertTrue(s.getConfigEntries().containsKey("baseline.yaml"));
        assertTrue(s.getConfigEntries().get("baseline.yaml").contains("model"),
                "a config is described by its keys: " + s.getConfigEntries());
        assertTrue(s.getConfigEntries().get("baseline.yaml").contains("seed"),
                s.getConfigEntries().toString());
        assertTrue(s.getResultFiles().contains("results.csv"));
        assertFalse(s.getResultFiles().contains("train.py"), "source is not a result");
    }

    @Test
    void aProjectWithNoGitRepositoryStillImports() throws IOException {
        // UC02 4a: Git-derived facts are unavailable, the scan continues
        Path project = tempDir.resolve("nogit");
        Files.createDirectories(project);
        Files.writeString(project.resolve("notes.txt"), "no repo here");

        ContextSnapshot s = fileProvider.scan(project);

        assertTrue(s.getRecentCommits().isEmpty());
        assertFalse(s.getProjectPath().isBlank(), "the import must still have succeeded");
    }

    @Test
    void anUnreadableDirectoryIsReportedAndStoresNothing() {
        assertThrows(IOException.class, () -> fileProvider.scan(tempDir.resolve("absent")));
        assertThrows(IOException.class, () -> fileProvider.scan(null));
    }

    // --- it must not read credentials ---------------------------------------

    @Test
    void filesNamedLikeCredentialsAreNotRead() throws IOException {
        // everything read here is sent to a language model to be summarised
        Path project = tempDir.resolve("withsecrets");
        Files.createDirectories(project);
        Files.writeString(project.resolve("secrets.yaml"), "api_key: sk-ant-REAL-LOOKING-VALUE\n");
        Files.writeString(project.resolve("credentials.json"), "{\"token\":\"abc\"}");
        Files.writeString(project.resolve("api_key.yaml"), "k: v");
        Files.writeString(project.resolve("safe.yaml"), "model: tcn\n");

        ContextSnapshot s = fileProvider.scan(project);

        assertEquals(1, s.getConfigEntries().size(), s.getConfigEntries().toString());
        assertTrue(s.getConfigEntries().containsKey("safe.yaml"));
    }

    @Test
    void aKeyShapedValueInAnOrdinaryConfigIsRedacted() throws IOException {
        Path project = tempDir.resolve("leaky");
        Files.createDirectories(project);
        Files.writeString(project.resolve("run.yaml"), "token: sk-ant-abcdefghijklmnop\n");

        ContextSnapshot s = fileProvider.scan(project);

        String value = s.getConfigEntries().get("run.yaml");
        assertFalse(value.contains("abcdefghijklmnop"), value);
        assertTrue(value.contains("[REDACTED]"), value);
    }

    @Test
    void redactionLeavesOrdinaryValuesAlone() {
        assertEquals("model: tcn_autoencoder", FileBasedContextProvider.redact(
                "model: tcn_autoencoder"));
    }

    private static ContextSnapshot assertDoesNotThrowScan(
            ProjectContextProvider provider, Path path) {
        try {
            return provider.scan(path);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aConfigIsDescribedByItsKeysSoADiffSaysWhatChanged() throws IOException {
        // the first live F10 run reported "2 lines: model: tcn -> 3 lines:
        // model: tcn", which says something changed but not what
        Path project = tempDir.resolve("keys");
        Files.createDirectories(project);
        Files.writeString(project.resolve("baseline.yaml"),
                "# a comment\nmodel: tcn\nseed: 20\ndropout: 0.3\n");

        String described = fileProvider.scan(project).getConfigEntries().get("baseline.yaml");

        assertTrue(described.startsWith("keys: "), described);
        assertTrue(described.contains("dropout"),
                "a new key is what makes the diff informative: " + described);
        assertFalse(described.contains("0.3"),
                "values are not needed, and a value is likelier to be a secret");
    }

    @Test
    void aConfigWithOneKeyFallsBackToItsFirstLine() throws IOException {
        Path project = tempDir.resolve("onekey");
        Files.createDirectories(project);
        Files.writeString(project.resolve("tiny.yaml"), "model: tcn\n");

        String described = fileProvider.scan(project).getConfigEntries().get("tiny.yaml");
        assertTrue(described.contains("model: tcn"), described);
    }
}
