package ca.yorku.eecs3311.consultant.llm;

/**
 * Signals that the LLM could not answer: no key configured, the network is
 * down, the provider returned an error, or the reply contained no text.
 *
 * <p>This is the {@code ServiceError} drawn in sequence diagram SD02. It is
 * checked on purpose: almost every use case in the design has an alternate
 * flow for an unavailable LLM — the deterministic result is still shown and
 * only the narrative is marked unavailable — and a checked exception makes the
 * compiler insist that each caller writes that path instead of forgetting it.
 */
public class LLMUnavailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public LLMUnavailableException(String reason) {
        super(reason);
    }

    public LLMUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
