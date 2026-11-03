package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests the template method itself: that {@code run()} fixes the order of
 * plan, executeTools and summarize, and that a subclass cannot change it.
 */
class AgentTest {

    /** A subclass that records the order its hooks were called in. */
    private static final class RecordingAgent extends Agent {
        final List<String> calls = new ArrayList<>();

        RecordingAgent(ToolManager tools) {
            super(tools);
        }

        @Override
        protected Plan plan(Object input) {
            calls.add("plan");
            return new Plan().addStep("inspect " + input);
        }

        @Override
        protected Object executeTools(Plan plan) {
            calls.add("executeTools");
            return "raw result";
        }

        @Override
        protected Object summarize(Object result) {
            calls.add("summarize");
            return "summary of " + result;
        }
    }

    private static ToolManager tools() {
        return new ToolManager(new FakeLLMClient().respondWith("ok"));
    }

    @Test
    void runCallsTheThreeStepsInOrderExactlyOnce() {
        RecordingAgent agent = new RecordingAgent(tools());

        Object out = agent.run("a paper");

        assertEquals(List.of("plan", "executeTools", "summarize"), agent.calls);
        assertEquals("summary of raw result", out);
    }

    @Test
    void runIsFinalSoNoSubclassCanReorderTheSteps() throws Exception {
        // the whole point of the pattern: the order lives in one place
        Method run = Agent.class.getDeclaredMethod("run", Object.class);
        assertTrue(Modifier.isFinal(run.getModifiers()),
                "Agent.run() must be final or a subclass could replace the template");
    }

    @Test
    void theHooksAreProtectedNotPublic() {
        for (Method m : Agent.class.getDeclaredMethods()) {
            if (List.of("plan", "executeTools", "summarize").contains(m.getName())) {
                assertTrue(Modifier.isProtected(m.getModifiers()),
                        m.getName() + "() should be protected - an extension point, not API");
            }
        }
    }

    @Test
    void rejectsANullToolManager() {
        assertThrows(IllegalArgumentException.class, () -> new RecordingAgent(null));
    }

    @Test
    void thePlanIsKeptSoTheUiCanShowWhatTheAgentDid() {
        RecordingAgent agent = new RecordingAgent(tools());

        agent.run("a paper");

        assertNotNull(agent.getLastPlan());
        assertEquals(List.of("inspect a paper"), agent.getLastPlan().getSteps());
    }

    @Test
    void noPlanIsHeldBeforeTheFirstRun() {
        assertEquals(null, new RecordingAgent(tools()).getLastPlan());
    }

    @Test
    void aSubclassReturningNoPlanFailsLoudly() {
        Agent broken = new Agent(tools()) {
            @Override
            protected Plan plan(Object input) {
                return null;
            }

            @Override
            protected Object executeTools(Plan plan) {
                return "never reached";
            }

            @Override
            protected Object summarize(Object result) {
                return result;
            }
        };

        // a null plan would reach executeTools as a null argument and surface
        // far from its cause, so it is caught in the template
        assertThrows(IllegalStateException.class, () -> broken.run("input"));
    }
}
