package ca.yorku.eecs3311.consultant.analysis;

import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks which stored analyses are relevant to a question, deterministically.
 *
 * <p>This is the retrieval half of F08 and F11, and it runs before the model is
 * involved at all. It decides what the model is allowed to see, which is what
 * makes those features something other than a thin wrapper around a language
 * model: the answer is constrained by stored project data rather than produced
 * from the model's own knowledge (SD06).
 *
 * <p>Returning nothing is a first-class outcome, not a failure. When no stored
 * record matches, the caller must say so rather than let the model answer
 * anyway — for F08 that distinction is the difference between "no matching
 * evidence found" and the much stronger, unprovable "no one has tried this".
 */
public class EvidenceRetriever {

    /**
     * How many analyses may be handed to the model at once.
     *
     * <p>A prompt cannot carry the whole library, and a researcher cannot check
     * twenty citations. The cap is applied after ranking, so what survives is
     * the closest evidence rather than whatever happened to be stored first.
     */
    public static final int MAX_EVIDENCE = 6;

    /** Terms too common to carry meaning; without these, every query matches everything. */
    private static final Set<String> STOPWORDS = Set.of(
            "about", "after", "again", "against", "already", "also", "another", "answer",
            "anyone", "anything", "around", "because", "been", "before", "being", "below",
            "between", "both", "could", "does", "doing", "done", "during", "each", "either",
            "else", "ever", "every", "from", "further", "give", "going", "have", "having",
            "here", "however", "into", "itself", "just", "like", "made", "make", "many",
            "might", "more", "most", "much", "must", "neither", "nothing", "once", "only",
            "other", "others", "ours", "over", "same", "should", "since", "some", "someone",
            "something", "such", "than", "that", "their", "them", "then", "there", "these",
            "they", "thing", "things", "this", "those", "through", "thus", "tried", "under",
            "until", "upon", "used", "using", "very", "want", "were", "what", "when", "where",
            "whether", "which", "while", "will", "with", "within", "without", "would", "your",
            "yours",
            // added after a live F08 run sent "instead" and "equally" to arXiv as
            // search terms, which are ANDed and so emptied the result set
            "across", "among", "instead", "overall", "rather", "toward", "towards", "whole");

    /** A term shorter than this is almost always a stopword or an artefact. */
    private static final int MIN_TERM_LENGTH = 4;

    /**
     * @param query what the researcher asked, never blank
     * @param stored every analysis in project memory
     * @return the matching analyses, closest first, at most {@link #MAX_EVIDENCE}.
     *     Empty when nothing matches, which the caller must report rather than
     *     work around
     * @throws IllegalArgumentException if the query is null or blank, because a
     *     blank query would otherwise match every paper and ground an answer in
     *     evidence the researcher never asked for
     */
    public List<PaperAnalysis> relevantTo(String query, List<PaperAnalysis> stored) {
        List<String> terms = termsOf(query);
        if (stored == null || stored.isEmpty() || terms.isEmpty()) {
            return List.of();
        }

        int needed = minimumOverlap(terms.size());
        List<Scored> scored = new ArrayList<>();
        for (PaperAnalysis analysis : stored) {
            int hits = overlap(terms, haystack(analysis));
            if (hits >= needed) {
                scored.add(new Scored(analysis, hits));
            }
        }
        // ties break on title so the same question gives the same evidence twice
        scored.sort(Comparator.comparingInt((Scored s) -> -s.hits)
                .thenComparing(s -> s.analysis.getPaper().getTitle()));

        List<PaperAnalysis> best = new ArrayList<>();
        for (Scored s : scored.subList(0, Math.min(MAX_EVIDENCE, scored.size()))) {
            best.add(s.analysis);
        }
        return best;
    }

    /**
     * The same bar, applied to papers that have only a title and an abstract.
     *
     * <p>Results from a broadened literature query have to clear it too. A
     * search relaxed to "oversampling synthetic" returned ten papers on traffic
     * mode choice, link prediction and imbalanced text classification. Listing
     * those as the evidence an answer rests on gives weak evidence the
     * appearance of evidence, which is the one thing F08 is supposed not to do.
     *
     * @param query the idea as originally described, not the relaxed query
     * @param found what the literature source returned
     * @return the ones that match the idea, closest first, at most
     *     {@link #MAX_EVIDENCE}
     */
    public List<PaperRecord> papersRelevantTo(String query, List<PaperRecord> found) {
        List<String> terms = termsOf(query);
        if (found == null || found.isEmpty() || terms.isEmpty()) {
            return List.of();
        }
        int needed = minimumOverlap(terms.size());

        List<ScoredPaper> scored = new ArrayList<>();
        for (PaperRecord paper : found) {
            String text = (paper.getTitle() + " " + paper.getSummary()).toLowerCase();
            int hits = overlap(terms, text);
            if (hits >= needed) {
                scored.add(new ScoredPaper(paper, hits));
            }
        }
        scored.sort(Comparator.comparingInt((ScoredPaper s) -> -s.hits)
                .thenComparing(s -> s.paper.getTitle()));

        List<PaperRecord> best = new ArrayList<>();
        for (ScoredPaper s : scored.subList(0, Math.min(MAX_EVIDENCE, scored.size()))) {
            best.add(s.paper);
        }
        return best;
    }

    /**
     * @param query what the researcher asked
     * @return the meaningful terms it was reduced to, so a client can show what
     *     was actually searched for when nothing came back
     * @throws IllegalArgumentException if the query is null or blank
     */
    public List<String> termsOf(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String word : query.toLowerCase().split("[^a-z0-9]+")) {
            if (word.length() >= MIN_TERM_LENGTH && !STOPWORDS.contains(word)) {
                terms.add(word);
            }
        }
        return List.copyOf(terms);
    }

    /**
     * How many of the query's terms a paper must match to count as evidence.
     *
     * <p>One shared word is not evidence. Asked "reinforcement learning for
     * robot locomotion", a one-hit rule returned a paper on contrastive
     * learning for chest X-rays, because both contain "learning" — and that
     * paper would then have been handed to the model as the retrieved context
     * for an answer about robots.
     *
     * <p>A third of the terms, rounded up, scales with the question: a
     * two-word query still matches on one term, while a long one has to be
     * covered more broadly before a paper qualifies.
     */
    private int minimumOverlap(int termCount) {
        return Math.max(1, (termCount + 2) / 3);
    }

    /** Every field a question could reasonably be about, as one lowercase string. */
    private String haystack(PaperAnalysis a) {
        StringBuilder b = new StringBuilder();
        b.append(a.getPaper().getTitle()).append(' ')
                .append(a.getPaper().getVenue()).append(' ')
                .append(a.getProblem()).append(' ')
                .append(a.getMethod()).append(' ')
                .append(a.getDataset()).append(' ')
                .append(a.getModel()).append(' ')
                .append(a.getResults()).append(' ')
                .append(a.getLimitations()).append(' ')
                .append(String.join(" ", a.getMetrics())).append(' ')
                .append(String.join(" ", a.getStrategyTags()));
        return b.toString().toLowerCase();
    }

    /**
     * Counts how many distinct query terms appear in the text.
     *
     * <p>A trailing {@code s} is tolerated in both directions: a researcher
     * types "diffusion models" and the paper says "diffusion model". Matching
     * only the exact form made an earlier feature miss papers it had clearly
     * found relevant.
     */
    private int overlap(List<String> terms, String text) {
        int hits = 0;
        for (String term : terms) {
            String singular = term.endsWith("s") ? term.substring(0, term.length() - 1) : term;
            if (text.contains(term) || text.contains(singular)) {
                hits++;
            }
        }
        return hits;
    }

    private record Scored(PaperAnalysis analysis, int hits) {
    }

    private record ScoredPaper(PaperRecord paper, int hits) {
    }
}
