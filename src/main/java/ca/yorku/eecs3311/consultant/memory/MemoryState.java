package ca.yorku.eecs3311.consultant.memory;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    /**
     * The ids of the listing a client last displayed, in display order, so a
     * row number typed in a later process still means the same paper. Absent
     * from files written before row numbers existed, which loads as empty.
     */
    private List<String> lastListing = new ArrayList<>();

    /**
     * Papers discovered but not yet reported in a digest, and when the last
     * digest ran. Persisted because the Observer that collects them runs in a
     * different process from the one that prints the digest.
     */
    private List<PaperRecord> digestPending = new ArrayList<>();

    private String lastDigestAt = "";

    /**
     * Null until a project directory has actually been imported, and omitted
     * from the file while it is. A snapshot written out as blank strings reads
     * as a scan that happened, which is a different claim from never having
     * scanned at all.
     *
     * <p>The test is a blank {@code scannedAt}, not {@code isEmpty()}: a scan
     * always stamps its time, so a blank one means no scan ever ran. A scan
     * that ran and genuinely found nothing is {@code isEmpty()} but must still
     * be stored, because "we looked and there was nothing" is a real finding.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ContextSnapshot lastSnapshot;

    public ProjectProfile getProfile() {
        return profile;
    }

    public void setProfile(ProjectProfile profile) {
        this.profile = profile == null ? new ProjectProfile() : profile;
    }

    public List<String> getLastListing() {
        return lastListing;
    }

    public void setLastListing(List<String> lastListing) {
        this.lastListing = lastListing == null ? new ArrayList<>() : new ArrayList<>(lastListing);
    }

    public List<PaperRecord> getDigestPending() {
        return digestPending;
    }

    public void setDigestPending(List<PaperRecord> digestPending) {
        this.digestPending = digestPending == null
                ? new ArrayList<>() : new ArrayList<>(digestPending);
    }

    public String getLastDigestAt() {
        return lastDigestAt;
    }

    public void setLastDigestAt(String lastDigestAt) {
        this.lastDigestAt = lastDigestAt == null ? "" : lastDigestAt;
    }

    public List<PaperAnalysis> getPapers() {
        return papers;
    }

    public void setPapers(List<PaperAnalysis> papers) {
        this.papers = papers == null ? new ArrayList<>() : new ArrayList<>(papers);
    }

    /** @return the stored snapshot, or null if none was ever imported */
    public ContextSnapshot getLastSnapshot() {
        return lastSnapshot;
    }

    /** A snapshot from no scan at all is stored as null, to stay out of the file. */
    public void setLastSnapshot(ContextSnapshot lastSnapshot) {
        boolean neverScanned = lastSnapshot == null || lastSnapshot.getScannedAt().isBlank();
        this.lastSnapshot = neverScanned ? null : lastSnapshot;
    }
}
