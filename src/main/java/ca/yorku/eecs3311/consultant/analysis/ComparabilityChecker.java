package ca.yorku.eecs3311.consultant.analysis;

import ca.yorku.eecs3311.consultant.domain.Comparability;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * F07 — decides whether a paper's reported results can fairly be compared with
 * the researcher's own.
 *
 * <p>Entirely deterministic, and deliberately so. This is the one judgement in
 * the system that must never be produced by a language model: a confident but
 * wrong "comparable" would have the researcher claim they beat a number that
 * was measured on different data. The model is used afterwards, only to put
 * this verdict into words.
 *
 * <p>Introduces no design pattern of its own, by design — it is a domain
 * service, and inventing a pattern around two pure functions would be
 * pattern-for-its-own-sake.
 */
public class ComparabilityChecker {

    /**
     * Metric names that mean the same thing.
     *
     * <p>Deliberately short and conservative. Each entry risks turning a real
     * difference into a false "comparable", so only genuinely interchangeable
     * names are listed; anything arguable is left to come out as a mismatch,
     * where the researcher can see it and judge.
     */
    private static final Map<String, String> METRIC_ALIASES = metricAliases();

    private static Map<String, String> metricAliases() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("auc", "auroc");
        m.put("areaunderthecurve", "auroc");
        m.put("areaundertheroccurve", "auroc");
        m.put("rocauc", "auroc");
        m.put("acc", "accuracy");
        m.put("f1score", "f1");
        m.put("dicecoefficient", "dice");
        m.put("dicescore", "dice");
        return m;
    }

    /**
     * The verdict.
     *
     * @param analysis the paper, already analysed
     * @param profile the researcher's own project
     * @return {@link Comparability#CANNOT_DETERMINE} whenever either side is
     *     missing the information needed to decide — never a guess
     */
    public Comparability isComparable(PaperAnalysis analysis, ProjectProfile profile) {
        requireBoth(analysis, profile);

        // UC06 1a: an unknown field is never silently treated as a match or a
        // mismatch. Both of those are claims; this is an absence of evidence.
        if (PaperAnalysis.isUnknown(analysis.getDataset()) || statedMetrics(analysis).isEmpty()
                || profile.getDatasets().isEmpty() || profile.getMetrics().isEmpty()) {
            return Comparability.CANNOT_DETERMINE;
        }

        boolean sameDataset = sharesDataset(analysis, profile);
        boolean sameMetric = sharesMetric(analysis, profile);
        return sameDataset && sameMetric ? Comparability.COMPARABLE : Comparability.NOT_COMPARABLE;
    }

    /**
     * Every specific reason the results may not line up.
     *
     * @return one readable line per mismatch, empty when nothing is wrong.
     *     A field missing on either side appears here too, so the researcher
     *     sees <em>why</em> the verdict could not be reached.
     */
    public List<String> findMismatches(PaperAnalysis analysis, ProjectProfile profile) {
        requireBoth(analysis, profile);
        List<String> mismatches = new ArrayList<>();

        if (PaperAnalysis.isUnknown(analysis.getDataset())) {
            mismatches.add("the paper does not state which dataset it used");
        } else if (profile.getDatasets().isEmpty()) {
            mismatches.add("your project profile lists no datasets");
        } else if (!sharesDataset(analysis, profile)) {
            mismatches.add("different data: the paper used " + shorten(analysis.getDataset())
                    + ", your project uses " + String.join(", ", profile.getDatasets()));
        }

        if (statedMetrics(analysis).isEmpty()) {
            mismatches.add("the paper does not state which metrics it reports");
        } else if (profile.getMetrics().isEmpty()) {
            mismatches.add("your project profile lists no metrics");
        } else if (!sharesMetric(analysis, profile)) {
            mismatches.add("different measures: the paper reports "
                    + shorten(String.join(", ", statedMetrics(analysis)))
                    + ", your project uses " + String.join(", ", profile.getMetrics()));
        }

        if (PaperAnalysis.isUnknown(analysis.getResults())) {
            mismatches.add("the paper does not state its results numerically");
        }

        // Noted rather than checked: neither PaperAnalysis nor ProjectProfile
        // records the evaluation split, so two papers on the same dataset and
        // metric can still disagree about what was held out.
        return mismatches;
    }

    /**
     * A short note fit to sit beside the badge.
     *
     * <p>Deliberately a summary rather than the mismatch list: an extracted
     * dataset field can be a whole paragraph — one real paper answered
     * "Three medical imaging contexts: (i) histopathology..." — and repeating
     * that next to the badge and again in the list made the output unreadable.
     */
    public String explain(PaperAnalysis analysis, ProjectProfile profile) {
        Comparability verdict = isComparable(analysis, profile);
        List<String> mismatches = findMismatches(analysis, profile);

        // always prefixed with the verdict, because the note is sometimes shown
        // on its own and has to stand without the badge beside it
        if (mismatches.isEmpty()) {
            return verdict == Comparability.COMPARABLE
                    ? verdict.label() + " - same dataset and the same measure"
                    : verdict.label();
        }
        return verdict.label() + " - " + (mismatches.size() == 1
                ? mismatches.get(0)
                : mismatches.size() + " reasons the numbers may not line up");
    }

    /** Keeps an extracted field that turned out to be a paragraph readable. */
    private static String shorten(String value) {
        String oneLine = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 90 ? oneLine : oneLine.substring(0, 87) + "...";
    }

    // --- matching ------------------------------------------------------------

    private boolean sharesDataset(PaperAnalysis analysis, ProjectProfile profile) {
        String paperDatasets = normalise(analysis.getDataset());
        for (String mine : profile.getDatasets()) {
            String candidate = normalise(mine);
            if (!candidate.isBlank() && paperDatasets.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private boolean sharesMetric(PaperAnalysis analysis, ProjectProfile profile) {
        List<String> mine = new ArrayList<>();
        for (String metric : profile.getMetrics()) {
            mine.add(canonicalMetric(metric));
        }
        for (String theirs : statedMetrics(analysis)) {
            String candidate = canonicalMetric(theirs);
            for (String ours : mine) {
                // either direction: "subgroup AUROC gap" covers "AUROC"
                if (!candidate.isBlank() && !ours.isBlank()
                        && (candidate.contains(ours) || ours.contains(candidate))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The metrics the paper actually states.
     *
     * <p>Filters the {@code unknown} sentinel out of the list. A metrics list
     * holding only "unknown" means the metrics were not stated, and treating it
     * as a stated metric made the verdict say "different measures" where it had
     * to say "cannot determine" — turning an absence of evidence into a claim,
     * which is the exact error the three-valued verdict exists to prevent.
     */
    private static List<String> statedMetrics(PaperAnalysis analysis) {
        List<String> stated = new ArrayList<>();
        for (String metric : analysis.getMetrics()) {
            if (metric != null && !metric.isBlank() && !PaperAnalysis.isUnknown(metric)) {
                stated.add(metric);
            }
        }
        return stated;
    }

    /**
     * Strips everything that varies between how two authors write one name, so
     * "MIMIC-CXR", "MIMIC CXR" and "mimic_cxr" compare equal.
     */
    private static String normalise(String value) {
        return value == null ? "" : Pattern.compile("[^\\p{Alnum}]").matcher(value.toLowerCase())
                .replaceAll("");
    }

    private static String canonicalMetric(String metric) {
        String normalised = normalise(metric);
        return METRIC_ALIASES.getOrDefault(normalised, normalised);
    }

    private static void requireBoth(PaperAnalysis analysis, ProjectProfile profile) {
        if (analysis == null) {
            throw new IllegalArgumentException("analysis must not be null");
        }
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
    }
}
