package ca.yorku.eecs3311.consultant.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The structured form of a paper produced by F04: what problem it addresses,
 * how, on what data, and with what result.
 *
 * <p>Extraction is unreliable by nature, so any field the agent could not
 * establish is set to {@link #UNKNOWN} rather than left blank or guessed. F07
 * depends on that distinction: an unknown dataset must produce "cannot
 * determine", never a false match.
 */
public class PaperAnalysis {

    /** Marks a field the analysis could not establish. */
    public static final String UNKNOWN = "unknown";

    private PaperRecord paper = new PaperRecord();
    private String problem = UNKNOWN;
    private String method = UNKNOWN;
    private String dataset = UNKNOWN;
    private String model = UNKNOWN;
    private List<String> metrics = new ArrayList<>();
    private String results = UNKNOWN;
    private String limitations = UNKNOWN;
    private List<String> strategyTags = new ArrayList<>();
    private boolean tagsUncertain;

    public PaperAnalysis() {
    }

    public PaperAnalysis(PaperRecord paper) {
        this.paper = paper == null ? new PaperRecord() : paper;
    }

    /**
     * @param value any extracted field value
     * @return true when the value is absent or explicitly unknown
     */
    public static boolean isUnknown(String value) {
        return value == null || value.isBlank() || UNKNOWN.equalsIgnoreCase(value.trim());
    }

    /**
     * Names the fields the analysis could not establish.
     *
     * <p>Shown in the UI so the researcher can see what the extraction missed
     * instead of reading a confident-looking record with invented values.
     *
     * @return the unknown field names, empty when extraction was complete
     */
    public List<String> unknownFields() {
        List<String> unknown = new ArrayList<>();
        if (isUnknown(problem)) {
            unknown.add("problem");
        }
        if (isUnknown(method)) {
            unknown.add("method");
        }
        if (isUnknown(dataset)) {
            unknown.add("dataset");
        }
        if (isUnknown(model)) {
            unknown.add("model");
        }
        if (metrics.isEmpty()) {
            unknown.add("metrics");
        }
        if (isUnknown(results)) {
            unknown.add("results");
        }
        return unknown;
    }

    /** @return true when every field needed for a comparison is present */
    public boolean isComplete() {
        return unknownFields().isEmpty();
    }

    /**
     * @param metric a metric name, case-insensitive
     * @return true when the paper reports that metric
     */
    public boolean reportsMetric(String metric) {
        if (metric == null) {
            return false;
        }
        return metrics.stream().anyMatch(m -> m != null && m.equalsIgnoreCase(metric.trim()));
    }

    /**
     * Records a strategy tag. Blank tags and duplicates are ignored so repeated
     * classification cannot inflate the list.
     *
     * @param tag the strategy tag to add
     */
    public void addStrategyTag(String tag) {
        if (tag == null || tag.isBlank()) {
            return;
        }
        String trimmed = tag.trim();
        if (strategyTags.stream().noneMatch(t -> t.equalsIgnoreCase(trimmed))) {
            strategyTags.add(trimmed);
        }
    }

    public PaperRecord getPaper() {
        return paper;
    }

    public void setPaper(PaperRecord paper) {
        this.paper = paper == null ? new PaperRecord() : paper;
    }

    public String getProblem() {
        return problem;
    }

    public void setProblem(String problem) {
        this.problem = normalise(problem);
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = normalise(method);
    }

    public String getDataset() {
        return dataset;
    }

    public void setDataset(String dataset) {
        this.dataset = normalise(dataset);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = normalise(model);
    }

    public List<String> getMetrics() {
        return Collections.unmodifiableList(metrics);
    }

    public void setMetrics(List<String> metrics) {
        this.metrics = metrics == null ? new ArrayList<>() : new ArrayList<>(metrics);
    }

    public String getResults() {
        return results;
    }

    public void setResults(String results) {
        this.results = normalise(results);
    }

    public String getLimitations() {
        return limitations;
    }

    public void setLimitations(String limitations) {
        this.limitations = normalise(limitations);
    }

    public List<String> getStrategyTags() {
        return Collections.unmodifiableList(strategyTags);
    }

    public void setStrategyTags(List<String> strategyTags) {
        this.strategyTags = strategyTags == null ? new ArrayList<>() : new ArrayList<>(strategyTags);
    }

    public boolean isTagsUncertain() {
        return tagsUncertain;
    }

    public void setTagsUncertain(boolean tagsUncertain) {
        this.tagsUncertain = tagsUncertain;
    }

    private static String normalise(String value) {
        return (value == null || value.isBlank()) ? UNKNOWN : value.trim();
    }

    @Override
    public String toString() {
        return "PaperAnalysis{" + paper.getTitle() + ", dataset=" + dataset
                + ", metrics=" + metrics + ", tags=" + strategyTags + "}";
    }
}
