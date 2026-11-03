package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import org.junit.jupiter.api.Test;

/** The single doorway agents use to reach tools. */
class ToolManagerTest {

    @Test
    void rejectsANullLlm() {
        assertThrows(IllegalArgumentException.class, () -> new ToolManager(null));
    }

    @Test
    void callLlmDelegatesToTheClient() throws Exception {
        FakeLLMClient llm = new FakeLLMClient().respondWith("an answer");
        ToolManager tools = new ToolManager(llm);

        assertEquals("an answer", tools.callLLM("a question"));
        assertEquals("a question", llm.promptsSeen().get(0));
    }

    @Test
    void everyToolCallIsRecorded() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("ok"));

        tools.callLLM("first");
        tools.callLLM("second");

        assertEquals(2, tools.getCallLog().size());
        assertTrue(tools.getCallLog().get(0).startsWith("llm:"),
                "the log should name the tool, got: " + tools.getCallLog().get(0));
    }

    @Test
    void aFailedCallIsStillRecorded() {
        // the Stage 1 rationale for the Template Method is that an agent must
        // not be able to forget to log which tools it used - a failed call is
        // exactly the case most likely to go unlogged
        ToolManager tools = new ToolManager(new FakeLLMClient().failWith("network down"));

        assertThrows(LLMUnavailableException.class, () -> tools.callLLM("a question"));
        assertEquals(1, tools.getCallLog().size());
        assertTrue(tools.getCallLog().get(0).contains("failed"),
                "a failed call must be visibly a failure in the log");
    }

    @Test
    void unavailabilityIsPassedThroughUnchanged() {
        ToolManager tools = new ToolManager(new FakeLLMClient().failWith("network down"));

        LLMUnavailableException e = assertThrows(LLMUnavailableException.class,
                () -> tools.callLLM("a question"));
        assertEquals("network down", e.getMessage(),
                "ToolManager must not swallow or rewrite the reason");
    }

    @Test
    void theCallLogCannotBeEditedFromOutside() throws Exception {
        ToolManager tools = new ToolManager(new FakeLLMClient().respondWith("ok"));
        tools.callLLM("first");

        assertThrows(UnsupportedOperationException.class,
                () -> tools.getCallLog().add("forged entry"));
    }

    @Test
    void rejectsABlankPrompt() {
        ToolManager tools = new ToolManager(new FakeLLMClient());
        assertThrows(IllegalArgumentException.class, () -> tools.callLLM("  "));
    }
}
