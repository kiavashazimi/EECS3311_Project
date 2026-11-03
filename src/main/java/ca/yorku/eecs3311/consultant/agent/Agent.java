package ca.yorku.eecs3311.consultant.agent;

/**
 * The shape every agent in this system follows: work out what to do, use the
 * tools, then turn the result into something a researcher can read.
 *
 * <p>This is the Template Method of section 4 of the Stage 1 report.
 * {@link #run(Object)} is {@code final} on purpose — that is what makes it a
 * template rather than a suggestion. A subclass supplies the three steps and
 * cannot reorder them, skip one, or forget that the plan is recorded.
 */
public abstract class Agent {

    /** The tools this agent is allowed to use. */
    protected final ToolManager tools;

    private Plan lastPlan;

    protected Agent(ToolManager tools) {
        if (tools == null) {
            throw new IllegalArgumentException("tools must not be null");
        }
        this.tools = tools;
    }

    /**
     * Runs the agent. The order of the three steps is fixed here and nowhere
     * else.
     *
     * @param input whatever the agent works from — a query, a paper, a path
     * @return the summarized result
     * @throws AgentException if a step could not be completed, so the client
     *     can show the error and keep what it already had on screen
     * @throws IllegalStateException if a subclass produces no plan
     */
    public final Object run(Object input) throws AgentException {
        Plan plan = plan(input);
        if (plan == null) {
            // without this the null would arrive inside executeTools and fail
            // somewhere far from the subclass that actually caused it
            throw new IllegalStateException(
                    getClass().getSimpleName() + ".plan() returned no plan");
        }
        this.lastPlan = plan;

        Object raw = executeTools(plan);
        return summarize(raw);
    }

    /**
     * @return the plan from the most recent {@link #run}, or null before the
     *     first one. The GUI and the CLI show this so the researcher can see
     *     what the agent decided to do rather than only its answer.
     */
    public Plan getLastPlan() {
        return lastPlan;
    }

    /** Works out which tools to use, and in what order. */
    protected abstract Plan plan(Object input) throws AgentException;

    /** Carries out the plan through {@link #tools}. */
    protected abstract Object executeTools(Plan plan) throws AgentException;

    /** Turns the raw tool output into the result the caller wants. */
    protected abstract Object summarize(Object result) throws AgentException;
}
