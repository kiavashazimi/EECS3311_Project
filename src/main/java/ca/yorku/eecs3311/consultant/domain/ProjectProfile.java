package ca.yorku.eecs3311.consultant.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The researcher's own project: the question they are working on and the
 * datasets, models, metrics and results that define it.
 *
 * <p>This is the record every other feature is judged against. F06 compares a
 * paper to it, and F07 decides whether published results are comparable to the
 * {@code ownResults} recorded here.
 */
public class ProjectProfile {

    private String researchQuestion = "";
    private List<String> datasets = new ArrayList<>();
    private List<String> models = new ArrayList<>();
    private List<String> metrics = new ArrayList<>();
    private List<String> ownResults = new ArrayList<>();

    public ProjectProfile() {
    }

    public ProjectProfile(String researchQuestion) {
        this.researchQuestion = researchQuestion == null ? "" : researchQuestion;
    }

    /**
     * Names the required fields that are still empty.
     *
     * <p>F01 rejects a save when this is non-empty, and F06 reports exactly
     * which fields are missing rather than guessing around them. Returning the
     * names, not just a boolean, is what lets both features say <em>which</em>
     * field is at fault.
     *
     * <p><strong>Only the research question is required.</strong> Datasets and
     * metrics were required too, until a researcher working on a question with
     * neither typed {@code "no specific"} into both fields to get past the
     * form. That string is not inert: it reached arXiv as a quoted search term,
     * went into the ranking prompt as a dataset name, and made
     * {@code ComparabilityChecker} answer {@code NOT_COMPARABLE} where it had
     * to answer {@code CANNOT_DETERMINE} — an absence of evidence turned into a
     * claim, which is the one thing the three-valued verdict exists to stop.
     * A blank field is read correctly everywhere; an invented one is not, so
     * the wall was producing worse data than the gap it closed. See
     * {@link #recommendedFieldsMissing()}.
     *
     * @return the missing field names, empty if the profile is usable
     */
    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (isBlank(researchQuestion)) {
            missing.add("researchQuestion");
        }
        return missing;
    }

    /**
     * Names the fields that are not required but cost the researcher something
     * when they are left out.
     *
     * <p>Separate from {@link #missingFields()} because the two drive different
     * behaviour: that list blocks a save, this one only warrants a note saying
     * what degrades. Without datasets and metrics, F03 searches on the research
     * question alone and F07 can only ever reach "cannot determine".
     *
     * @return the recommended field names that are empty, in form order
     */
    public List<String> recommendedFieldsMissing() {
        List<String> missing = new ArrayList<>();
        if (datasets.isEmpty()) {
            missing.add("datasets");
        }
        if (metrics.isEmpty()) {
            missing.add("metrics");
        }
        return missing;
    }

    /** @return true when every required field is populated, so this can be saved */
    @JsonIgnore
    public boolean isComplete() {
        return missingFields().isEmpty();
    }

    /**
     * @return true when nothing is missing at all, required or recommended.
     *     Distinct from {@link #isComplete()}: a bare research question is
     *     savable but not fully described, and reporting it as complete would
     *     overstate what the other features have to work with
     */
    @JsonIgnore
    public boolean isFullyDescribed() {
        return missingFields().isEmpty() && recommendedFieldsMissing().isEmpty();
    }

    /**
     * @param metric a metric name, case-insensitive
     * @return true if the project reports that metric
     */
    public boolean usesMetric(String metric) {
        return containsIgnoreCase(metrics, metric);
    }

    /**
     * @param dataset a dataset name, case-insensitive
     * @return true if the project uses that dataset
     */
    public boolean usesDataset(String dataset) {
        return containsIgnoreCase(datasets, dataset);
    }

    /**
     * Whether this profile describes the same project as another.
     *
     * <p>Exists because a client's form and the saved profile can disagree.
     * F03 discovers from the <em>saved</em> profile, so typing a new research
     * question and pressing "Discover papers" without saving returns a ranked
     * list for the previous project with nothing on screen to say so. That
     * happened: a question about facial attractiveness returned chest X-ray
     * fairness papers. This lets the client warn instead.
     *
     * <p>Deliberately strict about order: reordering datasets counts as a
     * change. A needless warning costs one click, while a missed one hands the
     * researcher results for a question they are no longer asking.
     *
     * @param other the profile to compare against; null counts as different
     * @return true when the question and all four lists agree, ignoring case
     *     and surrounding whitespace
     */
    public boolean describesSameProjectAs(ProjectProfile other) {
        if (other == null) {
            return false;
        }
        return sameText(researchQuestion, other.researchQuestion)
                && sameList(datasets, other.datasets)
                && sameList(models, other.models)
                && sameList(metrics, other.metrics)
                && sameList(ownResults, other.ownResults);
    }

    private static boolean sameList(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!sameText(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameText(String a, String b) {
        String left = a == null ? "" : a.strip();
        String right = b == null ? "" : b.strip();
        return left.equalsIgnoreCase(right);
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        if (candidate == null) {
            return false;
        }
        return values.stream().anyMatch(v -> v != null && v.equalsIgnoreCase(candidate.trim()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public String getResearchQuestion() {
        return researchQuestion;
    }

    public void setResearchQuestion(String researchQuestion) {
        this.researchQuestion = researchQuestion == null ? "" : researchQuestion;
    }

    public List<String> getDatasets() {
        return Collections.unmodifiableList(datasets);
    }

    public void setDatasets(List<String> datasets) {
        this.datasets = datasets == null ? new ArrayList<>() : new ArrayList<>(datasets);
    }

    public List<String> getModels() {
        return Collections.unmodifiableList(models);
    }

    public void setModels(List<String> models) {
        this.models = models == null ? new ArrayList<>() : new ArrayList<>(models);
    }

    public List<String> getMetrics() {
        return Collections.unmodifiableList(metrics);
    }

    public void setMetrics(List<String> metrics) {
        this.metrics = metrics == null ? new ArrayList<>() : new ArrayList<>(metrics);
    }

    public List<String> getOwnResults() {
        return Collections.unmodifiableList(ownResults);
    }

    public void setOwnResults(List<String> ownResults) {
        this.ownResults = ownResults == null ? new ArrayList<>() : new ArrayList<>(ownResults);
    }

    @Override
    public String toString() {
        return "ProjectProfile{question='" + researchQuestion + "', datasets=" + datasets
                + ", models=" + models + ", metrics=" + metrics + "}";
    }
}
