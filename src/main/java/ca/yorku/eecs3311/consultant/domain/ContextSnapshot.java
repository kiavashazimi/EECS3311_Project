package ca.yorku.eecs3311.consultant.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The state of the researcher's own project directory at one moment (F02), and
 * the basis for the change summary in F10.
 */
public class ContextSnapshot {

    private String scannedAt = "";
    private String projectPath = "";
    private String providerName = "";
    private List<String> recentCommits = new ArrayList<>();
    private Map<String, String> configEntries = new LinkedHashMap<>();
    private List<String> resultFiles = new ArrayList<>();
    private String agentDescription = "";

    public ContextSnapshot() {
    }

    public ContextSnapshot(String scannedAt, String projectPath) {
        this.scannedAt = nullSafe(scannedAt);
        this.projectPath = nullSafe(projectPath);
    }

    /**
     * Describes what changed between an older snapshot and this one.
     *
     * <p>This is the deterministic half of F10: the agent only phrases the
     * result, so the facts themselves never depend on a language model.
     *
     * @param older the previously stored snapshot, may be null
     * @return human-readable change lines, empty when nothing changed
     */
    public List<String> changesSince(ContextSnapshot older) {
        List<String> changes = new ArrayList<>();
        if (older == null) {
            return changes;
        }
        for (String commit : recentCommits) {
            if (!older.recentCommits.contains(commit)) {
                changes.add("new commit: " + commit);
            }
        }
        for (String file : resultFiles) {
            if (!older.resultFiles.contains(file)) {
                changes.add("new result file: " + file);
            }
        }
        for (String file : older.resultFiles) {
            if (!resultFiles.contains(file)) {
                changes.add("removed result file: " + file);
            }
        }
        for (Map.Entry<String, String> e : configEntries.entrySet()) {
            String before = older.configEntries.get(e.getKey());
            if (before == null) {
                changes.add("new config: " + e.getKey() + " = " + e.getValue());
            } else if (!before.equals(e.getValue())) {
                changes.add("config changed: " + e.getKey() + ": " + before + " -> " + e.getValue());
            }
        }
        return changes;
    }

    /** @return true when the scan found nothing at all, so F02 can say so plainly */
    public boolean isEmpty() {
        return recentCommits.isEmpty() && configEntries.isEmpty() && resultFiles.isEmpty()
                && agentDescription.isBlank();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    public String getScannedAt() {
        return scannedAt;
    }

    public void setScannedAt(String scannedAt) {
        this.scannedAt = nullSafe(scannedAt);
    }

    public String getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(String projectPath) {
        this.projectPath = nullSafe(projectPath);
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = nullSafe(providerName);
    }

    public List<String> getRecentCommits() {
        return Collections.unmodifiableList(recentCommits);
    }

    public void setRecentCommits(List<String> recentCommits) {
        this.recentCommits = recentCommits == null ? new ArrayList<>() : new ArrayList<>(recentCommits);
    }

    public Map<String, String> getConfigEntries() {
        return Collections.unmodifiableMap(configEntries);
    }

    public void setConfigEntries(Map<String, String> configEntries) {
        this.configEntries = configEntries == null ? new LinkedHashMap<>() : new LinkedHashMap<>(configEntries);
    }

    public List<String> getResultFiles() {
        return Collections.unmodifiableList(resultFiles);
    }

    public void setResultFiles(List<String> resultFiles) {
        this.resultFiles = resultFiles == null ? new ArrayList<>() : new ArrayList<>(resultFiles);
    }

    public String getAgentDescription() {
        return agentDescription;
    }

    public void setAgentDescription(String agentDescription) {
        this.agentDescription = nullSafe(agentDescription);
    }
}
