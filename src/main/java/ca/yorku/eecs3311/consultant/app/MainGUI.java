package ca.yorku.eecs3311.consultant.app;

import ca.yorku.eecs3311.consultant.agent.AgentException;
import ca.yorku.eecs3311.consultant.agent.ProjectContextAgent;
import ca.yorku.eecs3311.consultant.controller.AppController;
import ca.yorku.eecs3311.consultant.controller.StorageException;
import ca.yorku.eecs3311.consultant.domain.Answer;
import ca.yorku.eecs3311.consultant.domain.ComparisonResult;
import ca.yorku.eecs3311.consultant.domain.Digest;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.controller.ValidationException;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import ca.yorku.eecs3311.consultant.domain.RepoSummary;
import ca.yorku.eecs3311.consultant.domain.StrategyNode;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import java.io.File;

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
    private final Button checkCode = new Button("Check code");
    private final Button importProject = new Button("Import my project");
    private final Button whatChanged = new Button("What changed");
    private final Button strategies = new Button("Strategies");
    private final Button digestButton = new Button("Digest");
    private final Button newProject = new Button("New project");
    private final TreeView<StrategyNode> tree = new TreeView<>();
    private final TextField consultQuery = new TextField();
    private final Button askButton = new Button("Ask");
    private final Button priorButton = new Button("Has anyone tried this?");
    private final TextArea detail = new TextArea();

    public MainGUI(AppController controller) {
        this.controller = controller;
    }

    /** @return the root node for the window */
    public BorderPane build() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.setTop(header());
        root.setCenter(new VBox(8, form(), feed.node(), tree, detailPane()));
        root.setBottom(footer());

        // the feed is registered as an observer; the agent never sees this class
        controller.addPaperFeedListener(feed);
        discover.setOnAction(e -> discoverPapers());
        askButton.setOnAction(e -> consult(false));
        priorButton.setOnAction(e -> consult(true));
        digestButton.setOnAction(e -> showDigestNow());

        // nothing to analyse until a paper is picked out of the feed
        analyze.setDisable(true);
        compare.setDisable(true);
        analyze.setOnAction(e -> analyzeSelected());
        compare.setOnAction(e -> compareSelected());
        checkCode.setDisable(true);
        checkCode.setOnAction(e -> checkCodeForSelected());
        importProject.setOnAction(e -> chooseAndImport());
        whatChanged.setOnAction(e -> showChanges());
        strategies.setOnAction(e -> showLandscape());

        // hidden until there is a landscape to show
        tree.setManaged(false);
        tree.setVisible(false);
        tree.setPrefHeight(220);
        tree.setShowRoot(false);
        tree.setCellFactory(view -> new javafx.scene.control.TreeCell<>() {
            @Override
            protected void updateItem(StrategyNode node, boolean empty) {
                super.updateItem(node, empty);
                if (empty || node == null) {
                    setText(null);
                    return;
                }
                setText(node.getName() + "  [" + node.paperCount() + "]"
                        + (node.isOwnApproach() ? "   <- your approach" : ""));
            }
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, before, after) -> {
            if (after != null) {
                detail.setText(describeSelectedBranch(after.getValue()));
            }
        });
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
        grid.addRow(row++, new Label("Datasets"), datasets);
        grid.addRow(row++, new Label("Metrics"), metrics);
        grid.addRow(row++, new Label("Models"), models);
        grid.addRow(row++, new Label("Your results so far"), results);

        Button save = new Button("Save project");
        save.setDefaultButton(true);
        save.setOnAction(e -> save());
        newProject.setOnAction(e -> startNewProject());

        status.setWrapText(true);
        grid.add(new HBox(12, save, discover, analyze, compare, checkCode), 1, row++);
        grid.add(new HBox(12, importProject, whatChanged, strategies, digestButton,
                newProject, status), 1, row++);

        consultQuery.setPromptText(
                "ask about your project, or describe an idea to look for prior work");
        grid.addRow(row, new Label("Consult"),
                new HBox(12, consultQuery, askButton, priorButton));
        GridPane.setHgrow(consultQuery, Priority.ALWAYS);
        consultQuery.setPrefColumnCount(40);

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

    /**
     * The profile as the form currently reads it, saved or not.
     *
     * <p>One place reads the fields, so {@link #save()} and the unsaved-change
     * check in {@link #discoverPapers()} cannot drift apart and disagree about
     * what is on screen.
     */
    private ProjectProfile formProfile() {
        ProjectProfile p = new ProjectProfile(question.getText() == null
                ? "" : question.getText().strip());
        p.setDatasets(items(datasets.getText()));
        p.setModels(items(models.getText()));
        p.setMetrics(items(metrics.getText()));
        p.setOwnResults(items(results.getText()));
        return p;
    }

    private void save() {
        clearFieldMarks();

        ProjectProfile p = formProfile();

        try {
            ProjectProfile saved = controller.saveProfile(p);
            List<String> thin = saved.recommendedFieldsMissing();
            if (thin.isEmpty()) {
                status.setStyle("-fx-text-fill: #1e8449;");
                status.setText("Saved.");
            } else {
                // saved, but the researcher should know what it costs rather
                // than discovering it as a silently worse search later
                status.setStyle("-fx-text-fill: #b9770e;");
                status.setText("Saved without " + String.join(" or ", thin)
                        + " - discovery will search on your question alone, and"
                        + " comparability can only say \"cannot determine\".");
            }
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
     * Discards the project after an explicit confirmation.
     *
     * <p>The dialog names what will be lost — a count of analyses, not just
     * "are you sure" — because the solution landscape is built from those
     * analyses and the researcher may not connect the two.
     */
    private void startNewProject() {
        int analyses = controller.getAnalyses().size();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Discard this project and start over?\n\n"
                        + "This deletes your profile and all " + analyses
                        + " analysed paper(s), including the solution landscape built"
                        + " from them. It cannot be undone.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("New project");
        confirm.setHeaderText(null);

        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        try {
            controller.startNewProject();
        } catch (StorageException e) {
            status.setStyle("-fx-text-fill: #c0392b;");
            status.setText(e.getMessage());
            return;
        }
        showProfile(controller.getProfile());
        feed.showRanked(List.of());
        tree.setRoot(null);
        tree.setManaged(false);
        tree.setVisible(false);
        detail.clear();
        status.setStyle("-fx-text-fill: #1e8449;");
        status.setText("New project. Describe it above and save.");
    }

    /**
     * Runs discovery off the FX thread. A literature search plus two model
     * calls takes seconds; doing it on the FX thread would freeze the window
     * and look like a crash.
     */
    private void discoverPapers() {
        // F03 discovers from the SAVED profile, not from what is on screen.
        // Without this, editing the research question and pressing Discover
        // returned a ranked list for the previous project and looked like an
        // answer to the new one.
        if (!formProfile().describesSameProjectAs(controller.getProfile())) {
            status.setStyle("-fx-text-fill: #c0392b;");
            status.setText("Not searched - Discover uses your saved project, and the form"
                    + " has unsaved changes. Click \"Save project\" first.");
            return;
        }

        discover.setDisable(true);
        feed.showSearching();

        Thread worker = new Thread(() -> {
            try {
                List<RankedPaper> papers = controller.discoverPapers();
                Platform.runLater(() -> {
                    feed.showRanked(papers, controller.getRankingFailure());
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

    /**
     * F12 from the GUI. The digest itself is assembled from what the builder
     * was already told, but the narrative is a model call, so it runs off the
     * FX thread like the others.
     */
    private void showDigestNow() {
        digestButton.setDisable(true);
        detail.setText("Assembling the digest...");

        Thread worker = new Thread(() -> {
            try {
                Digest d = controller.buildDigest();
                Platform.runLater(() -> {
                    showDigest(d);
                    digestButton.setDisable(false);
                });
            } catch (StorageException e) {
                Platform.runLater(() -> {
                    detail.setText(e.getMessage());
                    digestButton.setDisable(false);
                });
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    detail.setText("The digest could not be built: " + e);
                    digestButton.setDisable(false);
                });
            }
        }, "digest");
        worker.setDaemon(true);
        worker.start();
    }

    /** The method SD08 names. Renders a digest, or says plainly that it is empty. */
    void showDigest(Digest d) {
        StringBuilder b = new StringBuilder("Digest - ").append(d.getGeneratedAt())
                .append("\n\n");

        if (d.isEmpty()) {
            // UC11 3a: an explicit message, not a blank panel
            String since = controller.getLastDigestAt();
            b.append(since.isBlank()
                    ? "Nothing new to report yet. Discover papers, or import your project."
                    : "Nothing new since " + since + ".");
            detail.setText(b.toString());
            return;
        }

        b.append(d.isSummaryMissing()
                ? "(no summary - the language model was unavailable)"
                : d.getSummaryText());

        if (!d.getNewPapers().isEmpty()) {
            b.append("\n\nNew papers (").append(d.getNewPapers().size()).append(')');
            for (var p : d.getNewPapers()) {
                b.append("\n  ").append(p.getDate()).append("  ").append(p.getTitle());
            }
        }
        if (!d.getStrategyHighlights().isEmpty()) {
            b.append("\n\nApproaches seen: ")
                    .append(String.join(", ", d.getStrategyHighlights()));
        }
        if (!d.getProjectChanges().isBlank()) {
            b.append("\n\nYour project\n  ").append(d.getProjectChanges());
        }
        detail.setText(b.toString());
    }

    /**
     * F11 and F08 from the GUI. Off the FX thread, like discovery.
     *
     * @param priorWork true for F08 (also searches the live literature), false
     *     for F11 (stored project data only)
     */
    private void consult(boolean priorWork) {
        String text = consultQuery.getText() == null ? "" : consultQuery.getText().strip();
        if (text.isBlank()) {
            status.setStyle("-fx-text-fill: #c0392b;");
            status.setText(priorWork ? "Describe the idea first." : "Type a question first.");
            consultQuery.setStyle(REQUIRED_STYLE);
            return;
        }
        consultQuery.setStyle("");
        askButton.setDisable(true);
        priorButton.setDisable(true);
        detail.setText(priorWork ? "Searching papers and your own memory..." : "Thinking...");
        // from here the detail pane answers a different question from the one
        // the feed's papers were found for
        feed.markStale();

        Thread worker = new Thread(() -> {
            try {
                Answer answer = priorWork ? controller.searchPriorWork(text)
                        : controller.ask(text);
                Platform.runLater(() -> {
                    detail.setText(describe(answer));
                    consultDone();
                });
            } catch (AgentException e) {
                Platform.runLater(() -> {
                    detail.setText(e.getMessage());
                    consultDone();
                });
            } catch (RuntimeException e) {
                // the buttons must come back even when something unforeseen
                // fails; a dead control with no message is the worst outcome
                Platform.runLater(() -> {
                    detail.setText("That could not be answered: " + e);
                    consultDone();
                });
            }
        }, "consult");
        worker.setDaemon(true);
        worker.start();
    }

    private void consultDone() {
        askButton.setDisable(false);
        priorButton.setDisable(false);
    }

    /** Renders an answer with the records it rests on, never without them. */
    private String describe(Answer answer) {
        StringBuilder b = new StringBuilder();
        if (!answer.isGrounded()) {
            // the empty result is shown as prominently as an answer would be
            b.append(answer.getText());
            if (!answer.getNote().isBlank()) {
                b.append("\n\n(").append(answer.getNote()).append(')');
            }
            return b.toString();
        }

        b.append(answer.isTextMissing()
                ? "The records below matched, but no summary could be written."
                : answer.getText());
        b.append("\n\nBased on:");
        for (String source : answer.getSources()) {
            b.append("\n  - ").append(source);
        }
        if (!answer.getNote().isBlank()) {
            b.append("\n\n(").append(answer.getNote()).append(')');
        }
        return b.toString();
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
     * F13 — build the landscape and show it as a tree.
     *
     * <p>One recursive method fills the whole tree without asking whether a
     * node is a leaf or a branch, which is what the Composite is for.
     */
    private void showLandscape() {
        strategies.setDisable(true);
        detail.setText("Building the solution landscape...");

        Thread worker = new Thread(() -> {
            StrategyNode root;
            boolean tooFew;
            try {
                root = controller.buildSolutionLandscape();
                tooFew = controller.tooFewPapersForLandscape();
            } catch (RuntimeException e) {
                // without this the button stayed disabled with no message, and
                // the only way back was to restart the application
                Platform.runLater(() -> {
                    strategies.setDisable(false);
                    detail.setText("The solution landscape could not be built: " + e);
                });
                return;
            }
            final StrategyNode built = root;
            final boolean few = tooFew;
            Platform.runLater(() -> {
                strategies.setDisable(false);
                if (built.paperCount() == 0) {
                    tree.setManaged(false);
                    tree.setVisible(false);
                    detail.setText("Nothing to map yet. Analyse a paper first.");
                    return;
                }
                tree.setRoot(asTreeItem(built));
                tree.setManaged(true);
                tree.setVisible(true);
                // the branches come from the analyses, not from the research
                // question, and nothing said so: after switching to a new
                // question the tree still showed the previous project's
                // families and looked like it had ignored the change
                detail.setText("Solution landscape - built from your "
                        + built.paperCount() + " analysed paper(s), not from your"
                        + " research question."
                        + (few
                                ? "\n\nToo few papers for branch summaries - the seeded "
                                        + "families are shown as they are."
                                : "\n\nSelect a branch to see what it covers."));
            });
        }, "build-landscape");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * A branch's summary followed by the papers sitting directly on it.
     *
     * <p>F13 in {@code docs/STAGE1.md} says the right-hand pane shows "the
     * selected branch's main idea, its sibling contrast and its papers"; only
     * the CLI did. The gap became visible once sub-branches existed: a node
     * reading {@code [9]} with children holding 3, 3 and 2 had a ninth paper
     * the model had not grouped, and nothing on screen could show it — the
     * tree renders branches only.
     */
    private String describeSelectedBranch(StrategyNode node) {
        StringBuilder b = new StringBuilder(controller.describeBranch(node));
        List<PaperAnalysis> own = node.getPapers();
        if (own.isEmpty()) {
            return b.toString();
        }
        b.append(node.isLeaf() ? "\n\nPapers" : "\n\nPapers not in any sub-branch");
        for (PaperAnalysis paper : own) {
            b.append("\n  - ").append(paper.getPaper().getTitle());
        }
        return b.toString();
    }

    /** Recurses to any depth with no test for leaf versus branch. */
    private static TreeItem<StrategyNode> asTreeItem(StrategyNode node) {
        TreeItem<StrategyNode> item = new TreeItem<>(node);
        item.setExpanded(true);
        for (StrategyNode child : node.getChildren()) {
            item.getChildren().add(asTreeItem(child));
        }
        return item;
    }

    /** F02 — pick a directory and read it. */
    private void chooseAndImport() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose your project directory");
        File chosen = chooser.showDialog(detail.getScene().getWindow());
        if (chosen == null) {
            return;
        }

        importProject.setDisable(true);
        detail.setText("Reading " + chosen + "...");
        runOffThread(() -> {
            try {
                ProjectContextAgent.ImportedContext imported =
                        controller.importProjectContext(chosen.getAbsolutePath());
                return describe(imported);
            } catch (AgentException e) {
                // UC02 1a: reported, nothing stored
                return "Could not read that directory.\n\n" + e.getMessage();
            } catch (StorageException e) {
                return "The project was read but could not be saved.\n\n" + e.getMessage();
            }
        }, importProject);
    }

    /** F10 — re-scan the stored path and say what changed. */
    private void showChanges() {
        whatChanged.setDisable(true);
        detail.setText("Checking for changes...");
        runOffThread(() -> {
            try {
                var changes = controller.projectChanges();
                String summary = controller.summarizeProjectChanges();
                if (changes.isEmpty()) {
                    return summary;
                }
                StringBuilder b = new StringBuilder(changes.size() + " change(s)\n\n");
                changes.forEach(c -> b.append("   - ").append(c).append('\n'));
                b.append('\n').append(summary.isBlank()
                        ? "(no narrative - the language model was unavailable. The list above "
                                + "is computed from the two snapshots and stands.)"
                        : summary);
                return b.toString();
            } catch (AgentException e) {
                // UC09 2a: point at the import rather than an empty panel
                return e.getMessage();
            } catch (StorageException e) {
                return e.getMessage();
            }
        }, whatChanged);
    }

    private static String describe(ProjectContextAgent.ImportedContext imported) {
        var s = imported.snapshot();
        StringBuilder b = new StringBuilder();
        b.append("Imported ").append(s.getProjectPath()).append("\n\n");
        b.append("Facts from   ").append(s.getProviderName()).append('\n');
        b.append("Scanned at   ").append(s.getScannedAt()).append('\n');
        if (!s.getRecentCommits().isEmpty()) {
            b.append("\nRecent commits\n");
            s.getRecentCommits().forEach(c -> b.append("   ").append(c).append('\n'));
        }
        if (!s.getConfigEntries().isEmpty()) {
            b.append("\nConfiguration\n");
            s.getConfigEntries().forEach((k, v) ->
                    b.append("   ").append(k).append("  ").append(v).append('\n'));
        }
        if (!s.getResultFiles().isEmpty()) {
            b.append("\nResults: ").append(String.join(", ", s.getResultFiles())).append('\n');
        }
        b.append('\n').append(imported.isSummaryMissing()
                ? "(no summary - the language model was unavailable. The facts above stand.)"
                : imported.summary());
        return b.toString();
    }

    /** Runs work off the FX thread and puts its text in the detail pane. */
    private void runOffThread(java.util.function.Supplier<String> work, Button toReenable) {
        Thread worker = new Thread(() -> {
            String text = work.get();
            Platform.runLater(() -> {
                detail.setText(text);
                toReenable.setDisable(false);
            });
        }, "gui-worker");
        worker.setDaemon(true);
        worker.start();
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
        boolean notAnalysed = selected.isEmpty()
                || controller.findAnalysis(selected.get().getId()).isEmpty();
        compare.setDisable(notAnalysed);
        checkCode.setDisable(notAnalysed);
    }

    /** F09 on the selected paper. Metadata only; no code is downloaded or run. */
    private void checkCodeForSelected() {
        var selected = feed.selectedPaper();
        if (selected.isEmpty()) {
            return;
        }
        var stored = controller.findAnalysis(selected.get().getId());
        if (stored.isEmpty()) {
            detail.setText("Analyze this paper first.");
            return;
        }
        checkCode.setDisable(true);
        detail.setText("Checking the repository for " + selected.get().getTitle() + "...");
        runOffThread(() -> {
            try {
                return describe(controller.checkRepository(stored.get()));
            } catch (AgentException e) {
                return "Could not check the repository.\n\n" + e.getMessage();
            }
        }, checkCode);
    }

    private static String describe(RepoSummary r) {
        if (r.getUrl().isBlank()) {
            // UC08 2a: stated plainly rather than as a failure
            return r.getNote();
        }
        StringBuilder b = new StringBuilder();
        b.append(r.getUrl()).append("\n\n");
        b.append("Code          ").append(r.isCodeAvailable() ? "present" : "not found")
                .append('\n');
        b.append("Data          ").append(r.isHasDatasets() ? "present" : "not found")
                .append('\n');
        b.append("Evaluation    ")
                .append(r.isHasEvalScripts() ? "scripts present" : "none found").append('\n');
        b.append("Licence       ").append(r.getLicense()).append('\n');
        b.append("README        ").append(r.getReadmeQuality()).append('\n');
        b.append("Reproducible  ").append(r.isReproducible()
                ? "plausibly - code, data and an evaluation are all there"
                : "not from this listing alone").append('\n');
        if (!r.getNote().isBlank()) {
            b.append('\n').append(r.getNote()).append('\n');
        }
        b.append("\n(metadata only - no code was downloaded or run)");
        return b.toString();
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
