package ca.yorku.eecs3311.consultant.app;

import ca.yorku.eecs3311.consultant.agent.PaperFeedListener;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    /** True once an answer unrelated to these papers was shown next to them. */
    private boolean stale;

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

        rows.clear();
        ranked.clear();
        ranked.addAll(papers);
        stale = false;
        for (RankedPaper ranked : papers) {
            PaperRecord p = ranked.paper();
            StringBuilder row = new StringBuilder();
            row.append(ranked.isUnranked() ? "  -  " : String.format("%3d  ", ranked.score()));
            row.append(p.getTitle());
            if (ranked.closestPriorWork()) {
                row.append("   [closest prior work]");
            }
            row.append("\n       ").append(p.getDate()).append("  ").append(p.getVenue());
            if (!ranked.why().isBlank()) {
                row.append("\n       ").append(ranked.why());
            }
            rows.add(row.toString());
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
