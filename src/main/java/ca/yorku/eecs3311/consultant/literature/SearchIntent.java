package ca.yorku.eecs3311.consultant.literature;

/**
 * Why a search is being run, which decides how results are ordered.
 *
 * <p>The two use cases want opposite orderings from the same method. UC03
 * discovers what is *new*, so it sorts by date. UC07 looks for prior work,
 * where a five-year-old paper can be the most important result, so it sorts by
 * relevance. Hardcoding either one makes the other feature quietly wrong.
 */
public enum SearchIntent {

    /** UC07 — prior work. Best matches first, regardless of age. */
    MOST_RELEVANT("relevance"),

    /** UC03 — new papers. Newest first. */
    NEWEST("submittedDate");

    private final String arxivSortKey;

    SearchIntent(String arxivSortKey) {
        this.arxivSortKey = arxivSortKey;
    }

    /** @return the value arXiv's {@code sortBy} parameter expects */
    public String arxivSortKey() {
        return arxivSortKey;
    }
}
