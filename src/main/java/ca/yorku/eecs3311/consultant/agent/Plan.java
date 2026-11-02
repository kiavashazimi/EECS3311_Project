package ca.yorku.eecs3311.consultant.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What an agent intends to do, produced by the planning step of the template
 * method in {@code Agent.run()} and consumed by the tool-execution step.
 *
 * <p>Agents create a Plan and discard it; nothing stores one. Making the plan
 * an object rather than an implicit sequence is what lets a test assert what an
 * agent decided to do without running the tools.
 */
public class Plan {

    private final List<String> steps = new ArrayList<>();
    private final List<String> toolCalls = new ArrayList<>();

    /**
     * @param step a human-readable description of one intended step
     * @return this plan, so steps can be chained
     */
    public Plan addStep(String step) {
        if (step != null && !step.isBlank()) {
            steps.add(step.trim());
        }
        return this;
    }

    /**
     * @param toolCall the name of a tool the agent intends to invoke
     * @return this plan, so calls can be chained
     */
    public Plan addToolCall(String toolCall) {
        if (toolCall != null && !toolCall.isBlank()) {
            toolCalls.add(toolCall.trim());
        }
        return this;
    }

    public List<String> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public List<String> getToolCalls() {
        return Collections.unmodifiableList(toolCalls);
    }

    /** @return true when the agent decided no action was needed */
    public boolean isEmpty() {
        return steps.isEmpty() && toolCalls.isEmpty();
    }

    @Override
    public String toString() {
        return "Plan{steps=" + steps + ", toolCalls=" + toolCalls + "}";
    }
}
