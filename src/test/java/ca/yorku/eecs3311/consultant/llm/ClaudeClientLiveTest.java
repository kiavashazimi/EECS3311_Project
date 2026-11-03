package ca.yorku.eecs3311.consultant.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The one test that really calls Claude. It is skipped unless you ask for it:
 *
 * <pre>mvn test -Dllm.live=true</pre>
 *
 * <p>Kept off by default on purpose — a real call costs money and needs a
 * network, and {@code mvn test} must stay free and offline.
 */
@EnabledIfSystemProperty(named = "llm.live", matches = "true")
class ClaudeClientLiveTest {

    @Test
    void answersARealPrompt() throws Exception {
        Config config = Config.load();
        assertTrue(config.hasLlmApiKey(),
                "set LLM_API_KEY in .env before running the live test");

        LLMClient client = new ClaudeClient(config);
        String answer = client.generate(
                "Reply with exactly one word, lowercase, no punctuation: working");

        System.out.println("live model: " + config.llmModel());
        System.out.println("live reply: " + answer);

        assertFalse(answer.isBlank(), "a live call must return text");
        assertTrue(answer.toLowerCase().contains("working"),
                "expected the model to follow a trivial instruction, got: " + answer);
    }
}
