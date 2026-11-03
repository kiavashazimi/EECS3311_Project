package ca.yorku.eecs3311.consultant.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.Comparability;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F07. The one verdict in the system that must never come from a language
 * model: a confident but wrong "comparable" would have the researcher claim
 * they beat a number measured on different data.
 */
class ComparabilityCheckerTest {

    private final ComparabilityChecker checker = new ComparabilityChecker();

    private static ProjectProfile myProject() {
        ProjectProfile p = new ProjectProfile("do synthetic X-rays improve fairness?");
        p.setDatasets(List.of("MIMIC-CXR", "CheXpert"));
        p.setMetrics(List.of("AUROC", "underdiagnosis gap"));
        return p;
    }

    private static PaperAnalysis paper(String dataset, List<String> metrics, String results) {
        PaperAnalysis a = new PaperAnalysis(
                new PaperRecord("1", "A paper", "arXiv", "2026-01-01"));
        a.setDataset(dataset);
        a.setMetrics(metrics);
        a.setResults(results);
        a.setMethod("a diffusion model");
        return a;
    }

    // --- comparable ----------------------------------------------------------

    @Test
    void sameDatasetAndSameMetricIsComparable() {
        PaperAnalysis a = paper("MIMIC-CXR", List.of("AUROC"), "AUROC 0.84");
        assertEquals(Comparability.COMPARABLE, checker.isComparable(a, myProject()));
        assertTrue(checker.findMismatches(a, myProject()).isEmpty());
    }

    @Test
    void punctuationAndCaseInTheNameDoNotMatter() {
        // "MIMIC CXR", "mimic-cxr" and "MIMIC_CXR" are one dataset
        for (String written : List.of("MIMIC CXR", "mimic-cxr", "MIMIC_CXR", "the MIMIC-CXR set")) {
            assertEquals(Comparability.COMPARABLE,
                    checker.isComparable(paper(written, List.of("AUROC"), "0.84"), myProject()),
                    "should match: " + written);
        }
    }

    @Test
    void anInterchangeableMetricNameStillCounts() {
        // AUC and AUROC are the same measure; a false mismatch here would
        // hide a paper the researcher genuinely competes with
        assertEquals(Comparability.COMPARABLE,
                checker.isComparable(paper("CheXpert", List.of("AUC"), "0.81"), myProject()));
    }

    @Test
    void aMoreSpecificMetricNameCountsAsTheMetric() {
        assertEquals(Comparability.COMPARABLE,
                checker.isComparable(paper("CheXpert", List.of("subgroup AUROC"), "0.81"),
                        myProject()));
    }

    // --- not comparable ------------------------------------------------------

    @Test
    void aDifferentDatasetIsNotComparableAndSaysWhich() {
        PaperAnalysis a = paper("NIH ChestX-ray14", List.of("AUROC"), "0.79");

        assertEquals(Comparability.NOT_COMPARABLE, checker.isComparable(a, myProject()));
        String mismatch = String.join(" | ", checker.findMismatches(a, myProject()));
        assertTrue(mismatch.contains("ChestX-ray14"), mismatch);
        assertTrue(mismatch.contains("MIMIC-CXR"), "it must name both sides: " + mismatch);
    }

    @Test
    void aDifferentMetricIsNotComparableAndSaysWhich() {
        PaperAnalysis a = paper("MIMIC-CXR", List.of("Dice"), "0.72");

        assertEquals(Comparability.NOT_COMPARABLE, checker.isComparable(a, myProject()));
        assertTrue(String.join(" ", checker.findMismatches(a, myProject())).contains("Dice"));
    }

    @Test
    void bothWrongYieldsBothMismatches() {
        PaperAnalysis a = paper("ImageNet", List.of("top-1 accuracy"), "76%");
        assertEquals(2, checker.findMismatches(a, myProject()).size());
    }

    // --- cannot determine (UC06 1a) -----------------------------------------

    @Test
    void anUnknownDatasetCannotBeDeterminedNotJudged() {
        PaperAnalysis a = paper(PaperAnalysis.UNKNOWN, List.of("AUROC"), "0.84");

        assertEquals(Comparability.CANNOT_DETERMINE, checker.isComparable(a, myProject()),
                "an absence of evidence is not a mismatch");
        assertTrue(String.join(" ", checker.findMismatches(a, myProject()))
                .contains("does not state which dataset"));
    }

    @Test
    void noMetricsInThePaperCannotBeDetermined() {
        assertEquals(Comparability.CANNOT_DETERMINE,
                checker.isComparable(paper("MIMIC-CXR", List.of(), "0.84"), myProject()));
    }

    @Test
    void anEmptyProfileCannotBeDetermined() {
        PaperAnalysis a = paper("MIMIC-CXR", List.of("AUROC"), "0.84");
        ProjectProfile bare = new ProjectProfile("a question with no datasets or metrics");

        assertEquals(Comparability.CANNOT_DETERMINE, checker.isComparable(a, bare));
        String mismatch = String.join(" | ", checker.findMismatches(a, bare));
        assertTrue(mismatch.contains("your project profile lists no datasets"), mismatch);
    }

    @Test
    void anUnknownDatasetIsNeverReportedAsAMatch() {
        // the dangerous direction: "unknown" must not be read as agreement
        PaperAnalysis a = paper(PaperAnalysis.UNKNOWN, List.of("AUROC"), "0.84");
        assertFalse(checker.isComparable(a, myProject()).isComparable());
    }

    @Test
    void missingResultsAreFlaggedEvenWhenTheVerdictIsComparable() {
        PaperAnalysis a = paper("MIMIC-CXR", List.of("AUROC"), PaperAnalysis.UNKNOWN);

        assertEquals(Comparability.COMPARABLE, checker.isComparable(a, myProject()));
        assertTrue(String.join(" ", checker.findMismatches(a, myProject()))
                .contains("does not state its results"),
                "the metric matches, but there is no number to compare against");
    }

    // --- the explanation -----------------------------------------------------

    @Test
    void explainAlwaysStatesTheVerdict() {
        // the note is sometimes shown without the badge, so it must stand alone
        PaperAnalysis a = paper("ImageNet", List.of("top-1 accuracy"), "76%");
        assertTrue(checker.explain(a, myProject())
                .startsWith(Comparability.NOT_COMPARABLE.label()));
    }

    @Test
    void explainIsPositiveWhenNothingIsWrong() {
        String note = checker.explain(paper("MIMIC-CXR", List.of("AUROC"), "0.84"), myProject());
        assertTrue(note.startsWith(Comparability.COMPARABLE.label()), note);
        assertTrue(note.toLowerCase().contains("same dataset"), note);
    }

    @Test
    void rejectsNullArguments() {
        PaperAnalysis a = paper("MIMIC-CXR", List.of("AUROC"), "0.84");
        assertThrows(IllegalArgumentException.class, () -> checker.isComparable(null, myProject()));
        assertThrows(IllegalArgumentException.class, () -> checker.isComparable(a, null));
        assertThrows(IllegalArgumentException.class, () -> checker.findMismatches(null, myProject()));
    }

    // --- readability of the output -------------------------------------------

    @Test
    void aDatasetFieldThatIsAWholeParagraphIsShortened() {
        // verbatim shape of a real extraction from arXiv 2304.09218
        PaperAnalysis a = paper("Three medical imaging contexts: (i) histopathology images "
                + "from a publicly available generalisation benchmark, (ii) chest X-rays from "
                + "publicly available clinical datasets, and (iii) dermatology images with "
                + "complex shifts and imaging conditions. Specific dataset names are not "
                + "stated.", List.of("AUROC"), "0.84");

        String mismatch = checker.findMismatches(a, myProject()).get(0);

        assertTrue(mismatch.length() < 200, "unreadable at " + mismatch.length() + " chars");
        assertTrue(mismatch.endsWith("MIMIC-CXR, CheXpert"), "both sides must still be named");
    }

    @Test
    void theNoteSummarisesRatherThanRepeatingEveryMismatch() {
        PaperAnalysis a = paper("ImageNet", List.of("top-1 accuracy"), "76%");

        String note = checker.explain(a, myProject());
        assertTrue(note.contains("2 reasons"), note);
        assertTrue(note.length() < 120, "it must stay short: " + note);
        assertFalse(note.contains("ImageNet"),
                "the detail belongs in the mismatch list, not beside the badge");
    }

    @Test
    void aSingleMismatchIsStatedInFullBesideTheBadge() {
        PaperAnalysis a = paper("ImageNet", List.of("AUROC"), "0.84");
        assertTrue(checker.explain(a, myProject()).contains("ImageNet"));
    }

    // --- the sentinel must never look like a value ---------------------------

    @Test
    void aMetricsListHoldingOnlyUnknownCannotBeDetermined() {
        // found live: the extraction produced metrics ["unknown"], and the
        // verdict came back NOT COMPARABLE - "the paper reports unknown".
        // That turns "we do not know" into "they do not match".
        PaperAnalysis a = paper("MIMIC-CXR", List.of(PaperAnalysis.UNKNOWN), "0.84");

        assertEquals(Comparability.CANNOT_DETERMINE, checker.isComparable(a, myProject()));
        assertTrue(String.join(" ", checker.findMismatches(a, myProject()))
                .contains("does not state which metrics"));
    }

    @Test
    void aRealMetricAlongsideTheSentinelStillCounts() {
        PaperAnalysis a = paper("MIMIC-CXR", List.of(PaperAnalysis.UNKNOWN, "AUROC"), "0.84");
        assertEquals(Comparability.COMPARABLE, checker.isComparable(a, myProject()));
    }

    @Test
    void theSentinelIsNotNamedAsADifferingMeasure() {
        PaperAnalysis a = paper("MIMIC-CXR", List.of(PaperAnalysis.UNKNOWN, "Dice"), "0.72");
        String mismatch = String.join(" ", checker.findMismatches(a, myProject()));

        assertTrue(mismatch.contains("Dice"), mismatch);
        assertFalse(mismatch.contains("unknown, Dice"),
                "the sentinel must not be listed as a metric the paper reports: " + mismatch);
    }
}
