package ca.yorku.eecs3311.consultant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectProfileTest {

    private static ProjectProfile complete() {
        ProjectProfile p = new ProjectProfile("Efficient retrieval for long-context QA");
        p.setDatasets(List.of("NaturalQuestions-LC"));
        p.setMetrics(List.of("nDCG@10"));
        return p;
    }

    @Test
    void newProfileReportsEveryRequiredFieldMissing() {
        assertEquals(List.of("researchQuestion", "datasets", "metrics"),
                new ProjectProfile().missingFields());
    }

    @Test
    void completeProfileHasNoMissingFields() {
        assertTrue(complete().isComplete());
        assertTrue(complete().missingFields().isEmpty());
    }

    @Test
    void blankQuestionCountsAsMissing() {
        ProjectProfile p = complete();
        p.setResearchQuestion("   ");
        assertEquals(List.of("researchQuestion"), p.missingFields());
    }

    @Test
    void nullQuestionIsStoredAsEmptyRatherThanNull() {
        ProjectProfile p = new ProjectProfile(null);
        assertEquals("", p.getResearchQuestion());
        assertFalse(p.isComplete());
    }

    @Test
    void metricAndDatasetLookupIgnoresCaseAndSurroundingSpace() {
        ProjectProfile p = complete();
        assertTrue(p.usesMetric("  ndcg@10 "));
        assertTrue(p.usesDataset("naturalquestions-lc"));
        assertFalse(p.usesMetric("Recall@20"));
        assertFalse(p.usesMetric(null));
    }

    @Test
    void collectionsAreDefensivelyCopiedAndNotModifiableThroughGetters() {
        List<String> source = new java.util.ArrayList<>(List.of("ds1"));
        ProjectProfile p = complete();
        p.setDatasets(source);

        source.add("sneaked-in");
        assertEquals(List.of("ds1"), p.getDatasets(),
                "mutating the caller's list must not change the profile");

        assertThrows(UnsupportedOperationException.class, () -> p.getDatasets().add("x"),
                "the getter must not expose a mutable view");
    }
}
