package ca.yorku.eecs3311.consultant.literature;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import java.util.ArrayList;
import java.util.List;

/** A test double, so agent tests never reach a real literature API. */
public final class FakeLiteratureSource implements LiteratureSource {

    private final List<String> queriesSeen = new ArrayList<>();
    private final List<SearchIntent> intentsSeen = new ArrayList<>();
    private List<PaperRecord> results = new ArrayList<>();
    private int minTermsForResults = 0;
    private String unavailableReason;
    private final String name;

    public FakeLiteratureSource() {
        this("fake");
    }

    public FakeLiteratureSource(String name) {
        this.name = name;
    }

    public FakeLiteratureSource returning(PaperRecord... papers) {
        this.results = new ArrayList<>(List.of(papers));
        this.unavailableReason = null;
        return this;
    }

    /**
     * Returns nothing until the query has been relaxed to {@code terms} terms
     * or fewer, mimicking arXiv, where ANDed terms collapse the result set.
     */
    public FakeLiteratureSource emptyUntilRelaxedTo(int terms) {
        this.minTermsForResults = terms;
        return this;
    }

    public FakeLiteratureSource failWith(String reason) {
        this.unavailableReason = reason;
        return this;
    }

    @Override
    public List<PaperRecord> search(String query, SearchIntent intent)
            throws LiteratureUnavailableException {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query must not be null or blank");
        }
        queriesSeen.add(query);
        intentsSeen.add(intent);
        if (unavailableReason != null) {
            throw new LiteratureUnavailableException(unavailableReason);
        }
        if (minTermsForResults > 0 && QueryTerms.count(query) > minTermsForResults) {
            return List.of();
        }
        return List.copyOf(results);
    }

    @Override
    public String name() {
        return name;
    }

    public List<String> queriesSeen() {
        return List.copyOf(queriesSeen);
    }

    public List<SearchIntent> intentsSeen() {
        return List.copyOf(intentsSeen);
    }
}
