package ca.yorku.eecs3311.consultant.memory;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The researcher's project state: their profile, every paper analysed so far,
 * and the most recent scan of their project directory.
 *
 * <p><strong>Singleton.</strong> The GUI, the CLI and all five agents read and
 * write this state. Separate copies could drift — an analysis saved from the
 * CLI would not appear in the GUI, and F07 might compare a paper against a
 * profile the user had already changed. One instance removes that whole class
 * of bug rather than requiring every caller to synchronise.
 *
 * <p>Instantiated through the initialization-on-demand holder idiom: the JVM
 * guarantees the holder class is loaded once and only on first use, so access
 * is thread-safe and lazy without any synchronisation cost on reads.
 *
 * <p>This class holds state in memory only. Persistence is layered on top of
 * it rather than built into it, so the storage format can change without the
 * agents noticing.
 */
public final class ProjectMemory {

    private ProjectProfile profile = new ProjectProfile();
    private final List<PaperAnalysis> papers = new ArrayList<>();
    private ContextSnapshot lastSnapshot = new ContextSnapshot();
    private List<String> lastListing = new ArrayList<>();

    private ProjectMemory() {
    }

    private static final class Holder {
        private static final ProjectMemory INSTANCE = new ProjectMemory();
    }

    /** @return the single shared instance */
    public static ProjectMemory getInstance() {
        return Holder.INSTANCE;
    }

    // --- profile ------------------------------------------------------------

    /**
     * @param p the profile to store, never null
     * @throws IllegalArgumentException if p is null, so a mistaken call cannot
     *     silently erase the researcher's profile
     */
    public synchronized void saveProfile(ProjectProfile p) {
        if (p == null) {
            throw new IllegalArgumentException("profile must not be null");
        }
        this.profile = p;
    }

    /** @return the stored profile, empty but never null before one is saved */
    public synchronized ProjectProfile getProfile() {
        return profile;
    }

    // --- analysed papers ----------------------------------------------------

    /**
     * Stores an analysis, replacing any earlier analysis of the same paper.
     *
     * <p>F04 can be run again on a paper the researcher has already analysed.
     * Replacing rather than appending keeps one record per paper, so the feed,
     * the digest and the solution landscape all count it once.
     *
     * @param a the analysis to store, never null
     * @throws IllegalArgumentException if a is null
     */
    public synchronized void addPaperAnalysis(PaperAnalysis a) {
        if (a == null) {
            throw new IllegalArgumentException("analysis must not be null");
        }
        String id = a.getPaper().getId();
        for (int i = 0; i < papers.size(); i++) {
            if (papers.get(i).getPaper().getId().equals(id)) {
                papers.set(i, a);
                return;
            }
        }
        papers.add(a);
    }

    /** @return every stored analysis, in the order they were first added */
    public synchronized List<PaperAnalysis> getPaperAnalyses() {
        return Collections.unmodifiableList(new ArrayList<>(papers));
    }

    /**
     * @param paperId the identifier of the paper
     * @return the stored analysis, or empty when the paper has not been analysed
     */
    public synchronized Optional<PaperAnalysis> findAnalysis(String paperId) {
        if (paperId == null) {
            return Optional.empty();
        }
        return papers.stream().filter(a -> a.getPaper().getId().equals(paperId)).findFirst();
    }

    // --- project context ----------------------------------------------------

    /**
     * @param s the newest scan of the project directory, never null
     * @throws IllegalArgumentException if s is null
     */
    public synchronized void saveSnapshot(ContextSnapshot s) {
        if (s == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        this.lastSnapshot = s;
    }

    /**
     * @return the most recent snapshot. Empty rather than null before the first
     *     import, so F10 can report that there is nothing to diff against
     */
    public synchronized ContextSnapshot getLastSnapshot() {
        return lastSnapshot;
    }

    // --- the last discovery listing -----------------------------------------

    /**
     * Records the paper ids of the listing a client last showed, in the order
     * it showed them.
     *
     * <p>This exists so the CLI can accept the row number a researcher just
     * read off the screen. Every {@code rc} invocation is a separate JVM, so
     * the position-to-id mapping has to outlive the process that printed it.
     *
     * @param ids the ids in display order; null or empty clears the listing,
     *     because "there is no listing" is the correct state after a discovery
     *     that found nothing
     */
    public synchronized void saveLastListing(List<String> ids) {
        lastListing = ids == null ? new ArrayList<>() : new ArrayList<>(ids);
    }

    /**
     * @return the ids of the last listing in display order, read-only. Empty
     *     before any discovery, so a caller can tell a stale number from one
     *     that never had a listing to refer to
     */
    public synchronized List<String> getLastListing() {
        return Collections.unmodifiableList(new ArrayList<>(lastListing));
    }

    // --- lifecycle ----------------------------------------------------------

    /**
     * Returns the memory to its initial state.
     *
     * <p>Needed because a singleton outlives a test. Each test clears it first
     * so results cannot depend on execution order. Also backs a future "start a
     * new project" action.
     */
    public synchronized void clear() {
        profile = new ProjectProfile();
        papers.clear();
        lastSnapshot = new ContextSnapshot();
        lastListing = new ArrayList<>();
    }
}
