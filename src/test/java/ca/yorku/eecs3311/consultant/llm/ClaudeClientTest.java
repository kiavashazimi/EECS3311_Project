package ca.yorku.eecs3311.consultant.llm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.config.Config;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the Claude adapter that never touch the network. Every assertion
 * here must hold without a key and without an internet connection, because
 * {@code mvn test} runs dozens of times a day and a paid API call has no place
 * in it.
 */
class ClaudeClientTest {

    @TempDir
    Path tempDir;

    /** A config with no key at all. */
    private Config withoutKey() throws IOException {
        return Config.load(tempDir.resolve("absent.env"), Map.of());
    }

    /** A config whose key is syntactically fine but will never be used here. */
    private Config withKey() throws IOException {
        Path f = tempDir.resolve(".env");
        Files.writeString(f, "LLM_API_KEY=sk-ant-not-a-real-key\n");
        return Config.load(f, Map.of());
    }

    // --- construction --------------------------------------------------------

    @Test
    void rejectsNullConfig() {
        assertThrows(IllegalArgumentException.class, () -> new ClaudeClient(null));
    }

    @Test
    void rejectsNonPositiveMaxTokens() throws IOException {
        Config c = withKey();
        assertThrows(IllegalArgumentException.class, () -> new ClaudeClient(c, 0));
        assertThrows(IllegalArgumentException.class, () -> new ClaudeClient(c, -1));
    }

    @Test
    void constructingWithoutAKeyDoesNotThrow() throws IOException {
        // the underlying client is built on first use, so an app with no key
        // still starts and can show every deterministic feature
        Config c = withoutKey();
        assertDoesNotThrow(() -> new ClaudeClient(c));
    }

    // --- prompt validation ---------------------------------------------------

    @Test
    void rejectsNullPrompt() throws IOException {
        ClaudeClient client = new ClaudeClient(withKey());
        assertThrows(IllegalArgumentException.class, () -> client.generate(null));
    }

    @Test
    void rejectsBlankPrompt() throws IOException {
        ClaudeClient client = new ClaudeClient(withKey());
        assertThrows(IllegalArgumentException.class, () -> client.generate("   \n "));
    }

    // --- the unavailable path (SD02) ----------------------------------------

    @Test
    void withoutAKeyGenerateReportsUnavailableRatherThanFailing() throws IOException {
        ClaudeClient client = new ClaudeClient(withoutKey());

        LLMUnavailableException e = assertThrows(LLMUnavailableException.class,
                () -> client.generate("summarise this paper"));

        assertTrue(e.getMessage().contains(".env"),
                "the message must say where to put the key, not just that it is missing");
    }

    @Test
    void validationHappensBeforeTheMissingKeyIsReported() throws IOException {
        // a blank prompt is a programming error; a missing key is a user
        // condition. The programming error must surface first.
        ClaudeClient client = new ClaudeClient(withoutKey());
        assertThrows(IllegalArgumentException.class, () -> client.generate(""));
    }

    // --- the key must never escape in an error message ----------------------

    @Test
    void scrubMasksAnythingThatLooksLikeAKey() {
        String leaked = "401 unauthorized for key sk-ant-EXAMPLE-NOT-A-REAL-KEY in header";
        String safe = ClaudeClient.scrub(leaked);

        assertFalse(safe.contains("EXAMPLE-NOT-A-REAL-KEY"), "the key body must not survive scrubbing");
        assertTrue(safe.contains("sk-ant-***"), "it should be visibly masked, not silently dropped");
        assertTrue(safe.contains("401 unauthorized"), "the useful part of the message must remain");
    }

    @Test
    void scrubLeavesOrdinaryMessagesAlone() {
        String message = "connect timed out after 60s";
        assertEquals(message, ClaudeClient.scrub(message));
    }

    @Test
    void scrubHandlesNull() {
        assertEquals("", ClaudeClient.scrub(null));
    }
}
