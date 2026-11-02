package ca.yorku.eecs3311.consultant.domain;

/** The readable verdict on whether a paper's code is reusable (F09). */
public class RepoSummary {

    private String url = "";
    private boolean codeAvailable;
    private String readmeQuality = "unknown";
    private String license = "";
    private boolean hasDatasets;
    private boolean hasEvalScripts;
    private String note = "";

    public RepoSummary() {
    }

    /**
     * Reports a repository that could not be read, with the reason.
     *
     * <p>F09 distinguishes "no repository linked", "private", "not found" and
     * "rate limited" instead of collapsing them into one failure, so the
     * researcher knows whether to retry or give up.
     *
     * @param url    the repository URL, may be empty when none was linked
     * @param reason why the repository could not be inspected
     * @return a summary marked unavailable
     */
    public static RepoSummary unavailable(String url, String reason) {
        RepoSummary s = new RepoSummary();
        s.setUrl(url);
        s.codeAvailable = false;
        s.setNote(reason);
        return s;
    }

    /** @return true when the repository carries both data and a way to run the evaluation */
    public boolean isReproducible() {
        return codeAvailable && hasDatasets && hasEvalScripts;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url;
    }

    public boolean isCodeAvailable() {
        return codeAvailable;
    }

    public void setCodeAvailable(boolean codeAvailable) {
        this.codeAvailable = codeAvailable;
    }

    public String getReadmeQuality() {
        return readmeQuality;
    }

    public void setReadmeQuality(String readmeQuality) {
        this.readmeQuality = (readmeQuality == null || readmeQuality.isBlank()) ? "unknown" : readmeQuality;
    }

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license == null ? "" : license;
    }

    public boolean isHasDatasets() {
        return hasDatasets;
    }

    public void setHasDatasets(boolean hasDatasets) {
        this.hasDatasets = hasDatasets;
    }

    public boolean isHasEvalScripts() {
        return hasEvalScripts;
    }

    public void setHasEvalScripts(boolean hasEvalScripts) {
        this.hasEvalScripts = hasEvalScripts;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note == null ? "" : note;
    }
}
