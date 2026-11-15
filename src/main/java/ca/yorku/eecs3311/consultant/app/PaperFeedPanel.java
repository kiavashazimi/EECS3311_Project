package ca.yorku.eecs3311.consultant.app;

import ca.yorku.eecs3311.consultant.agent.PaperFeedListener;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The GUI's paper feed — a CONCRETE OBSERVER of {@link PaperFeedListener}.
 *
 * <p>It redraws when told about a paper and knows nothing about how the paper
 * was found. The discovery agent holds no reference to this class, only to the
 * interface, which is the point of the pattern: a backend agent stays free of
 * UI code.
 */
public class PaperFeedPanel implements PaperFeedListener {

    private final ObservableList<String> rows = FXCollections.observableArrayList();
    private final ListView<String> list = new ListView<>(rows);
    private final Label heading = new Label("Papers");

    /** Parallel to {@link #rows}, so a selected row maps back to its paper. */
    private final List<RankedPaper> ranked = new ArrayList<>();

    /** Prefix that marks the list as older than what is shown beside it. */
    static final String STALE_PREFIX = "From your last discovery - ";

    /**
     * Marks a row whose paper already has an analysis.
     *
     * <p>Marked, not removed: "Compare to my project" and "Check code" are only
     * enabled for an analysed paper, so dropping analysed rows from the feed
     * would make both permanently unreachable, and would shift the row numbers
     * that {@code rc compare 2} refers to.
     */
    static final String ANALYSED_MARKER = "[analysed]";

    /** True once an answer unrelated to these papers was shown next to them. */
    private boolean stale;

    /**
     * Answers whether a paper has been analysed, so the row can say so.
     *
     * <p>A predicate rather than a reference to the controller: the feed is a
     * view and has no business reaching into application state, and the tests
     * can mark whatever they like without building a controller. The default
     * marks nothing, so a panel nobody configured behaves as it always did.
     */
    private Predicate<String> analysed = id -> false;

    public PaperFeedPanel() {
        list.setPlaceholder(new Label("No papers yet. Click \"Discover papers\"."));
        // a two-line cell, so the reason is readable rather than truncated
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setWrapText(true);
            }
        });
    }

    /** @return the node to place in the window */
    public VBox node() {
        VBox box = new VBox(6, heading, list);
        box.setPadding(new Insets(12, 0, 0, 0));
        VBox.setVgrow(list, Priority.ALWAYS);
        return box;
    }

    /**
     * Called by the discovery agent, which may be running on a background
     * thread — JavaFX nodes may only be touched on the FX thread, so the
     * update is handed over rather than applied directly.
     */
    @Override
    public void onNewPaper(PaperRecord paper) {
        Platform.runLater(() -> rows.add(paper.getDate() + "  " + paper.getTitle()));
    }

    /** @return how many papers the feed is showing */
    int size() {
        return ranked.size();
    }

    /** @return the paper on the selected row, or empty when nothing is selected */
    public Optional<PaperRecord> selectedPaper() {
        int index = list.getSelectionModel().getSelectedIndex();
        return index < 0 || index >= ranked.size()
                ? Optional.empty()
                : Optional.of(ranked.get(index).paper());
    }

    /**
     * Sets how the panel decides a paper has been analysed.
     *
     * @param analysed given a paper id, true when an analysis exists for it
     */
    public void setAnalysedCheck(Predicate<String> analysed) {
        this.analysed = analysed == null ? id -> false : analysed;
        rebuildRows();
    }

    /**
     * Redraws the rows so newly analysed papers pick up their marker.
     *
     * <p>Called after an analysis finishes. The rows are plain strings built
     * once, so nothing would change on screen otherwise, and the paper the
     * researcher just analysed would look untouched.
     */
    public void refreshAnalysedMarkers() {
        rebuildRows();
    }

    /** Runs {@code action} whenever the selection changes, to enable buttons. */
    public void onSelectionChanged(Runnable action) {
        list.getSelectionModel().selectedIndexProperty()
                .addListener((observable, before, after) -> action.run());
    }

    /** Replaces the feed with the ranked list, which carries the reasons. */
    public void showRanked(List<RankedPaper> papers) {
        showRanked(papers, "");
    }

    /**
     * @param whyUnranked why ranking did not happen, blank when it did. An
     *     unreachable model and an unreadable reply are different problems
     */
    public void showRanked(List<RankedPaper> papers, String whyUnranked) {
        boolean unranked = !papers.isEmpty() && papers.stream().allMatch(RankedPaper::isUnranked);
        String reason = whyUnranked == null || whyUnranked.isBlank()
                ? "the ranking did not run" : whyUnranked;
        heading.setText(papers.isEmpty()
                ? "Papers"
                : papers.size() + " papers" + (unranked
                        ? " - NOT RANKED, " + reason
                        : ", most useful first"));

        ranked.clear();
        ranked.addAll(papers);
        stale = false;
        rebuildRows();
    }

    /**
     * One row's text.
     *
     * <p>Static for the same reason {@code staleHeading} is: composing the row
     * needs no {@code ListView}, so pulling it out lets it be checked without a
     * started JavaFX toolkit.
     *
     * @param entry the ranked paper
     * @param analysed whether an analysis already exists for it
     * @return the two- or three-line cell text
     */
    static String rowText(RankedPaper entry, boolean analysed) {
        PaperRecord p = entry.paper();
        StringBuilder row = new StringBuilder();
        row.append(entry.isUnranked() ? "  -  " : String.format("%3d  ", entry.score()));
        row.append(p.getTitle());
        if (entry.closestPriorWork()) {
            row.append("   [closest prior work]");
        }
        if (analysed) {
            row.append("   ").append(ANALYSED_MARKER);
        }
        row.append("\n       ").append(p.getDate()).append("  ").append(p.getVenue());
        if (!entry.why().isBlank()) {
            row.append("\n       ").append(entry.why());
        }
        return row.toString();
    }

    /**
     * Rewrites every row from {@link #ranked}, keeping the selection.
     *
     * <p>Replacing the items clears the selection, and losing it after an
     * analysis would disable "Compare to my project" on the very paper the
     * researcher had just analysed.
     */
    private void rebuildRows() {
        int selected = list.getSelectionModel().getSelectedIndex();
        rows.clear();
        for (RankedPaper entry : ranked) {
            rows.add(rowText(entry, analysed.test(entry.paper().getId())));
        }
        if (selected >= 0 && selected < rows.size()) {
            list.getSelectionModel().select(selected);
        }
    }

    /**
     * Marks the feed as no longer matching what is displayed beside it.
     *
     * <p>F08 and F11 write their answer to the detail pane and never touch
     * this list, so the two panes can be read as one result: an answer about
     * face perception sitting next to a ranked list about chest X-ray
     * fairness. The heading says which is which rather than leaving the
     * researcher to guess.
     *
     * <p>Does nothing when the feed is empty — there is no stale list to
     * label — and nothing on a second call.
     */
    public void markStale() {
        if (stale || ranked.isEmpty()) {
            return;
        }
        stale = true;
        heading.setText(staleHeading(heading.getText()));
    }

    /**
     * @param current the heading as it reads now
     * @return it with the stale prefix, applied at most once so a repeated
     *     call cannot stack prefixes, and never replacing what the heading
     *     already said — a ranking failure stays visible
     */
    static String staleHeading(String current) {
        String text = current == null ? "" : current;
        return text.startsWith(STALE_PREFIX) ? text : STALE_PREFIX + text;
    }

    /** Shown while a search is running, so the window does not look frozen. */
    public void showSearching() {
        heading.setText("Searching...");
        rows.clear();
        ranked.clear();
        stale = false;
    }

    public void showError(String message) {
        heading.setText("Search failed - previous papers kept. " + message);
    }

    /** @return the rows currently displayed, for inspection */
    List<String> rows() {
        return List.copyOf(rows);
    }
}
