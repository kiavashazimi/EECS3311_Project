package ca.yorku.eecs3311.consultant.app;

import ca.yorku.eecs3311.consultant.agent.PaperFeedListener;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import java.util.List;
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

    /** Replaces the feed with the ranked list, which carries the reasons. */
    public void showRanked(List<RankedPaper> papers) {
        boolean unranked = !papers.isEmpty() && papers.stream().allMatch(RankedPaper::isUnranked);
        heading.setText(papers.isEmpty()
                ? "Papers"
                : papers.size() + " papers" + (unranked
                        ? " - NOT RANKED, the language model was unavailable"
                        : ", most useful first"));

        rows.clear();
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

    /** Shown while a search is running, so the window does not look frozen. */
    public void showSearching() {
        heading.setText("Searching...");
        rows.clear();
    }

    public void showError(String message) {
        heading.setText("Search failed - previous papers kept. " + message);
    }

    /** @return the rows currently displayed, for inspection */
    List<String> rows() {
        return List.copyOf(rows);
    }
}
