package ca.yorku.eecs3311.consultant.llm;

/**
 * The interface every agent uses to reason with a language model — the TARGET
 * of the Adapter pattern in section 4 of the Stage 1 report.
 *
 * <p>Nothing above this interface knows which provider answers. Swapping
 * provider means adding one class beside {@link ClaudeClient}; no agent, no
 * service, and neither the GUI nor the CLI changes.
 */
public interface LLMClient {

    /**
     * Sends {@code prompt} to the model and returns its text reply.
     *
     * @param prompt the full prompt; must not be null or blank
     * @return the model's reply, never null and never blank
     * @throws LLMUnavailableException if the model cannot be reached or
     *     returned no usable text — callers are expected to fall back to their
     *     deterministic result rather than fail
     * @throws IllegalArgumentException if {@code prompt} is null or blank,
     *     which is a bug in the caller rather than a service condition
     */
    String generate(String prompt) throws LLMUnavailableException;
}
