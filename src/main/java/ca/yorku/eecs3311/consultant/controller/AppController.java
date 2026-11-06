package ca.yorku.eecs3311.consultant.controller;

import ca.yorku.eecs3311.consultant.agent.AgentException;
import ca.yorku.eecs3311.consultant.agent.DigestBuilder;
import ca.yorku.eecs3311.consultant.agent.GroundedAnswerAgent;
import ca.yorku.eecs3311.consultant.agent.PaperAnalysisAgent;
import ca.yorku.eecs3311.consultant.agent.PaperDiscoveryAgent;
import ca.yorku.eecs3311.consultant.agent.PaperFeedListener;
import ca.yorku.eecs3311.consultant.agent.ProjectContextAgent;
import ca.yorku.eecs3311.consultant.agent.RepositoryAnalysisAgent;
import ca.yorku.eecs3311.consultant.agent.ResearchConsultantAgent;
import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.context.ClaudeCodeContextProvider;
import ca.yorku.eecs3311.consultant.context.FileBasedContextProvider;
import ca.yorku.eecs3311.consultant.domain.Answer;
import ca.yorku.eecs3311.consultant.domain.ComparisonResult;
import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.domain.Digest;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.domain.RepoSummary;
import ca.yorku.eecs3311.consultant.domain.StrategyNode;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import ca.yorku.eecs3311.consultant.literature.LiteratureUnavailableException;
import ca.yorku.eecs3311.consultant.literature.SearchIntent;
import ca.yorku.eecs3311.consultant.memory.MemoryStore;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private final ResearchConsultantAgent consultant;
    private final ProjectContextAgent context;
    private final RepositoryAnalysisAgent repository;
    private final GroundedAnswerAgent answering;
    private final DigestBuilder digestBuilder;

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
        this.consultant = new ResearchConsultantAgent(tools);
        // the coding-agent adapter is probed first and falls back to files
        this.context = new ProjectContextAgent(tools, List.of(
                new ClaudeCodeContextProvider(), new FileBasedContextProvider()));
        this.repository = new RepositoryAnalysisAgent(tools);
        this.answering = new GroundedAnswerAgent(tools);
        // registered here, not by a client: the digest has to be collecting
        // during every discovery, whichever client started it
        this.digestBuilder = new DigestBuilder(tools, memory);
        this.discovery.addListener(digestBuilder);
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
        List<RankedPaper> ranked = (List<RankedPaper>) discovery.run(memory.getProfile());
        rememberListing(ranked);
        return ranked;
    }

    /**
     * Stores the display order so a client can later resolve a row number
     * back to a paper id.
     *
     * <p>A failed write is swallowed deliberately, and only here. Row numbers
     * are a convenience; the papers themselves are the result of the call and
     * are already in hand. Turning a disk problem into an exception would
     * discard a search the researcher has paid a model call for, in order to
     * report that a shortcut will not work.
     */
    private void rememberListing(List<RankedPaper> ranked) {
        List<String> ids = new ArrayList<>();
        for (RankedPaper r : ranked) {
            ids.add(r.paper().getId());
        }
        memory.saveLastListing(ids);
        try {
            store.save(memory);
        } catch (IOException e) {
            // the listing is still correct in memory for this process
        }
    }

    /**
     * @return the paper ids of the last listing shown, in display order, so a
     *     client can turn the position a researcher read off the screen back
     *     into an id. Empty when no discovery has run yet
     */
    public List<String> getLastListing() {
        return memory.getLastListing();
    }

    /**
     * Registers something that wants to hear about each discovered paper — the
     * GUI feed, the digest builder, anything added later.
     */
    public void addPaperFeedListener(PaperFeedListener listener) {
        discovery.addListener(listener);
    }

    /**
     * @return why the last discovery could not rank its results, blank when it
     *     ranked them. "Unavailable" and "unreadable" are different problems
     *     and the clients say which
     */
    public String getRankingFailure() {
        return discovery.getRankingFailure();
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
        String wanted = paperId.strip();
        PaperRecord found;
        try {
            found = tools.findPaper(wanted).orElse(null);
        } catch (LiteratureUnavailableException e) {
            throw new AgentException("could not look up " + wanted + ": " + e.getMessage(), e);
        }
        if (found == null) {
            throw new AgentException("no paper found with id " + wanted);
        }
        if (!sameId(found.getId(), wanted)) {
            // Belt and braces behind findById. A free-text lookup once returned
            // an unrelated paper for an id the source did not understand, and it
            // was analysed and stored as the paper that had been asked for.
            // Analysing the wrong paper silently is worse than not analysing.
            throw new AgentException("no paper found with id " + wanted
                    + " - the source returned " + found.getId() + " instead");
        }
        return analyzePaper(found);
    }

    /** arXiv appends a version, so 2304.09218 and 2304.09218v1 are one paper. */
    private static boolean sameId(String a, String b) {
        return a != null && b != null
                && a.replaceAll("v\\d+$", "").equalsIgnoreCase(b.replaceAll("v\\d+$", ""));
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

    // --- F06 / F07: compare a paper to this project (UC05 includes UC06) ----

    /**
     * Explains how a paper relates to this project, and whether the two sets of
     * numbers can fairly be compared.
     *
     * <p>F07 has no facade method of its own, by design: the comparability
     * check runs inside this call, which is what the {@code <<include>>} from
     * UC05 to UC06 means. It is also computed <em>before</em> the model writes
     * anything, so a fluent narrative cannot talk the researcher into a
     * comparison the data does not support.
     *
     * @param stored an analysis already produced by {@link #analyzePaper}
     * @return the comparison, with the verdict and mismatch list set even when
     *     the narrative is missing
     * @throws AgentException if the comparison could not be run
     */
    public ComparisonResult comparePaper(PaperAnalysis stored) throws AgentException {
        if (stored == null) {
            throw new IllegalArgumentException("analysis must not be null");
        }
        return consultant.compare(stored, memory.getProfile());
    }

    /**
     * Finds a stored analysis by its paper id.
     *
     * @return the analysis, or empty when that paper has not been analysed yet
     */
    public java.util.Optional<PaperAnalysis> findAnalysis(String paperId) {
        return memory.findAnalysis(paperId);
    }

    // --- F02 / F10: project context (UC02, UC09, SD02) ----------------------

    /**
     * Reads the researcher's own project directory into memory.
     *
     * <p>Which provider answers is decided at runtime: a directory with
     * coding-agent context is read that way, anything else falls back to files,
     * and the snapshot records which supplied the facts so the UI can say.
     *
     * @param projectPath the directory to import
     * @return the facts, and prose about them which may be missing
     * @throws AgentException if the directory cannot be read — nothing is stored
     * @throws StorageException if the snapshot could not be written; it is kept
     *     in memory
     */
    public ProjectContextAgent.ImportedContext importProjectContext(String projectPath)
            throws AgentException, StorageException {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath must not be null or blank");
        }
        ContextSnapshot previous = memory.getLastSnapshot();
        ProjectContextAgent.ImportedContext imported =
                (ProjectContextAgent.ImportedContext) context.run(
                        new ProjectContextAgent.Import(
                                Path.of(projectPath.strip()),
                                previous.getScannedAt().isBlank() ? null : previous));

        memory.saveSnapshot(imported.snapshot());
        try {
            store.save(memory);
        } catch (IOException e) {
            throw new StorageException(
                    "the project was read but could not be written to " + store.getFile(), e);
        }
        return imported;
    }

    /**
     * Re-scans the stored project path and says what changed.
     *
     * @return prose describing the diff, or "" when the model is unavailable
     * @throws AgentException if no project has been imported yet, or the path is
     *     no longer readable — the previous snapshot is retained either way
     */
    public String summarizeProjectChanges() throws AgentException, StorageException {
        ContextSnapshot previous = memory.getLastSnapshot();
        if (previous.getScannedAt().isBlank()) {
            // UC09 2a: point them at the import rather than showing an empty panel
            throw new AgentException(
                    "no project has been imported yet - import one first to have something "
                            + "to compare against");
        }

        ProjectContextAgent.ImportedContext current =
                (ProjectContextAgent.ImportedContext) context.run(
                        new ProjectContextAgent.Import(
                                Path.of(previous.getProjectPath()), previous));

        String summary = context.summarizeChanges(previous, current.snapshot());
        memory.saveSnapshot(current.snapshot());
        try {
            store.save(memory);
        } catch (IOException e) {
            throw new StorageException("the new snapshot could not be written", e);
        }
        return summary;
    }

    /** @return the deterministic change list, which needs no model */
    public List<String> projectChanges() throws AgentException {
        ContextSnapshot previous = memory.getLastSnapshot();
        if (previous.getScannedAt().isBlank()) {
            throw new AgentException("no project has been imported yet");
        }
        ProjectContextAgent.ImportedContext current =
                (ProjectContextAgent.ImportedContext) context.run(
                        new ProjectContextAgent.Import(
                                Path.of(previous.getProjectPath()), previous));
        return context.changesBetween(previous, current.snapshot());
    }

    /** @return the last imported snapshot; empty rather than null before the first */
    public ContextSnapshot getLastSnapshot() {
        return memory.getLastSnapshot();
    }

    // --- F08: has anyone tried this (UC07, SD06) ----------------------------

    /**
     * Looks for prior evidence of an idea, in the live literature and in the
     * papers this project has already analysed.
     *
     * <p>Both sources matter: the closest prior work is often a paper the
     * researcher read months ago and forgot, which a literature search alone
     * would return as if it were new.
     *
     * @param idea the idea, in the researcher's own words
     * @return the closest evidence with its sources and a confidence note.
     *     {@code isGrounded()} false means these searches found nothing — never
     *     that the idea is unexplored, which two searches cannot establish
     * @throws AgentException if the idea is unusable
     */
    public Answer searchPriorWork(String idea) throws AgentException {
        return answering.searchPriorWork(idea, memory.getProfile(), memory.getPaperAnalyses());
    }

    // --- F11: ask a project-aware question (UC10, SD06) ---------------------

    /**
     * Answers a free-form question from stored project data only.
     *
     * <p>Not persisted: an answer is about the question asked at the time, and
     * a stored one would go stale as soon as another paper was analysed.
     *
     * @param question the question, in the researcher's own words
     * @return the answer with the records it rests on. {@code isGrounded()}
     *     false means nothing relevant is stored, and the text says so rather
     *     than answering from the model's general knowledge
     * @throws AgentException if the question is unusable
     */
    public Answer ask(String question) throws AgentException {
        return answering.ask(question, memory.getProfile(), memory.getPaperAnalyses());
    }

    // --- F12: the digest (UC11, SD08) ---------------------------------------

    /**
     * Assembles the digest from what the builder has been told since the last
     * one.
     *
     * <p>Nothing is queried here. {@code DigestBuilder} has been receiving each
     * discovered paper through the Observer all along, which is the difference
     * between being told and having to ask.
     *
     * @return the digest. {@code isEmpty()} means nothing new, which the caller
     *     reports as "nothing new since {@link #getLastDigestAt()}" rather than
     *     as a blank view
     * @throws StorageException if the digest was assembled but the record of
     *     having reported it could not be written. The digest itself is
     *     returned to the caller by then, so the risk is a repeat next time,
     *     not a loss
     */
    public Digest buildDigest() throws StorageException {
        Digest digest = digestBuilder.buildDigest();
        try {
            store.save(memory);
        } catch (IOException e) {
            throw new StorageException(
                    "the digest was assembled but could not be recorded in "
                            + store.getFile(), e);
        }
        return digest;
    }

    /** @return when the last digest ran, blank if none ever has */
    public String getLastDigestAt() {
        return memory.getLastDigestAt();
    }

    /** @return how many discovered papers are waiting to be reported */
    public int papersAwaitingDigest() {
        return digestBuilder.pendingCount();
    }

    // --- F13: the solution landscape (UC12) ---------------------------------

    /**
     * Builds the tree of solution approaches from the stored analyses.
     *
     * <p>Derived state, deliberately not persisted: it is computed from the
     * analyses, and storing generated prose beside them would let a saved tree
     * silently disagree with the papers it came from. Rebuilding costs one
     * model call.
     *
     * @return the root. Empty families are pruned, so an empty tree means
     *     nothing has been analysed yet.
     */
    public StrategyNode buildSolutionLandscape() {
        return consultant.buildLandscape(memory.getPaperAnalyses(), memory.getProfile());
    }

    /** @return one line about a branch, usable at any depth */
    public String describeBranch(StrategyNode node) {
        return consultant.summarizeBranch(node);
    }

    /** @return true when there are too few papers for sub-branch summaries */
    public boolean tooFewPapersForLandscape() {
        return ResearchConsultantAgent.tooFewPapersForBranchSummaries(
                memory.getPaperAnalyses().size());
    }

    // --- F09: inspect a paper's repository (UC08) ---------------------------

    /**
     * Judges whether a paper's code exists and looks reusable.
     *
     * <p>Only repository metadata is read. Code is never downloaded and never
     * executed — a paper's repository is arbitrary third-party code, and
     * running it to judge reproducibility would point a remote-code-execution
     * hole at the researcher's own machine.
     *
     * <p>Not persisted, for the same reason as the context summary and the
     * landscape: `readmeQuality` and `note` are generated, and a stored
     * judgement could silently disagree with a repository that has since
     * changed. Re-checking costs two GitHub calls and one model call.
     *
     * @param stored an analysis already produced by {@link #analyzePaper}
     * @return the summary. A paper with no repository, or one that cannot be
     *     reached, comes back as an unavailable summary carrying the reason
     *     rather than as an error — UC08 flows 2a and 3a.
     */
    public RepoSummary checkRepository(PaperAnalysis stored) throws AgentException {
        if (stored == null) {
            throw new IllegalArgumentException("analysis must not be null");
        }
        return repository.checkRepository(stored);
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

    /**
     * Discards the whole project — profile, analyses, snapshot and listings —
     * and writes the empty state to disk.
     *
     * <p>Not a Stage 1 feature. {@code ProjectMemory.clear()} existed for
     * tests, and its javadoc already named this as its other purpose, but
     * nothing reached it: switching to a new research question left every
     * earlier analysis in memory, so F13 kept mapping the previous project's
     * solution families and there was no way to start over short of deleting
     * the file by hand.
     *
     * <p>The write is part of the operation, not an afterthought. Clearing
     * memory alone would leave the old project on disk, and the next process
     * would load it straight back.
     *
     * <p>Irreversible, so both clients confirm before calling it.
     *
     * @throws StorageException if the cleared state cannot be written, with
     *     memory already cleared — the caller must say so rather than imply
     *     the old project is still intact
     */
    public void startNewProject() throws StorageException {
        memory.clear();
        try {
            store.save(memory);
        } catch (IOException e) {
            throw new StorageException(
                    "the project was cleared here, but " + store.getFile()
                            + " could not be rewritten", e);
        }
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
