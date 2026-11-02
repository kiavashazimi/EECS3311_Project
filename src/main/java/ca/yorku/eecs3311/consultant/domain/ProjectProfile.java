package ca.yorku.eecs3311.consultant.domain;

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
     * @return the missing field names, empty if the profile is usable
     */
    public List<String> missingFields() {
        List<String> missing = new ArrayList<>();
        if (isBlank(researchQuestion)) {
            missing.add("researchQuestion");
        }
        if (datasets.isEmpty()) {
            missing.add("datasets");
        }
        if (metrics.isEmpty()) {
            missing.add("metrics");
        }
        return missing;
    }

    /** @return true when every required field is populated */
    public boolean isComplete() {
        return missingFields().isEmpty();
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
