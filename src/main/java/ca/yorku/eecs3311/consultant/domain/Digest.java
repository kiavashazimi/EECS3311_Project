package ca.yorku.eecs3311.consultant.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A periodic summary of new papers and recent project changes (F12). */
public class Digest {

    private String generatedAt = "";
    private List<PaperRecord> newPapers = new ArrayList<>();
    private String projectChanges = "";
    private String summaryText = "";

    public Digest() {
    }

    public Digest(String generatedAt) {
        this.generatedAt = generatedAt == null ? "" : generatedAt;
    }

    /**
     * True when there is nothing to report.
     *
     * <p>F12 shows an explicit "nothing new since &lt;date&gt;" in this case
     * rather than an empty view that looks broken.
     *
     * @return true when no papers and no project changes were collected
     */
    @JsonIgnore
    public boolean isEmpty() {
        return newPapers.isEmpty() && projectChanges.isBlank();
    }

    /** @return true when the digest was assembled but no narrative could be generated */
    @JsonIgnore
    public boolean isSummaryMissing() {
        return summaryText.isBlank();
    }

    public void addPaper(PaperRecord paper) {
        if (paper != null && !newPapers.contains(paper)) {
            newPapers.add(paper);
        }
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(String generatedAt) {
        this.generatedAt = generatedAt == null ? "" : generatedAt;
    }

    public List<PaperRecord> getNewPapers() {
        return Collections.unmodifiableList(newPapers);
    }

    public void setNewPapers(List<PaperRecord> newPapers) {
        this.newPapers = newPapers == null ? new ArrayList<>() : new ArrayList<>(newPapers);
    }

    public String getProjectChanges() {
        return projectChanges;
    }

    public void setProjectChanges(String projectChanges) {
        this.projectChanges = projectChanges == null ? "" : projectChanges;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText == null ? "" : summaryText;
    }
}
