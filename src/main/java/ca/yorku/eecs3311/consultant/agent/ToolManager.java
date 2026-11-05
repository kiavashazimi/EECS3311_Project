package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.analysis.PaperParser;
import ca.yorku.eecs3311.consultant.analysis.UnparseablePaperException;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.RepoMetadata;
import ca.yorku.eecs3311.consultant.literature.LiteratureSource;
import ca.yorku.eecs3311.consultant.literature.LiteratureUnavailableException;
import ca.yorku.eecs3311.consultant.literature.SearchIntent;
import ca.yorku.eecs3311.consultant.llm.LLMClient;
import ca.yorku.eecs3311.consultant.repo.GitHubClient;
import ca.yorku.eecs3311.consultant.repo.RepositoryUnavailableException;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The single doorway through which agents reach the outside world — the LLM
 * now, and the literature sources, GitHub and the paper parser as those
 * arrive.
 *
 * <p>Two jobs. It holds the tools so no agent constructs its own, which is
 * what lets one {@code LiteratureSource} be swapped for another without
 * touching an agent. And it records every call, so an agent cannot quietly
 * fail to report which tools it used — section 4 of the Stage 1 report names
 * that risk as the reason the agent loop lives in one place.
 */
public class ToolManager {

    private final LLMClient llm;
    private final List<String> callLog = new ArrayList<>();

    /** The current strategy. Settable, not final: swappable at runtime. */
    private LiteratureSource literatureSource;
    private LiteratureSource fallbackLiteratureSource;

    /** Composed, not injected: the Stage 1 diagram owns these by composition. */
    private final PaperParser parser = new PaperParser();
    private GitHubClient github = new GitHubClient();

    public ToolManager(LLMClient llm) {
        if (llm == null) {
            throw new IllegalArgumentException("llm must not be null");
        }
        this.llm = llm;
    }

    /**
     * Sends a prompt to the language model.
     *
     * @param prompt the prompt; must not be null or blank
     * @return the model's reply
     * @throws LLMUnavailableException if the model cannot be reached; the
     *     reason is passed through unchanged so the agent can show it
     */
    public String callLLM(String prompt) throws LLMUnavailableException {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be null or blank");
        }
        try {
            String reply = llm.generate(prompt);
            callLog.add("llm: " + summarise(prompt) + " -> " + reply.length() + " chars");
            return reply;
        } catch (LLMUnavailableException e) {
            // logged before rethrowing: a call that failed is the one most
            // worth seeing in the record of what the agent tried
            callLog.add("llm: " + summarise(prompt) + " -> failed (" + e.getMessage() + ")");
            throw e;
        }
    }

    /**
     * Chooses which literature API to search. This is the Strategy being
     * swapped: no agent is touched, and nothing here knows which source it is.
     *
     * @param source the source to use from now on; must not be null
     */
    public void setLiteratureSource(LiteratureSource source) {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        this.literatureSource = source;
    }

    /** @return the source currently in use, or null if none has been set */
    public LiteratureSource getLiteratureSource() {
        return literatureSource;
    }

    /**
     * Searches the literature through whichever source is currently set.
     *
     * @param query free text; must not be null or blank
     * @return the matching papers, empty when there are none
     * @throws LiteratureUnavailableException if the source cannot be reached
     * @throws IllegalStateException if no source has been set
     */
    public List<PaperRecord> search(String query) throws LiteratureUnavailableException {
        return search(query, SearchIntent.MOST_RELEVANT);
    }

    /**
     * Searches the literature in the order {@code intent} asks for — newest
     * first for discovering new work, best matches first for prior work.
     *
     * @param query free text; must not be null or blank
     * @param intent the ordering the caller needs
     * @return the matching papers, empty when there are none
     * @throws LiteratureUnavailableException if the source cannot be reached
     * @throws IllegalStateException if no source has been set
     */
    public List<PaperRecord> search(String query, SearchIntent intent)
            throws LiteratureUnavailableException {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }
        if (intent == null) {
            throw new IllegalArgumentException("intent must not be null");
        }
        if (literatureSource == null) {
            throw new IllegalStateException(
                    "no literature source set - call setLiteratureSource() first");
        }
        String tool = literatureSource.name();
        try {
            List<PaperRecord> found = literatureSource.search(query, intent);
            callLog.add(tool + " (" + intent + "): " + summarise(query)
                    + " -> " + found.size() + " papers");
            return found;
        } catch (LiteratureUnavailableException e) {
            callLog.add(tool + " (" + intent + "): " + summarise(query)
                    + " -> failed (" + e.getMessage() + ")");
            if (fallbackLiteratureSource == null) {
                throw e;
            }
            return searchFallback(query, intent, e);
        }
    }

    /**
     * Searches the second source after the first could not be reached.
     *
     * <p>This is the Strategy making a real runtime decision rather than a
     * configuration-time one. Semantic Scholar ranks a whole question but rate
     * limits hard — HTTP 429 is its ordinary failure, not an exceptional one —
     * while arXiv is always available and only does ANDed keywords. Neither is
     * simply better, so the choice has to be made when the call fails.
     *
     * <p>The switch is recorded in the call log. A fallback the researcher
     * cannot see would make results from a different source, with different
     * coverage and a different ranking, look like results from the one they
     * configured.
     *
     * @param cause why the primary source failed, kept so that a failure of
     *     both reports the first reason and not only the second
     */
    private List<PaperRecord> searchFallback(String query, SearchIntent intent,
            LiteratureUnavailableException cause) throws LiteratureUnavailableException {
        String tool = fallbackLiteratureSource.name();
        callLog.add("falling back to " + tool + ": " + literatureSource.name()
                + " was unavailable");
        try {
            List<PaperRecord> found = fallbackLiteratureSource.search(query, intent);
            callLog.add(tool + " (" + intent + "): " + summarise(query)
                    + " -> " + found.size() + " papers");
            return found;
        } catch (LiteratureUnavailableException e) {
            callLog.add(tool + " (" + intent + "): " + summarise(query)
                    + " -> failed (" + e.getMessage() + ")");
            throw new LiteratureUnavailableException(
                    "both sources failed - " + literatureSource.name() + ": "
                            + cause.getMessage() + "; " + tool + ": " + e.getMessage(), e);
        }
    }

    /**
     * Looks one paper up by id, through whichever source is configured.
     *
     * @param id the identifier, never null or blank
     * @return the paper, or empty when no configured source has it
     * @throws LiteratureUnavailableException if no source could be reached
     */
    public Optional<PaperRecord> findPaper(String id) throws LiteratureUnavailableException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (literatureSource == null) {
            throw new IllegalStateException(
                    "no literature source set - call setLiteratureSource() first");
        }
        String tool = literatureSource.name();
        try {
            Optional<PaperRecord> found = literatureSource.findById(id.strip());
            callLog.add(tool + " lookup: " + id.strip() + " -> "
                    + (found.isPresent() ? "found" : "not found"));
            if (found.isPresent() || fallbackLiteratureSource == null) {
                return found;
            }
        } catch (LiteratureUnavailableException e) {
            callLog.add(tool + " lookup: " + id.strip() + " -> failed (" + e.getMessage() + ")");
            if (fallbackLiteratureSource == null) {
                throw e;
            }
        }
        // the other source may be the one that issued this id, or may simply
        // carry the same paper
        String other = fallbackLiteratureSource.name();
        callLog.add("falling back to " + other + " for lookup " + id.strip());
        Optional<PaperRecord> found = fallbackLiteratureSource.findById(id.strip());
        callLog.add(other + " lookup: " + id.strip() + " -> "
                + (found.isPresent() ? "found" : "not found"));
        return found;
    }

    /**
     * Sets the source to use when the primary one cannot be reached.
     *
     * @param source the second source, or null to disable falling back
     */
    public void setFallbackLiteratureSource(LiteratureSource source) {
        this.fallbackLiteratureSource = source;
    }

    /** @return the fallback source, or null when there is none */
    public LiteratureSource getFallbackLiteratureSource() {
        return fallbackLiteratureSource;
    }

    /**
     * Reads the analysable text of a paper.
     *
     * @param paper the paper to read
     * @return its text
     * @throws UnparseablePaperException when the paper has no text to analyse,
     *     so the caller flags it rather than analysing a title
     */
    public String parsePaper(PaperRecord paper) throws UnparseablePaperException {
        if (paper == null) {
            throw new IllegalArgumentException("paper must not be null");
        }
        try {
            String text = parser.parse(paper);
            callLog.add("parser: " + paper.getId() + " -> " + text.length() + " chars");
            return text;
        } catch (UnparseablePaperException e) {
            callLog.add("parser: " + paper.getId() + " -> failed (" + e.getMessage() + ")");
            throw e;
        }
    }

    /**
     * Reads a repository's metadata. Code is never downloaded or executed.
     *
     * @param repoUrl the repository URL
     * @return its metadata
     * @throws RepositoryUnavailableException if it cannot be read, with the reason
     */
    public RepoMetadata fetchRepo(String repoUrl) throws RepositoryUnavailableException {
        try {
            RepoMetadata metadata = github.fetchRepo(repoUrl);
            callLog.add("github: " + repoUrl + " -> "
                    + metadata.getTopLevelFiles().size() + " top-level entries");
            return metadata;
        } catch (RepositoryUnavailableException e) {
            callLog.add("github: " + repoUrl + " -> failed (" + e.getMessage() + ")");
            throw e;
        }
    }

    /** Replaces the GitHub client, so a token can be supplied or a fake injected. */
    public void setGitHubClient(GitHubClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        this.github = client;
    }

    /** @return every tool call made so far, oldest first, read-only */
    public List<String> getCallLog() {
        return List.copyOf(callLog);
    }

    /** Drops the record, for a fresh run. */
    public void clearCallLog() {
        callLog.clear();
    }

    /** Keeps the log readable when a prompt is a whole paper. */
    private static String summarise(String prompt) {
        String oneLine = prompt.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 60 ? oneLine : oneLine.substring(0, 57) + "...";
    }
}
