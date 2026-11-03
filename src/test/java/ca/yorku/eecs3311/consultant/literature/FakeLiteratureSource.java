package ca.yorku.eecs3311.consultant.literature;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import java.util.ArrayList;
import java.util.List;

/** A test double, so agent tests never reach a real literature API. */
public final class FakeLiteratureSource implements LiteratureSource {

    private final List<String> queriesSeen = new ArrayList<>();
    private final List<SearchIntent> intentsSeen = new ArrayList<>();
    private List<PaperRecord> results = new ArrayList<>();
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
