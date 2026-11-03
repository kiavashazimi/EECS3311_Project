package ca.yorku.eecs3311.consultant.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.config.Config;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

/**
 * Proves the Adapter actually buys what section 4 of the Stage 1 report claims:
 * callers depend on {@link LLMClient} and never on a provider.
 *
 * <p>The {@code summarise} method below stands in for what every agent will do
 * — call the LLM, and degrade gracefully when it is unavailable, which is the
 * alternate flow in nearly every use case.
 */
class LLMClientSubstitutionTest {

    @TempDir
    Path tempDir;

    /** A caller written against the interface only. It names no provider. */
    private static String summarise(LLMClient llm, String text) {
        try {
            return llm.generate("Summarise in one line: " + text);
        } catch (LLMUnavailableException e) {
            return "summary unavailable";
        }
    }

    @Test
    void aCallerWorksAgainstTheFake() {
        FakeLLMClient fake = new FakeLLMClient().respondWith("a short summary");

        assertEquals("a short summary", summarise(fake, "a long paper"));
        assertEquals(1, fake.callCount());
        assertTrue(fake.promptsSeen().get(0).contains("a long paper"),
                "the caller's text must reach the client unchanged");
    }

    @Test
    void theSameCallerDegradesWhenTheClientIsUnavailable() {
        FakeLLMClient fake = new FakeLLMClient().failWith("network down");

        assertEquals("summary unavailable", summarise(fake, "a long paper"),
                "an unavailable LLM must not break the caller");
    }

    @Test
    void theSameCallerAcceptsTheRealAdapterWithoutChange() throws IOException {
        // no key configured, so this exercises the real ClaudeClient down to
        // the point where it would have called out, with no network traffic
        LLMClient real = new ClaudeClient(Config.load(tempDir.resolve("absent.env"), Map.of()));

        assertEquals("summary unavailable", summarise(real, "a long paper"),
                "swapping the fake for the adapter must not change the caller");
    }
}
