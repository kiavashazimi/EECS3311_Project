package ca.yorku.eecs3311.consultant.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTest {

    @Test
    void aFreshPlanIsEmpty() {
        assertTrue(new Plan().isEmpty());
    }

    @Test
    void stepsAndToolCallsAreRecordedInOrderAndChainable() {
        Plan p = new Plan()
                .addStep("read the profile")
                .addStep("build a query")
                .addToolCall("literature.search");
        assertEquals(List.of("read the profile", "build a query"), p.getSteps());
        assertEquals(List.of("literature.search"), p.getToolCalls());
        assertFalse(p.isEmpty());
    }

    @Test
    void blankAndNullEntriesAreIgnoredAndValuesTrimmed() {
        Plan p = new Plan().addStep("  trimmed  ").addStep("").addStep(null).addToolCall("  t  ");
        assertEquals(List.of("trimmed"), p.getSteps());
        assertEquals(List.of("t"), p.getToolCalls());
    }

    @Test
    void theRecordedPlanCannotBeMutatedThroughItsGetters() {
        Plan p = new Plan().addStep("s");
        assertThrows(UnsupportedOperationException.class, () -> p.getSteps().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> p.getToolCalls().add("x"));
    }
}
