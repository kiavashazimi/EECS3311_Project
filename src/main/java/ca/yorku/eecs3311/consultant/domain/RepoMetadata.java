package ca.yorku.eecs3311.consultant.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Raw repository metadata as the GitHub API returns it (F09).
 *
 * <p>Metadata only. Repository code is never downloaded and never executed.
 */
public class RepoMetadata {

    private String url = "";
    private boolean hasReadme;
    private String license = "";
    private List<String> topLevelFiles = new ArrayList<>();

    public RepoMetadata() {
    }

    public RepoMetadata(String url) {
        this.url = url == null ? "" : url;
    }

    /**
     * @param name a file or directory name, case-insensitive
     * @return true when the repository root contains it
     */
    public boolean hasTopLevelEntry(String name) {
        if (name == null) {
            return false;
        }
        return topLevelFiles.stream().anyMatch(f -> f != null && f.equalsIgnoreCase(name.trim()));
    }

    /** @return true when a licence is stated, which reuse depends on */
    public boolean hasLicense() {
        return !license.isBlank() && !"none".equalsIgnoreCase(license);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url == null ? "" : url;
    }

    public boolean isHasReadme() {
        return hasReadme;
    }

    public void setHasReadme(boolean hasReadme) {
        this.hasReadme = hasReadme;
    }

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license == null ? "" : license;
    }

    public List<String> getTopLevelFiles() {
        return Collections.unmodifiableList(topLevelFiles);
    }

    public void setTopLevelFiles(List<String> topLevelFiles) {
        this.topLevelFiles = topLevelFiles == null ? new ArrayList<>() : new ArrayList<>(topLevelFiles);
    }
}
