package ca.yorku.eecs3311.consultant.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The outcome of comparing one analysed paper with the researcher's project (F06, F07). */
public class ComparisonResult {

    private String paperTitle = "";
    private List<String> similarities = new ArrayList<>();
    private List<String> differences = new ArrayList<>();
    private Comparability comparability = Comparability.CANNOT_DETERMINE;
    private List<String> mismatches = new ArrayList<>();
    private String comparabilityNote = "";
    private String narrative = "";

    public ComparisonResult() {
    }

    public ComparisonResult(String paperTitle) {
        this.paperTitle = paperTitle == null ? "" : paperTitle;
    }

    /**
     * True when the agent could not supply prose, so the UI should show the
     * verdict and the raw mismatches alone. This is F07's LLM-unavailable path,
     * and it must still be a usable result rather than an error.
     *
     * @return true when no narrative was produced
     */
    @JsonIgnore
    public boolean isNarrativeMissing() {
        return narrative.isBlank();
    }

    public void addSimilarity(String s) {
        addNonBlank(similarities, s);
    }

    public void addDifference(String s) {
        addNonBlank(differences, s);
    }

    public void addMismatch(String s) {
        addNonBlank(mismatches, s);
    }

    private static void addNonBlank(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }

    public String getPaperTitle() {
        return paperTitle;
    }

    public void setPaperTitle(String paperTitle) {
        this.paperTitle = paperTitle == null ? "" : paperTitle;
    }

    public List<String> getSimilarities() {
        return Collections.unmodifiableList(similarities);
    }

    public void setSimilarities(List<String> similarities) {
        this.similarities = copy(similarities);
    }

    public List<String> getDifferences() {
        return Collections.unmodifiableList(differences);
    }

    public void setDifferences(List<String> differences) {
        this.differences = copy(differences);
    }

    public Comparability getComparability() {
        return comparability;
    }

    public void setComparability(Comparability comparability) {
        this.comparability = comparability == null ? Comparability.CANNOT_DETERMINE : comparability;
    }

    public List<String> getMismatches() {
        return Collections.unmodifiableList(mismatches);
    }

    public void setMismatches(List<String> mismatches) {
        this.mismatches = copy(mismatches);
    }

    public String getComparabilityNote() {
        return comparabilityNote;
    }

    public void setComparabilityNote(String note) {
        this.comparabilityNote = note == null ? "" : note;
    }

    public String getNarrative() {
        return narrative;
    }

    public void setNarrative(String narrative) {
        this.narrative = narrative == null ? "" : narrative;
    }

    private static List<String> copy(List<String> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }
}
