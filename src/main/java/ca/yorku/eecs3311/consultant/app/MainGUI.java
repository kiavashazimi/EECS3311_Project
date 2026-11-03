package ca.yorku.eecs3311.consultant.app;

import ca.yorku.eecs3311.consultant.agent.AgentException;
import ca.yorku.eecs3311.consultant.controller.AppController;
import ca.yorku.eecs3311.consultant.controller.StorageException;
import ca.yorku.eecs3311.consultant.domain.ComparisonResult;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.controller.ValidationException;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

/**
 * The desktop client. Holds no domain logic: it collects input, calls
 * {@link AppController}, and renders what comes back — the same facade the CLI
 * uses, which is what keeps the two clients from drifting apart.
 */
public class MainGUI {

    private static final String REQUIRED_STYLE =
            "-fx-border-color: #c0392b; -fx-border-width: 0 0 2 0;";

    private final AppController controller;

    private final TextArea question = new TextArea();
    private final TextField datasets = new TextField();
    private final TextField models = new TextField();
    private final TextField metrics = new TextField();
    private final TextField results = new TextField();

    private final Label status = new Label();
    private final Label stored = new Label();

    private final PaperFeedPanel feed = new PaperFeedPanel();
    private final Button discover = new Button("Discover papers");
    private final Button analyze = new Button("Analyze selected");
    private final Button compare = new Button("Compare to my project");
    private final TextArea detail = new TextArea();

    public MainGUI(AppController controller) {
        this.controller = controller;
    }

    /** @return the root node for the window */
    public BorderPane build() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setTop(header());
        root.setCenter(new VBox(8, form(), feed.node(), detailPane()));
        root.setBottom(footer());

        // the feed is registered as an observer; the agent never sees this class
        controller.addPaperFeedListener(feed);
        discover.setOnAction(e -> discoverPapers());

        // nothing to analyse until a paper is picked out of the feed
        analyze.setDisable(true);
        compare.setDisable(true);
        analyze.setOnAction(e -> analyzeSelected());
        compare.setOnAction(e -> compareSelected());
        feed.onSelectionChanged(this::refreshButtons);

        controller.loadStoredProject();
        showProfile(controller.getProfile());
        return root;
    }

    private VBox header() {
        Label title = new Label("Project-Aware Research Consultant");
        title.setFont(Font.font(20));
        Label subtitle = new Label("Describe your project once. Everything else works from it.");
        VBox box = new VBox(4, title, subtitle);
        box.setPadding(new Insets(0, 0, 16, 0));
        return box;
    }

    private GridPane form() {
        question.setPrefRowCount(3);
        question.setWrapText(true);
        question.setPromptText("What are you trying to find out?");
        datasets.setPromptText("comma separated, e.g. CheXpert, MIMIC-CXR");
        models.setPromptText("comma separated");
        metrics.setPromptText("comma separated, e.g. AUC, subgroup AUC gap");
        results.setPromptText("comma separated, e.g. AUC = 0.84 on CheXpert");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);

        int row = 0;
        grid.addRow(row++, new Label("Research question *"), question);
        grid.addRow(row++, new Label("Datasets *"), datasets);
        grid.addRow(row++, new Label("Metrics *"), metrics);
        grid.addRow(row++, new Label("Models"), models);
        grid.addRow(row++, new Label("Your results so far"), results);

        Button save = new Button("Save project");
        save.setDefaultButton(true);
        save.setOnAction(e -> save());

        status.setWrapText(true);
        grid.add(new HBox(12, save, discover, analyze, compare, status), 1, row);

        GridPane.setHgrow(question, Priority.ALWAYS);
        for (TextField field : List.of(datasets, models, metrics, results)) {
            GridPane.setHgrow(field, Priority.ALWAYS);
            field.setPrefColumnCount(40);
        }
        return grid;
    }

    private VBox footer() {
        stored.setStyle("-fx-text-fill: #555;");
        VBox box = new VBox(stored);
        box.setPadding(new Insets(16, 0, 0, 0));
        return box;
    }

    // --- actions -------------------------------------------------------------

    private void save() {
        clearFieldMarks();

        ProjectProfile p = new ProjectProfile(question.getText() == null
                ? "" : question.getText().strip());
        p.setDatasets(items(datasets.getText()));
        p.setModels(items(models.getText()));
        p.setMetrics(items(metrics.getText()));
        p.setOwnResults(items(results.getText()));

        try {
            ProjectProfile saved = controller.saveProfile(p);
            status.setStyle("-fx-text-fill: #1e8449;");
            status.setText("Saved.");
            showProfile(saved);
        } catch (ValidationException e) {
            // SD01: inline error, nothing persisted
            markMissing(e.getFields());
            status.setStyle("-fx-text-fill: #c0392b;");
            status.setText("Not saved - fill in: " + String.join(", ", e.getFields()));
        } catch (StorageException e) {
            status.setStyle("-fx-text-fill: #c0392b;");
            status.setText("Could not write the project file. Your previous project is intact.");
        }
    }

    /**
     * Runs discovery off the FX thread. A literature search plus two model
     * calls takes seconds; doing it on the FX thread would freeze the window
     * and look like a crash.
     */
    private void discoverPapers() {
        discover.setDisable(true);
        feed.showSearching();

        Thread worker = new Thread(() -> {
            try {
                List<RankedPaper> papers = controller.discoverPapers();
                Platform.runLater(() -> {
                    feed.showRanked(papers);
                    discover.setDisable(false);
                });
            } catch (AgentException e) {
                // UC03 4a: the error is shown and the feed is left alone
                Platform.runLater(() -> {
                    feed.showError(e.getMessage());
                    discover.setDisable(false);
                });
            }
        }, "discover-papers");
        worker.setDaemon(true);
        worker.start();
    }

    private ScrollPane detailPane() {
        detail.setEditable(false);
        detail.setWrapText(true);
        detail.setPrefRowCount(10);
        detail.setPromptText("Select a paper and click \"Analyze selected\".");
        ScrollPane pane = new ScrollPane(detail);
        pane.setFitToWidth(true);
        return pane;
    }

    /**
     * Compare is only offered once the paper has been analysed.
     *
     * <p>Two clicks rather than one on purpose: analysis and comparison are
     * separate steps that can fail separately, and collapsing them would hide
     * which one went wrong — the CLI requires the same order.
     */
    private void refreshButtons() {
        var selected = feed.selectedPaper();
        analyze.setDisable(selected.isEmpty());
        compare.setDisable(selected.isEmpty()
                || controller.findAnalysis(selected.get().getId()).isEmpty());
    }

    /** F06/F07 on the selected paper, off the FX thread. */
    private void compareSelected() {
        var selected = feed.selectedPaper();
        if (selected.isEmpty()) {
            return;
        }
        var stored = controller.findAnalysis(selected.get().getId());
        if (stored.isEmpty()) {
            detail.setText("Analyze this paper first.");
            return;
        }
        compare.setDisable(true);
        detail.setText("Comparing " + selected.get().getTitle() + " to your project...");

        Thread worker = new Thread(() -> {
            String text;
            try {
                text = describe(controller.comparePaper(stored.get()));
            } catch (AgentException e) {
                text = "Could not compare this paper.\n\n" + e.getMessage();
            }
            String shown = text;
            Platform.runLater(() -> {
                detail.setText(shown);
                compare.setDisable(false);
            });
        }, "compare-paper");
        worker.setDaemon(true);
        worker.start();
    }

    private static String describe(ComparisonResult r) {
        StringBuilder b = new StringBuilder();
        b.append(r.getPaperTitle()).append("\n\n");
        b.append("Comparable?  ").append(r.getComparability().label()).append('\n');
        if (r.getMismatches().isEmpty()) {
            b.append("             ").append(r.getComparabilityNote()).append('\n');
        } else {
            b.append("Why not\n");
            r.getMismatches().forEach(m -> b.append("   - ").append(m).append('\n'));
        }
        appendList(b, "Similarities", r.getSimilarities());
        appendList(b, "Differences", r.getDifferences());
        b.append('\n');
        b.append(r.isNarrativeMissing()
                ? "(no narrative - the language model was unavailable. The verdict above "
                        + "is deterministic and stands.)"
                : r.getNarrative());
        return b.toString();
    }

    private static void appendList(StringBuilder b, String heading, java.util.List<String> items) {
        if (!items.isEmpty()) {
            b.append('\n').append(heading).append('\n');
            items.forEach(i -> b.append("   - ").append(i).append('\n'));
        }
    }

    /** F04/F05 on the selected paper, off the FX thread like discovery. */
    private void analyzeSelected() {
        var selected = feed.selectedPaper();
        if (selected.isEmpty()) {
            return;
        }
        analyze.setDisable(true);
        detail.setText("Analyzing " + selected.get().getTitle() + "...");

        Thread worker = new Thread(() -> {
            String text;
            try {
                text = describe(controller.analyzePaper(selected.get()));
            } catch (AgentException e) {
                // UC04 3a: the paper is flagged, not analysed from its title
                text = "Could not analyse this paper.\n\n" + e.getMessage();
            } catch (StorageException e) {
                text = "The analysis was produced but could not be saved.\n\n"
                        + e.getMessage()
                        + "\n\nIt is held in memory and will be written on the next save.";
            }
            String shown = text;
            Platform.runLater(() -> {
                detail.setText(shown);
                analyze.setDisable(false);
                refreshButtons();
            });
        }, "analyze-paper");
        worker.setDaemon(true);
        worker.start();
    }

    private static String describe(PaperAnalysis a) {
        StringBuilder b = new StringBuilder();
        b.append(a.getPaper().getTitle()).append("\n\n");
        b.append("Problem      ").append(a.getProblem()).append('\n');
        b.append("Method       ").append(a.getMethod()).append('\n');
        b.append("Dataset      ").append(a.getDataset()).append('\n');
        b.append("Model        ").append(a.getModel()).append('\n');
        b.append("Metrics      ").append(String.join(", ", a.getMetrics())).append('\n');
        b.append("Results      ").append(a.getResults()).append('\n');
        b.append("Limitations  ").append(a.getLimitations()).append('\n');
        b.append("Strategy     ").append(a.getStrategyTags().isEmpty()
                ? "none recognised" : String.join(", ", a.getStrategyTags()));
        if (a.isTagsUncertain()) {
            b.append("   (UNCERTAIN - not in the taxonomy)");
        }
        if (!a.isComplete()) {
            b.append("\n\nNot stated in the paper: ")
                    .append(String.join(", ", a.unknownFields()));
        }
        return b.toString();
    }

    private void showProfile(ProjectProfile p) {
        question.setText(p.getResearchQuestion());
        datasets.setText(String.join(", ", p.getDatasets()));
        models.setText(String.join(", ", p.getModels()));
        metrics.setText(String.join(", ", p.getMetrics()));
        results.setText(String.join(", ", p.getOwnResults()));
        stored.setText("Stored at " + controller.getStorageLocation());
    }

    private void markMissing(List<String> fields) {
        for (String field : fields) {
            switch (field) {
                case "researchQuestion" -> question.setStyle(REQUIRED_STYLE);
                case "datasets" -> datasets.setStyle(REQUIRED_STYLE);
                case "metrics" -> metrics.setStyle(REQUIRED_STYLE);
                default -> { }
            }
        }
    }

    private void clearFieldMarks() {
        question.setStyle("");
        datasets.setStyle("");
        metrics.setStyle("");
    }

    /** Same splitting rule as the CLI, so the two clients behave identically. */
    static List<String> items(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                items.add(part.strip());
            }
        }
        return items;
    }
}
