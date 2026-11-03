package ca.yorku.eecs3311.consultant.literature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.llm.FakeLLMClient;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the Strategy does what section 4 claims: the source can be changed at
 * runtime and the caller is untouched.
 */
class LiteratureStrategySwapTest {

    private static ToolManager tools() {
        return new ToolManager(new FakeLLMClient());
    }

    private static PaperRecord paper(String id, String title) {
        return new PaperRecord(id, title, "arXiv cs.CL", "2024-01-01");
    }

    /** A caller that names no concrete source. */
    private static List<String> titlesFor(ToolManager tools, String query) throws Exception {
        return tools.search(query).stream().map(PaperRecord::getTitle).toList();
    }

    @Test
    void theSourceCanBeSwappedWithoutChangingTheCaller() throws Exception {
        ToolManager tools = tools();

        tools.setLiteratureSource(new FakeLiteratureSource("arXiv")
                .returning(paper("1", "From arXiv")));
        assertEquals(List.of("From arXiv"), titlesFor(tools, "rag"));

        // same ToolManager, same caller, different strategy
        tools.setLiteratureSource(new FakeLiteratureSource("Semantic Scholar")
                .returning(paper("2", "From Semantic Scholar")));
        assertEquals(List.of("From Semantic Scholar"), titlesFor(tools, "rag"));
    }

    @Test
    void theCallLogNamesWhicheverSourceAnswered() throws Exception {
        ToolManager tools = tools();
        tools.setLiteratureSource(new FakeLiteratureSource("arXiv").returning(paper("1", "x")));
        tools.search("rag");

        tools.setLiteratureSource(
                new FakeLiteratureSource("Semantic Scholar").returning(paper("2", "y")));
        tools.search("rag");

        assertTrue(tools.getCallLog().get(0).startsWith("arXiv ("), tools.getCallLog().get(0));
        assertTrue(tools.getCallLog().get(1).startsWith("Semantic Scholar ("),
                tools.getCallLog().get(1));
    }

    @Test
    void searchingWithNoSourceSetFailsWithAUsefulMessage() {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> tools().search("rag"));
        assertTrue(e.getMessage().contains("setLiteratureSource"),
                "the message should say how to fix it, got: " + e.getMessage());
    }

    @Test
    void aFailedSearchIsRecordedAndPassedThrough() {
        ToolManager tools = tools();
        tools.setLiteratureSource(new FakeLiteratureSource("arXiv").failWith("HTTP 503"));

        LiteratureUnavailableException e = assertThrows(LiteratureUnavailableException.class,
                () -> tools.search("rag"));
        assertEquals("HTTP 503", e.getMessage());
        assertTrue(tools.getCallLog().get(0).contains("failed"), tools.getCallLog().get(0));
    }

    @Test
    void theSourceCannotBeUnset() {
        assertThrows(IllegalArgumentException.class, () -> tools().setLiteratureSource(null));
    }

    @Test
    void theIntentReachesTheSource() throws Exception {
        ToolManager tools = tools();
        FakeLiteratureSource source = new FakeLiteratureSource("arXiv").returning(paper("1", "x"));
        tools.setLiteratureSource(source);

        tools.search("fairness", SearchIntent.NEWEST);
        tools.search("fairness", SearchIntent.MOST_RELEVANT);
        tools.search("fairness");

        assertEquals(List.of(SearchIntent.NEWEST, SearchIntent.MOST_RELEVANT,
                SearchIntent.MOST_RELEVANT), source.intentsSeen(),
                "the default must be best-matches-first");
    }

    @Test
    void theCallLogSaysWhichOrderingWasAskedFor() throws Exception {
        ToolManager tools = tools();
        tools.setLiteratureSource(new FakeLiteratureSource("arXiv").returning(paper("1", "x")));
        tools.search("fairness", SearchIntent.NEWEST);

        assertTrue(tools.getCallLog().get(0).contains("NEWEST"), tools.getCallLog().get(0));
    }
}
