package ca.yorku.eecs3311.consultant.memory;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import java.util.ArrayList;
import java.util.List;

/**
 * The on-disk shape of everything {@link ProjectMemory} holds.
 *
 * <p>A separate type from {@code ProjectMemory} on purpose: the singleton is
 * about shared live state, this is about a file format. Keeping them apart
 * means the format can gain a version field, or move to a database, without
 * touching the class the agents use.
 */
public class MemoryState {

    private ProjectProfile profile = new ProjectProfile();
    private List<PaperAnalysis> papers = new ArrayList<>();
    private ContextSnapshot lastSnapshot = new ContextSnapshot();

    public ProjectProfile getProfile() {
        return profile;
    }

    public void setProfile(ProjectProfile profile) {
        this.profile = profile == null ? new ProjectProfile() : profile;
    }

    public List<PaperAnalysis> getPapers() {
        return papers;
    }

    public void setPapers(List<PaperAnalysis> papers) {
        this.papers = papers == null ? new ArrayList<>() : new ArrayList<>(papers);
    }

    public ContextSnapshot getLastSnapshot() {
        return lastSnapshot;
    }

    public void setLastSnapshot(ContextSnapshot lastSnapshot) {
        this.lastSnapshot = lastSnapshot == null ? new ContextSnapshot() : lastSnapshot;
    }
}
