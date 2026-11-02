package ca.yorku.eecs3311.consultant.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    @TempDir
    Path tempDir;

    private Path envFile(String contents) throws IOException {
        Path f = tempDir.resolve(".env");
        Files.writeString(f, contents);
        return f;
    }

    // --- parsing ------------------------------------------------------------

    @Test
    void readsSimpleKeyValuePairs() throws IOException {
        Config c = Config.load(envFile("LLM_API_KEY=abc123\nLLM_MODEL=some-model\n"), Map.of());
        assertEquals("abc123", c.llmApiKey());
        assertEquals("some-model", c.llmModel());
    }

    @Test
    void ignoresCommentsAndBlankLines() throws IOException {
        Config c = Config.load(envFile("""
                # Local secrets
                LLM_API_KEY=abc123

                   # indented comment
                LLM_MODEL=m
                """), Map.of());
        assertEquals("abc123", c.llmApiKey());
        assertEquals("m", c.llmModel());
    }

    @Test
    void trimsWhitespaceAndStripsSurroundingQuotes() throws IOException {
        Config c = Config.load(envFile("  LLM_API_KEY =  \"abc123\"  \nLLM_MODEL = 'm' \n"), Map.of());
        assertEquals("abc123", c.llmApiKey());
        assertEquals("m", c.llmModel());
    }

    @Test
    void aValueContainingEqualsSignsIsKeptWhole() throws IOException {
        Config c = Config.load(envFile("LLM_API_KEY=a=b=c\n"), Map.of());
        assertEquals("a=b=c", c.llmApiKey(), "only the first = separates key from value");
    }

    @Test
    void malformedLinesAreSkippedRatherThanFailingTheLoad() throws IOException {
        Config c = Config.load(envFile("this line has no equals sign\nLLM_API_KEY=abc123\n"), Map.of());
        assertEquals("abc123", c.llmApiKey());
    }

    // --- precedence and absence ---------------------------------------------

    @Test
    void anEnvironmentVariableOverridesTheFile() throws IOException {
        Config c = Config.load(envFile("LLM_API_KEY=from-file\n"),
                Map.of("LLM_API_KEY", "from-environment"));
        assertEquals("from-environment", c.llmApiKey(),
                "a grader can supply a key without editing any file");
    }

    @Test
    void aMissingEnvFileIsNotAnError() {
        Config c = Config.load(tempDir.resolve("does-not-exist"), Map.of("LLM_API_KEY", "k"));
        assertEquals("k", c.llmApiKey(), "a fresh clone must still start");
    }

    @Test
    void anAbsentKeyGivesAnActionableMessageRatherThanNull() throws IOException {
        Config c = Config.load(envFile("LLM_MODEL=m\n"), Map.of());
        assertFalse(c.hasLlmApiKey());
        IllegalStateException e = assertThrows(IllegalStateException.class, c::llmApiKey);
        assertTrue(e.getMessage().contains(".env"), "the message must say where to put the key");
        assertTrue(e.getMessage().contains("LLM_API_KEY"));
    }

    @Test
    void defaultsApplyWhenAValueIsAbsent() {
        Config c = Config.load(tempDir.resolve("none"), Map.of());
        assertFalse(c.llmModel().isBlank(), "a model default keeps the app runnable");
        assertTrue(c.llmBaseUrl().startsWith("https://"));
        assertTrue(c.dataDir().isAbsolute(), "memory must not depend on the working directory");
    }

    // --- the one that matters -----------------------------------------------

    @Test
    void theApiKeyNeverAppearsInToString() throws IOException {
        String secret = "sk-ant-super-secret-value";
        Config c = Config.load(envFile("LLM_API_KEY=" + secret + "\n"), Map.of());

        assertFalse(c.toString().contains(secret),
                "an accidental log line or a demo video must not expose the key");
        assertTrue(c.toString().contains("set"), "it should still say whether a key is present");
    }

    @Test
    void aBlankKeyCountsAsAbsentRatherThanPresent() throws IOException {
        Config c = Config.load(envFile("LLM_API_KEY=   \n"), Map.of());
        assertFalse(c.hasLlmApiKey(), "an empty placeholder is not a key");
    }

    @Test
    void thePlaceholderFromEnvExampleIsTreatedAsAbsent() throws IOException {
        Config c = Config.load(envFile("LLM_API_KEY=YOUR_LOCAL_API_KEY_HERE\n"), Map.of());
        assertFalse(c.hasLlmApiKey(),
                "copying .env.example without editing it must not look like a configured key");
    }
}
