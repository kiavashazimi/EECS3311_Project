package ca.yorku.eecs3311.consultant.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * A test double for {@link LLMClient}. It exists so that tests of agents and
 * services never reach the network: a real call costs money, needs a key, and
 * returns different text every time, none of which belongs in {@code mvn test}.
 *
 * <p>It also demonstrates the point of the Adapter: anything written against
 * {@code LLMClient} works with this class and with {@link ClaudeClient}
 * without changing a line.
 */
public final class FakeLLMClient implements LLMClient {

    private final List<String> promptsSeen = new ArrayList<>();
    private String nextResponse = "fake response";
    private String unavailableReason;

    /** Makes the next call succeed with {@code response}. */
    public FakeLLMClient respondWith(String response) {
        this.nextResponse = response;
        this.unavailableReason = null;
        return this;
    }

    /** Makes every call fail, so a caller's degraded path can be tested. */
    public FakeLLMClient failWith(String reason) {
        this.unavailableReason = reason;
        return this;
    }

    @Override
    public String generate(String prompt) throws LLMUnavailableException {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be null or blank");
        }
        promptsSeen.add(prompt);
        if (unavailableReason != null) {
            throw new LLMUnavailableException(unavailableReason);
        }
        return nextResponse;
    }

    /** @return every prompt passed to {@link #generate}, in order */
    public List<String> promptsSeen() {
        return List.copyOf(promptsSeen);
    }

    public int callCount() {
        return promptsSeen.size();
    }
}
