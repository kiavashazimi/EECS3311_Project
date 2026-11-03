package ca.yorku.eecs3311.consultant.literature;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splitting and relaxing the query syntax that {@link LiteratureSource}
 * defines — whitespace-separated terms, ANDed, with a {@code "quoted phrase"}
 * counting as one term.
 *
 * <p>Lives beside the interface rather than inside a source, because the syntax
 * is part of the interface's contract and a caller that wants to relax a query
 * must not have to know which source will run it.
 */
public final class QueryTerms {

    private static final Pattern TERM = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    private QueryTerms() {
    }

    /**
     * Splits a query into terms, keeping a quoted phrase whole.
     *
     * <p>An unclosed quote is treated as running to the end rather than
     * rejected: these queries are written by a language model, and losing a
     * search to a missing quote is worse than searching one term too broadly.
     */
    public static List<String> split(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.strip();
        if (q.chars().filter(c -> c == '"').count() % 2 != 0) {
            q = q + '"';
        }

        List<String> terms = new ArrayList<>();
        Matcher m = TERM.matcher(q);
        while (m.find()) {
            String phrase = m.group(1);
            terms.add(phrase != null ? '"' + phrase + '"' : m.group(2));
        }
        return terms;
    }

    /**
     * Drops the last term, widening the query.
     *
     * <p>Needed because terms are ANDed: every one has to appear in the
     * abstract, so a query collapses to nothing quickly. Measured on arXiv, one
     * real query went 5 papers at three terms, 1 at four, and 0 at five. A
     * search that returns nothing is better answered by relaxing it than by
     * telling the researcher there is no such work.
     *
     * <p>The last term goes first because a query is normally written with its
     * subject at the front and its qualifiers after it.
     *
     * @return the shortened query, or an empty string when nothing is left to
     *     drop — a one-term query cannot be widened any further
     */
    public static String dropLastTerm(String query) {
        List<String> terms = split(query);
        return terms.size() <= 1 ? "" : String.join(" ", terms.subList(0, terms.size() - 1));
    }

    /** @return how many terms the query has */
    public static int count(String query) {
        return split(query).size();
    }
}
