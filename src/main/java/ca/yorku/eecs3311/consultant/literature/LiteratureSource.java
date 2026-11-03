package ca.yorku.eecs3311.consultant.literature;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import java.util.List;

/**
 * One way of searching the literature — the STRATEGY of section 4 of the
 * Stage 1 report.
 *
 * <p>{@code ToolManager} holds one of these and delegates to it without
 * knowing which it is, so adding or switching a source (for coverage, or
 * because one is rate-limited) is a configuration change rather than an edit
 * to any agent.
 */
public interface LiteratureSource {

    /**
     * Searches for papers matching {@code query}, best matches first.
     *
     * @param query the query; must not be null or blank
     * @return matching papers, and an empty list when there are no matches —
     *     never null
     * @throws LiteratureUnavailableException if the source cannot be reached
     *     or its reply cannot be read
     * @throws IllegalArgumentException if {@code query} is null or blank
     */
    default List<PaperRecord> search(String query) throws LiteratureUnavailableException {
        return search(query, SearchIntent.MOST_RELEVANT);
    }

    /**
     * Searches for papers matching {@code query} in the order {@code intent}
     * asks for.
     *
     * <p>A query is whitespace-separated terms, ANDed together. Two forms get
     * special treatment, both of which an LLM-written query is expected to use:
     *
     * <ul>
     *   <li>{@code "a quoted phrase"} stays one term, so {@code "chest X-ray"}
     *       is not searched as {@code chest} AND {@code X-ray}.
     *   <li>{@code field:value} is passed through, so {@code cat:eess.IV} can
     *       narrow a search to a subject area.
     * </ul>
     *
     * Everything else is matched against the abstract.
     *
     * @param query the query; must not be null, blank, or free of searchable words
     * @param intent whether the caller wants the newest or the best matches
     * @return matching papers, and an empty list when there are no matches
     * @throws LiteratureUnavailableException if the source cannot be reached
     * @throws IllegalArgumentException if the query has no searchable words
     */
    List<PaperRecord> search(String query, SearchIntent intent)
            throws LiteratureUnavailableException;

    /** @return a short name for this source, for logs and the UI. */
    String name();
}
