package ca.yorku.eecs3311.consultant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PaperAnalysisTest {

    @Test
    void freshAnalysisReportsEveryFieldUnknown() {
        PaperAnalysis a = new PaperAnalysis();
        assertEquals(List.of("problem", "method", "dataset", "model", "metrics", "results"),
                a.unknownFields());
        assertFalse(a.isComplete());
    }

    @Test
    void blankAndNullValuesBecomeUnknownRatherThanEmpty() {
        PaperAnalysis a = new PaperAnalysis();
        a.setDataset("   ");
        a.setMethod(null);
        assertEquals(PaperAnalysis.UNKNOWN, a.getDataset());
        assertEquals(PaperAnalysis.UNKNOWN, a.getMethod());
    }

    @Test
    void isUnknownTreatsNullBlankAndTheSentinelAlike() {
        assertTrue(PaperAnalysis.isUnknown(null));
        assertTrue(PaperAnalysis.isUnknown(""));
        assertTrue(PaperAnalysis.isUnknown("  "));
        assertTrue(PaperAnalysis.isUnknown("UNKNOWN"));
        assertFalse(PaperAnalysis.isUnknown("NaturalQuestions-LC"));
    }

    @Test
    void completeAnalysisHasNoUnknownFields() {
        PaperAnalysis a = filled();
        assertTrue(a.isComplete(), "unexpected unknowns: " + a.unknownFields());
    }

    @Test
    void metricLookupIgnoresCase() {
        assertTrue(filled().reportsMetric("recall@20"));
        assertFalse(filled().reportsMetric("nDCG@10"));
        assertFalse(filled().reportsMetric(null));
    }

    @Test
    void strategyTagsAreDeduplicatedAndBlanksIgnored() {
        PaperAnalysis a = new PaperAnalysis();
        a.addStrategyTag("retrieval");
        a.addStrategyTag("  Retrieval  ");
        a.addStrategyTag("");
        a.addStrategyTag(null);
        a.addStrategyTag("long-context");
        assertEquals(List.of("retrieval", "long-context"), a.getStrategyTags());
    }

    private static PaperAnalysis filled() {
        PaperAnalysis a = new PaperAnalysis(new PaperRecord("p1", "Chunk-free Retrieval", "NeurIPS 25", "2025-11"));
        a.setProblem("Retrieval degrades beyond 32k tokens");
        a.setMethod("Chunk-free dense retrieval");
        a.setDataset("NaturalQuestions-LC");
        a.setModel("Llama-3-8B");
        a.setMetrics(List.of("Recall@20", "EM"));
        a.setResults("Recall@20 = 0.81");
        return a;
    }
}
