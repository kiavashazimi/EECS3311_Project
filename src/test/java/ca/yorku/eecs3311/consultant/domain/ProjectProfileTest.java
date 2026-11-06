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
    void onlyTheResearchQuestionIsRequired() {
        // Datasets and metrics were required until a researcher with neither
        // typed "no specific" into both to get past the form. That string then
        // reached arXiv as a quoted search term and made F07 answer
        // NOT_COMPARABLE where it had to answer CANNOT_DETERMINE.
        assertEquals(List.of("researchQuestion"), new ProjectProfile().missingFields());
    }

    @Test
    void datasetsAndMetricsAreRecommendedRatherThanRequired() {
        ProjectProfile questionOnly = new ProjectProfile("does concept mapping aid recall?");

        assertTrue(questionOnly.isComplete(), "a question alone must be savable");
        assertEquals(List.of("datasets", "metrics"), questionOnly.recommendedFieldsMissing(),
                "the client has to name what the researcher gives up by leaving them out");
    }

    @Test
    void aProfileWithEverythingRecommendsNothing() {
        assertTrue(complete().recommendedFieldsMissing().isEmpty());
        assertTrue(complete().isFullyDescribed());
    }

    @Test
    void savableAndFullyDescribedAreDifferentQuestions() {
        ProjectProfile questionOnly = new ProjectProfile("does concept mapping aid recall?");

        assertTrue(questionOnly.isComplete());
        assertFalse(questionOnly.isFullyDescribed(),
                "'rc profile' must not report a bare question as fully described");
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

    // --- comparing a form against what was saved -----------------------------

    @Test
    void aProfileDescribesTheSameProjectAsAnIdenticalCopy() {
        ProjectProfile other = new ProjectProfile("Efficient retrieval for long-context QA");
        other.setDatasets(List.of("NaturalQuestions-LC"));
        other.setMetrics(List.of("nDCG@10"));

        assertTrue(complete().describesSameProjectAs(other));
    }

    @Test
    void aDifferentResearchQuestionIsADifferentProject() {
        ProjectProfile edited = complete();
        edited.setResearchQuestion("Is facial attractiveness perceived automatically?");

        assertFalse(edited.describesSameProjectAs(complete()),
                "F03 searches the saved profile, so an unsaved edit must be detectable");
    }

    @Test
    void caseAndSurroundingWhitespaceAreNotAChange() {
        ProjectProfile retyped = new ProjectProfile("  efficient Retrieval for long-context QA ");
        retyped.setDatasets(List.of(" naturalquestions-lc"));
        retyped.setMetrics(List.of("NDCG@10 "));

        assertTrue(retyped.describesSameProjectAs(complete()),
                "a warning on whitespace alone would train the researcher to ignore it");
    }

    @Test
    void anAddedDatasetIsADifferentProject() {
        ProjectProfile edited = complete();
        edited.setDatasets(List.of("NaturalQuestions-LC", "HotpotQA"));

        assertFalse(edited.describesSameProjectAs(complete()));
    }

    @Test
    void anEmptyProfileMatchesAnotherEmptyOne() {
        assertTrue(new ProjectProfile().describesSameProjectAs(new ProjectProfile()),
                "before anything is saved there is nothing to warn about");
    }

    @Test
    void nothingDescribesTheSameProjectAsNull() {
        assertFalse(complete().describesSameProjectAs(null));
    }
}
