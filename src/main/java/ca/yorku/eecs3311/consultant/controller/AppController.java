package ca.yorku.eecs3311.consultant.controller;

import ca.yorku.eecs3311.consultant.agent.AgentException;
import ca.yorku.eecs3311.consultant.agent.PaperAnalysisAgent;
import ca.yorku.eecs3311.consultant.agent.PaperDiscoveryAgent;
import ca.yorku.eecs3311.consultant.agent.PaperFeedListener;
import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import ca.yorku.eecs3311.consultant.literature.LiteratureUnavailableException;
import ca.yorku.eecs3311.consultant.literature.SearchIntent;
import ca.yorku.eecs3311.consultant.memory.MemoryStore;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The FACADE of section 4 of the Stage 1 report: the one surface the GUI and
 * the CLI are allowed to touch.
 *
 * <p>Neither client holds domain logic. They collect input, call a method
 * here, and render what comes back — which is what makes "every feature works
 * from both the GUI and the CLI" achievable rather than two parallel
 * implementations that drift apart.
 *
 * <p>Methods arrive as the features behind them are built. The signatures in
 * the Stage 1 class diagram that have no implementation yet are deliberately
 * absent rather than present and throwing: a method that always fails is not
 * a facade, it is a placeholder.
 */
public class AppController {

    private final ProjectMemory memory;
    private final ToolManager tools;
    private final MemoryStore store;
    private final PaperDiscoveryAgent discovery;
    private final PaperAnalysisAgent analysis;

    public AppController(ProjectMemory memory, ToolManager tools, MemoryStore store) {
        if (memory == null) {
            throw new IllegalArgumentException("memory must not be null");
        }
        if (tools == null) {
            throw new IllegalArgumentException("tools must not be null");
        }
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.memory = memory;
        this.tools = tools;
        this.store = store;
        this.discovery = new PaperDiscoveryAgent(tools);
        this.analysis = new PaperAnalysisAgent(tools);
    }

    // --- F01: set up the project profile (UC01, SD01) -----------------------

    /**
     * Validates a profile, stores it, and writes it to disk.
     *
     * <p>The order matters and is the whole point of SD01: validation happens
     * here, before anything is stored, so a rejected profile cannot leave the
     * live project half-updated.
     *
     * @param profile the profile to save; must not be null
     * @return the stored profile
     * @throws ValidationException if a required field is empty — nothing is
     *     stored and the previous profile is untouched
     * @throws StorageException if the write fails after validation passed; the
     *     in-memory profile is left as it was
     */
    public ProjectProfile saveProfile(ProjectProfile profile)
            throws ValidationException, StorageException {
        if (profile == null) {
            throw new IllegalArgumentException("profile must not be null");
        }

        List<String> missing = profile.missingFields();
        if (!missing.isEmpty()) {
            throw new ValidationException(missing);
        }

        // keep the old one so a failed write can be rolled back
        ProjectProfile previous = memory.getProfile();
        memory.saveProfile(profile);
        try {
            store.save(memory);
        } catch (IOException e) {
            memory.saveProfile(previous);
            throw new StorageException(
                    "the project could not be written to " + store.getFile(), e);
        }
        return memory.getProfile();
    }

    /** @return the current profile, empty but never null before the first save */
    public ProjectProfile getProfile() {
        return memory.getProfile();
    }

    // --- F03: discover new papers (UC03, SD03) ------------------------------

    /**
     * Finds papers that matter for the current project, best first.
     *
     * @return the ranked papers, each with a one-line reason; empty when the
     *     search found nothing, which is a state rather than a failure
     * @throws AgentException if every search failed, or the project has no
     *     research question yet. The caller keeps whatever it had on screen and
     *     offers a retry, per UC03 flow 4a.
     */
    @SuppressWarnings("unchecked")
    public List<RankedPaper> discoverPapers() throws AgentException {
        return (List<RankedPaper>) discovery.run(memory.getProfile());
    }

    /**
     * Registers something that wants to hear about each discovered paper — the
     * GUI feed, the digest builder, anything added later.
     */
    public void addPaperFeedListener(PaperFeedListener listener) {
        discovery.addListener(listener);
    }

    /** @return what the discovery agent decided to do on its last run, or null */
    public ca.yorku.eecs3311.consultant.agent.Plan getLastDiscoveryPlan() {
        return discovery.getLastPlan();
    }

    // --- F04 / F05: analyze and classify a paper (UC04, SD04) ---------------

    /**
     * Turns a paper into a structured record, tags its strategy, and stores it.
     *
     * @param paper the paper to analyse; must not be null
     * @return the stored analysis
     * @throws AgentException if the paper has no analysable text, per UC04 3a
     * @throws StorageException if the analysis could not be written. The
     *     analysis is kept in memory and is readable through
     *     {@link #getAnalyses()} — unlike a failed profile save, it is not
     *     rolled back, because discarding it would throw away a model call the
     *     researcher has already paid for.
     */
    public PaperAnalysis analyzePaper(PaperRecord paper)
            throws AgentException, StorageException {
        if (paper == null) {
            throw new IllegalArgumentException("paper must not be null");
        }
        PaperAnalysis result = (PaperAnalysis) analysis.run(paper);
        memory.addPaperAnalysis(result);
        try {
            store.save(memory);
        } catch (IOException e) {
            throw new StorageException(
                    "the analysis was produced but could not be written to " + store.getFile(), e);
        }
        return result;
    }

    /**
     * Analyses a paper identified by its source id, fetching it first.
     *
     * <p>Exists for the CLI, which has no feed to select from: it resolves the
     * id through whichever {@code LiteratureSource} is configured rather than
     * holding state between invocations.
     *
     * @throws AgentException if no paper with that id could be found
     */
    public PaperAnalysis analyzePaperById(String paperId)
            throws AgentException, StorageException {
        if (paperId == null || paperId.isBlank()) {
            throw new IllegalArgumentException("paperId must not be null or blank");
        }
        List<PaperRecord> found;
        try {
            found = tools.search("id:" + paperId.strip(), SearchIntent.MOST_RELEVANT);
        } catch (LiteratureUnavailableException e) {
            throw new AgentException("could not look up " + paperId + ": " + e.getMessage(), e);
        }
        if (found.isEmpty()) {
            throw new AgentException("no paper found with id " + paperId);
        }
        return analyzePaper(found.get(0));
    }

    /**
     * Re-runs F05 on a stored analysis, without another extraction.
     *
     * @return the settled strategy tags
     */
    public List<String> classifyPaper(PaperAnalysis stored) {
        return analysis.classifyExisting(stored);
    }

    /** @return every analysis stored for this project, read-only */
    public List<PaperAnalysis> getAnalyses() {
        return memory.getPaperAnalyses();
    }

    // --- lifecycle -----------------------------------------------------------

    /**
     * Loads a stored project, if there is one.
     *
     * @return true when a project was loaded; false on a first run or when the
     *     file is unreadable, which is reported rather than thrown so the
     *     application always starts
     */
    public boolean loadStoredProject() {
        return store.load(memory);
    }

    /** @return the file the project is stored in, for the UI to show */
    public Path getStorageLocation() {
        return store.getFile();
    }

    /** @return the tool layer, for the agents this facade will hold. */
    protected ToolManager tools() {
        return tools;
    }
}
