package ca.yorku.eecs3311.consultant.literature;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * {@inheritDoc}
     *
     * <p>Honest about not having a paper. The real bug this interface exists to
     * prevent was a lookup that returned <em>something</em> for an id it did not
     * recognise, so this double must never do that either.
     */
    @Override
    public Optional<PaperRecord> findById(String id) throws LiteratureUnavailableException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        queriesSeen.add("id:" + id.strip());
        if (unavailableReason != null) {
            throw new LiteratureUnavailableException(unavailableReason);
        }
        return results.stream().filter(p -> p.getId().equalsIgnoreCase(id.strip())).findFirst();
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
