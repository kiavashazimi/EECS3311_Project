package ca.yorku.eecs3311.consultant.cli;

import ca.yorku.eecs3311.consultant.agent.AgentException;
import ca.yorku.eecs3311.consultant.agent.ProjectContextAgent;
import ca.yorku.eecs3311.consultant.agent.ToolManager;
import ca.yorku.eecs3311.consultant.config.Config;
import ca.yorku.eecs3311.consultant.controller.AppController;
import ca.yorku.eecs3311.consultant.controller.StorageException;
import ca.yorku.eecs3311.consultant.controller.ValidationException;
import ca.yorku.eecs3311.consultant.domain.ComparisonResult;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.RepoSummary;
import ca.yorku.eecs3311.consultant.domain.StrategyNode;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.domain.RankedPaper;
import ca.yorku.eecs3311.consultant.literature.ArxivSource;
import ca.yorku.eecs3311.consultant.llm.ClaudeClient;
import ca.yorku.eecs3311.consultant.memory.MemoryStore;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command-line entry point. Every feature must be reachable from here as well
 * as from the GUI, and both go through {@link AppController} — neither client
 * holds domain logic of its own.
 *
 * <p>Built around {@link #run(String[])} returning an exit code and writing to
 * injected streams, so the commands can be tested without capturing
 * {@code System.out} or ending the JVM.
 */
public class Cli {

    static final int OK = 0;
    static final int USAGE_ERROR = 1;
    static final int VALIDATION_ERROR = 2;
    static final int STORAGE_ERROR = 3;
    static final int AGENT_ERROR = 4;

    private static final Set<String> PROFILE_FLAGS =
            Set.of("--question", "--datasets", "--models", "--metrics", "--results");

    private final AppController controller;
    private final PrintStream out;
    private final PrintStream err;

    public Cli(AppController controller, PrintStream out, PrintStream err) {
        this.controller = controller;
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        Config config = Config.load();
        AppController controller = new AppController(
                ProjectMemory.getInstance(),
                toolManager(config),
                new MemoryStore(config.dataDir()));
        controller.loadStoredProject();

        System.exit(new Cli(controller, System.out, System.err).run(args));
    }

    private static ToolManager toolManager(Config config) {
        ToolManager tools = new ToolManager(new ClaudeClient(config));
        tools.setLiteratureSource(new ArxivSource());
        return tools;
    }

    /**
     * Runs one command.
     *
     * @param args the command and its flags
     * @return the process exit code: 0 on success, non-zero on any failure
     */
    public int run(String[] args) {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            printUsage();
            return OK;
        }

        switch (args[0]) {
            case "profile":
                return profile(args);
            case "discover":
                return discover();
            case "analyze":
            case "analyse":
                return analyze(args);
            case "analyses":
                return analyses();
            case "compare":
                return compare(args);
            case "import":
                return importProject(args);
            case "changes":
                return changes();
            case "landscape":
                return landscape();
            case "repo":
                return repo(args);
            case "where":
                out.println(controller.getStorageLocation());
                return OK;
            default:
                err.println("Unknown command: " + args[0]);
                err.println("Run 'rc help' for the list of commands.");
                return USAGE_ERROR;
        }
    }

    // --- discover ------------------------------------------------------------

    private int discover() {
        List<RankedPaper> papers;
        try {
            papers = controller.discoverPapers();
        } catch (AgentException e) {
            // UC03 4a: say what went wrong and offer the retry
            err.println(e.getMessage());
            err.println("Nothing was lost. Run 'rc discover' again to retry.");
            return AGENT_ERROR;
        }

        if (papers.isEmpty()) {
            out.println("No new papers matched this project.");
            return OK;
        }

        boolean unranked = papers.stream().allMatch(RankedPaper::isUnranked);
        if (unranked) {
            out.println("Found " + papers.size()
                    + " papers. NOT RANKED - the language model was unavailable.");
        } else {
            out.println("Found " + papers.size() + " papers, most useful first.");
        }
        out.println();

        int position = 1;
        for (RankedPaper ranked : papers) {
            var p = ranked.paper();
            String score = ranked.isUnranked() ? "  -" : String.format("%3d", ranked.score());
            out.println(String.format("%2d. [%s] %s", position++, score, p.getTitle()));
            out.println("      " + p.getDate() + "  " + p.getVenue() + "  " + p.getUrl());
            if (!ranked.why().isBlank()) {
                out.println("      why: " + ranked.why()
                        + (ranked.closestPriorWork() ? "  <-- closest prior work" : ""));
            }
            if (p.hasRepository()) {
                out.println("      code: " + p.getRepoUrl());
            }
            out.println();
        }
        return OK;
    }

    // --- paper references ----------------------------------------------------

    /**
     * Turns what the researcher typed into a paper id.
     *
     * <p>{@code discover} prints a numbered list, so the number is the obvious
     * thing to type next. Accepting only the arXiv id made the CLI show a
     * numbered list and then refuse its own numbers.
     *
     * <p>An exact id in the listing wins over a row number, and a number is
     * read as a row only when there is a listing to count against. The two
     * forms can collide only when a paper's id is itself a bare integer, and
     * then the literal thing the researcher typed is the better reading: a row
     * number is only ever a shorthand for an id.
     *
     * @param ref the id or row number as typed
     * @param command the command name, for the error message
     * @return the resolved paper id, or null when the reference cannot mean
     *     anything, in which case the complaint has already been printed
     */
    private String resolveRef(String ref, String command) {
        String typed = ref.strip();
        List<String> listing = controller.getLastListing();
        if (listing.contains(typed)) {
            return typed;
        }
        if (!typed.matches("\\d{1,4}")) {
            // every arXiv id carries a '.' or a '/', so this is an id, not a row
            return typed;
        }

        if (listing.isEmpty()) {
            // with nothing numbered, a number cannot be a row; it is an id, and
            // a wrong one is reported by whoever looks it up
            return typed;
        }

        int row = Integer.parseInt(typed);
        if (row < 1 || row > listing.size()) {
            err.println("There is no row " + row + "; the last listing had "
                    + listing.size() + " papers.");
            err.println("Run 'rc discover' to see it again, or pass the arXiv id, e.g."
                    + " 'rc " + command + " 2412.20374v2'.");
            return null;
        }
        return listing.get(row - 1);
    }

    /**
     * Explains the numbered list when a bare number was looked up as an id and
     * found nothing.
     *
     * <p>Typing the row number before running {@code discover} is the natural
     * mistake, and "no paper found with id 2" does not hint at the cause.
     */
    private void hintIfItLookedLikeARowNumber(String ref) {
        if (ref.strip().matches("\\d{1,4}") && controller.getLastListing().isEmpty()) {
            err.println("If that was a row number, run 'rc discover' first - the numbers"
                    + " come from its listing.");
        }
    }

    // --- analyze -------------------------------------------------------------

    private int analyze(String[] args) {
        if (args.length < 2) {
            err.println("'analyze' needs a paper id or a row number, e.g."
                    + " 'rc analyze 2304.09218v1' or 'rc analyze 2'.");
            err.println("Run 'rc discover' to see the numbered list.");
            return USAGE_ERROR;
        }

        String id = resolveRef(args[1], "analyze");
        if (id == null) {
            return USAGE_ERROR;
        }

        PaperAnalysis analysis;
        try {
            analysis = controller.analyzePaperById(id);
        } catch (AgentException e) {
            err.println(e.getMessage());
            hintIfItLookedLikeARowNumber(args[1]);
            return AGENT_ERROR;
        } catch (StorageException e) {
            // the analysis survives in memory; say so rather than implying it is lost
            err.println(e.getMessage());
            err.println("It is held in memory and will be written on the next successful save.");
            return STORAGE_ERROR;
        }

        printAnalysis(analysis);
        return OK;
    }

    private int analyses() {
        List<PaperAnalysis> stored = controller.getAnalyses();
        if (stored.isEmpty()) {
            out.println("No papers analysed yet. Try 'rc analyze <paper id>'.");
            return OK;
        }
        out.println(stored.size() + " analysed paper(s):");
        out.println();
        for (PaperAnalysis analysis : stored) {
            printAnalysis(analysis);
        }
        return OK;
    }

    private void printAnalysis(PaperAnalysis a) {
        out.println(a.getPaper().getTitle());
        out.println("  id          : " + a.getPaper().getId());
        out.println("  problem     : " + a.getProblem());
        out.println("  method      : " + a.getMethod());
        out.println("  dataset     : " + a.getDataset());
        out.println("  model       : " + a.getModel());
        out.println("  metrics     : " + String.join(", ", a.getMetrics()));
        out.println("  results     : " + a.getResults());
        out.println("  limitations : " + a.getLimitations());
        out.println("  strategy    : " + (a.getStrategyTags().isEmpty()
                ? "none recognised" : String.join(", ", a.getStrategyTags()))
                + (a.isTagsUncertain() ? "   (UNCERTAIN)" : ""));
        if (!a.isComplete()) {
            out.println("  unknown     : " + String.join(", ", a.unknownFields()));
        }
        out.println();
    }

    // --- compare -------------------------------------------------------------

    private int compare(String[] args) {
        if (args.length < 2) {
            err.println("'compare' needs a paper id, e.g. 'rc compare 2304.09218v1'.");
            return USAGE_ERROR;
        }
        String id = resolveRef(args[1], "compare");
        if (id == null) {
            return USAGE_ERROR;
        }

        PaperAnalysis stored = controller.findAnalysis(id).orElse(null);
        if (stored == null) {
            // analysing first would hide that two separate things happened
            err.println("That paper has not been analysed yet. Run 'rc analyze " + id
                    + "' first.");
            return USAGE_ERROR;
        }

        ComparisonResult result;
        try {
            result = controller.comparePaper(stored);
        } catch (AgentException e) {
            err.println(e.getMessage());
            return AGENT_ERROR;
        }

        out.println(result.getPaperTitle());
        out.println();
        out.println("  Comparable?  " + result.getComparability().label());
        if (result.getMismatches().isEmpty()) {
            out.println("  Why          " + result.getComparabilityNote());
        } else {
            // the note summarises the same list, so print one or the other
            out.println("  Why not");
            result.getMismatches().forEach(m -> out.println("    - " + m));
        }
        if (!result.getSimilarities().isEmpty()) {
            out.println("  Similarities");
            result.getSimilarities().forEach(m -> out.println("    - " + m));
        }
        if (!result.getDifferences().isEmpty()) {
            out.println("  Differences");
            result.getDifferences().forEach(m -> out.println("    - " + m));
        }
        if (result.isNarrativeMissing()) {
            out.println();
            out.println("  (no narrative - the language model was unavailable; the verdict"
                    + " above is deterministic and stands)");
        } else {
            out.println();
            out.println("  " + result.getNarrative());
        }
        out.println();
        return OK;
    }

    // --- project context -----------------------------------------------------

    private int importProject(String[] args) {
        if (args.length < 2) {
            err.println("'import' needs a directory, e.g. 'rc import ~/projects/my-study'.");
            return USAGE_ERROR;
        }

        ProjectContextAgent.ImportedContext imported;
        try {
            imported = controller.importProjectContext(args[1]);
        } catch (AgentException e) {
            err.println(e.getMessage());
            return AGENT_ERROR;
        } catch (StorageException e) {
            err.println(e.getMessage());
            return STORAGE_ERROR;
        }

        var s = imported.snapshot();
        out.println("Imported " + s.getProjectPath());
        out.println("  facts from  : " + s.getProviderName());
        out.println("  scanned at  : " + s.getScannedAt());
        if (!s.getRecentCommits().isEmpty()) {
            out.println("  commits     : " + s.getRecentCommits().size() + " recent");
            s.getRecentCommits().forEach(c -> out.println("      " + c));
        } else {
            out.println("  commits     : none (not a git repository, or no history)");
        }
        if (!s.getConfigEntries().isEmpty()) {
            out.println("  configs     : " + String.join(", ", s.getConfigEntries().keySet()));
        }
        if (!s.getResultFiles().isEmpty()) {
            out.println("  results     : " + String.join(", ", s.getResultFiles()));
        }
        if (!s.getAgentDescription().isBlank()) {
            out.println("  the project describes itself as:");
            s.getAgentDescription().lines().limit(12)
                    .forEach(line -> out.println("      " + line));
        }
        out.println();
        out.println(imported.isSummaryMissing()
                ? "(no summary - the language model was unavailable. The facts above stand;"
                        + " run 'rc import' again to retry the summary.)"
                : imported.summary());
        out.println();
        return OK;
    }

    private int changes() {
        String summary;
        List<String> changes;
        try {
            changes = controller.projectChanges();
            summary = controller.summarizeProjectChanges();
        } catch (AgentException e) {
            err.println(e.getMessage());
            return AGENT_ERROR;
        } catch (StorageException e) {
            err.println(e.getMessage());
            return STORAGE_ERROR;
        }

        if (changes.isEmpty()) {
            out.println(summary);
            return OK;
        }
        out.println(changes.size() + " change(s) in your project:");
        changes.forEach(c -> out.println("  - " + c));
        out.println();
        out.println(summary.isBlank()
                ? "(no narrative - the language model was unavailable. The list above is"
                        + " computed from the two snapshots and stands.)"
                : summary);
        out.println();
        return OK;
    }

    // --- repo ----------------------------------------------------------------

    private int repo(String[] args) {
        if (args.length < 2) {
            err.println("'repo' needs a paper id, e.g. 'rc repo 2412.20374v2'.");
            return USAGE_ERROR;
        }
        String id = resolveRef(args[1], "repo");
        if (id == null) {
            return USAGE_ERROR;
        }

        PaperAnalysis stored = controller.findAnalysis(id).orElse(null);
        if (stored == null) {
            err.println("That paper has not been analysed yet. Run 'rc analyze " + id
                    + "' first.");
            return USAGE_ERROR;
        }

        RepoSummary summary;
        try {
            summary = controller.checkRepository(stored);
        } catch (AgentException e) {
            err.println(e.getMessage());
            return AGENT_ERROR;
        }

        out.println(stored.getPaper().getTitle());
        out.println();
        if (summary.getUrl().isBlank()) {
            // UC08 2a: stated plainly, not as a failure
            out.println("  " + summary.getNote());
            out.println();
            return OK;
        }
        out.println("  repository   " + summary.getUrl());
        out.println("  code         " + (summary.isCodeAvailable() ? "present" : "not found"));
        out.println("  data         " + (summary.isHasDatasets() ? "present" : "not found"));
        out.println("  evaluation   "
                + (summary.isHasEvalScripts() ? "scripts present" : "none found"));
        out.println("  licence      " + summary.getLicense());
        out.println("  README       " + summary.getReadmeQuality());
        out.println("  reproducible " + (summary.isReproducible()
                ? "plausibly - code, data and an evaluation are all there"
                : "not from this listing alone"));
        if (!summary.getNote().isBlank()) {
            out.println();
            out.println("  " + summary.getNote());
        }
        out.println();
        out.println("  (metadata only - no code was downloaded or run)");
        out.println();
        return OK;
    }

    // --- landscape -----------------------------------------------------------

    private int landscape() {
        StrategyNode root = controller.buildSolutionLandscape();

        if (root.paperCount() == 0) {
            out.println("Nothing to map yet. Analyse a paper first with 'rc analyze <id>'.");
            return OK;
        }
        out.println("Solution landscape - " + root.paperCount() + " analysed paper(s)");
        if (controller.tooFewPapersForLandscape()) {
            // UC12 3a: say so rather than inventing branches from one paper
            out.println("(too few papers for branch summaries - the seeded families are"
                    + " shown as they are)");
        }
        out.println();
        printNode(root, "");
        return OK;
    }

    /** Walks the tree without asking whether a node is a leaf or a branch. */
    private void printNode(StrategyNode node, String indent) {
        for (StrategyNode child : node.getChildren()) {
            out.println(indent + "- " + child.getName()
                    + "  [" + child.paperCount() + "]"
                    + (child.isOwnApproach() ? "   <-- your approach" : ""));
            if (!child.isMainIdeaMissing()) {
                out.println(indent + "    " + child.getMainIdea());
            }
            for (PaperAnalysis paper : child.getPapers()) {
                out.println(indent + "    . " + paper.getPaper().getTitle());
            }
            printNode(child, indent + "    ");
        }
    }

    // --- profile -------------------------------------------------------------

    private int profile(String[] args) {
        if (args.length < 2) {
            err.println("'profile' needs a subcommand: 'profile show' or 'profile set'.");
            return USAGE_ERROR;
        }
        switch (args[1]) {
            case "show":
                return profileShow();
            case "set":
                return profileSet(args);
            default:
                err.println("Unknown profile subcommand: " + args[1]);
                return USAGE_ERROR;
        }
    }

    private int profileShow() {
        ProjectProfile p = controller.getProfile();
        if (p.getResearchQuestion().isBlank() && p.getDatasets().isEmpty()) {
            out.println("No project profile yet. Set one with:");
            out.println("  rc profile set --question \"...\" --datasets \"...\" "
                    + "--metrics \"...\"");
            return OK;
        }

        out.println("Project profile");
        out.println("  question : " + p.getResearchQuestion());
        out.println("  datasets : " + String.join(", ", p.getDatasets()));
        out.println("  models   : " + String.join(", ", p.getModels()));
        out.println("  metrics  : " + String.join(", ", p.getMetrics()));
        out.println("  results  : " + String.join(", ", p.getOwnResults()));
        out.println("  complete : " + p.isComplete());
        if (!p.isComplete()) {
            out.println("  missing  : " + String.join(", ", p.missingFields()));
        }
        return OK;
    }

    private int profileSet(String[] args) {
        Map<String, String> flags;
        try {
            flags = parseFlags(args, 2, PROFILE_FLAGS);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return USAGE_ERROR;
        }

        ProjectProfile p = new ProjectProfile(flags.getOrDefault("--question", ""));
        p.setDatasets(items(flags.get("--datasets")));
        p.setModels(items(flags.get("--models")));
        p.setMetrics(items(flags.get("--metrics")));
        p.setOwnResults(items(flags.get("--results")));

        try {
            controller.saveProfile(p);
        } catch (ValidationException e) {
            err.println("Nothing was saved. Missing required field(s): "
                    + String.join(", ", e.getFields()));
            return VALIDATION_ERROR;
        } catch (StorageException e) {
            err.println(e.getMessage());
            return STORAGE_ERROR;
        }

        out.println("Saved. Project stored at " + controller.getStorageLocation());
        return profileShow();
    }

    // --- argument parsing ----------------------------------------------------

    /**
     * Reads {@code --flag value} pairs.
     *
     * <p>An unknown flag is an error rather than being ignored: a typo such as
     * {@code --dataset} would otherwise discard what the user typed and then
     * fail validation for a field they believe they filled in.
     *
     * @throws IllegalArgumentException with a message fit to print
     */
    private static Map<String, String> parseFlags(String[] args, int from, Set<String> allowed) {
        Map<String, String> flags = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            String flag = args[i];
            if (!allowed.contains(flag)) {
                throw new IllegalArgumentException("Unknown option: " + flag
                        + "\nExpected one of: " + String.join(", ", allowed));
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(flag + " needs a value.");
            }
            flags.put(flag, args[++i]);
        }
        return flags;
    }

    /** Splits a comma-separated value, trimming each entry and dropping blanks. */
    private static List<String> items(String value) {
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

    private void printUsage() {
        out.println("""
                Project-Aware Research Consultant - command line

                Usage: rc <command> [options]

                Commands
                  profile show              print the current project profile
                  profile set [options]     create or replace the project profile
                  discover                  find papers that matter for this project
                  analyze <paper>           extract a paper's structure and strategy
                  analyses                  list every analysed paper
                  compare <paper>           compare an analysed paper to your project
                  import <directory>        read your own project's current state
                  changes                   what changed in your project since last import
                  repo <paper>              does the paper's code exist and look reusable

<paper> is either the arXiv id (2412.20374v2) or the row number from
the last 'rc discover' listing (2).
                  landscape                 the space of solution approaches, and where
                                            your own approach sits in it
                  where                     print the file the project is stored in
                  help                      this message

                profile set options
                  --question "..."          the research question          (required)
                  --datasets "a, b"         datasets used                  (required)
                  --metrics  "a, b"         metrics reported               (required)
                  --models   "a, b"         models used
                  --results  "a, b"         your own results so far

                Example
                  rc profile set --question "Does conditioning a generator on \\
                demographics close the fairness gap?" \\
                    --datasets "CheXpert, MIMIC-CXR" --metrics "AUC, subgroup AUC gap"
                """);
    }
}
