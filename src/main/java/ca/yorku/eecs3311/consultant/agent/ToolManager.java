package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.llm.LLMClient;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import java.util.ArrayList;
import java.util.List;

/**
 * The single doorway through which agents reach the outside world — the LLM
 * now, and the literature sources, GitHub and the paper parser as those
 * arrive.
 *
 * <p>Two jobs. It holds the tools so no agent constructs its own, which is
 * what lets one {@code LiteratureSource} be swapped for another without
 * touching an agent. And it records every call, so an agent cannot quietly
 * fail to report which tools it used — section 4 of the Stage 1 report names
 * that risk as the reason the agent loop lives in one place.
 */
public class ToolManager {

    private final LLMClient llm;
    private final List<String> callLog = new ArrayList<>();

    public ToolManager(LLMClient llm) {
        if (llm == null) {
            throw new IllegalArgumentException("llm must not be null");
        }
        this.llm = llm;
    }

    /**
     * Sends a prompt to the language model.
     *
     * @param prompt the prompt; must not be null or blank
     * @return the model's reply
     * @throws LLMUnavailableException if the model cannot be reached; the
     *     reason is passed through unchanged so the agent can show it
     */
    public String callLLM(String prompt) throws LLMUnavailableException {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be null or blank");
        }
        try {
            String reply = llm.generate(prompt);
            callLog.add("llm: " + summarise(prompt) + " -> " + reply.length() + " chars");
            return reply;
        } catch (LLMUnavailableException e) {
            // logged before rethrowing: a call that failed is the one most
            // worth seeing in the record of what the agent tried
            callLog.add("llm: " + summarise(prompt) + " -> failed (" + e.getMessage() + ")");
            throw e;
        }
    }

    /** @return every tool call made so far, oldest first, read-only */
    public List<String> getCallLog() {
        return List.copyOf(callLog);
    }

    /** Drops the record, for a fresh run. */
    public void clearCallLog() {
        callLog.clear();
    }

    /** Keeps the log readable when a prompt is a whole paper. */
    private static String summarise(String prompt) {
        String oneLine = prompt.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 57) + "...";
    }
}
