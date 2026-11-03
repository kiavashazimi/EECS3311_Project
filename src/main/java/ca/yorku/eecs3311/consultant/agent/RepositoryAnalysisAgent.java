package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.RepoMetadata;
import ca.yorku.eecs3311.consultant.domain.RepoSummary;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import ca.yorku.eecs3311.consultant.repo.RepositoryUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F09 — judges whether a paper's code exists and looks reusable.
 *
 * <p>Everything that decides *whether* the code is usable is derived from the
 * top-level listing, deterministically. The model is asked only to judge the
 * README and write a note. That split matters: "this repository has evaluation
 * scripts" is a checkable fact about a file listing, and a model guessing it
 * from a repository name would be fabricating evidence a researcher might rely
 * on when choosing what to reproduce.
 *
 * <p>Only metadata is ever read. Repository code is never downloaded and never
 * executed.
 */
public class RepositoryAnalysisAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> CODE_SUFFIXES = List.of(".py", ".java", ".ipynb",
            ".cpp", ".c", ".m", ".r", ".jl", ".sh", ".go", ".rs", ".scala", ".kt");
    private static final List<String> CODE_DIRECTORIES = List.of("src", "lib", "scripts",
            "code", "models", "model", "tools");
    private static final List<String> DATA_ENTRIES = List.of("data", "datasets", "dataset");
    private static final Pattern EVAL_ENTRY = Pattern.compile(
            "(?i).*(eval|evaluate|benchmark|test|metric|score|inference|predict).*");

    /** Carried from {@link #plan} to the later steps. */
    private PaperAnalysis pending;

    public RepositoryAnalysisAgent(ToolManager tools) {
        super(tools);
    }

    /** F09 — the entry point a client calls. */
    public RepoSummary checkRepository(PaperAnalysis analysis) throws AgentException {
        return (RepoSummary) run(analysis);
    }

    // --- Template Method hooks ----------------------------------------------

    @Override
    protected Plan plan(Object input) throws AgentException {
        if (!(input instanceof PaperAnalysis analysis)) {
            throw new AgentException("repository analysis needs a PaperAnalysis, got "
                    + (input == null ? "null" : input.getClass().getSimpleName()));
        }
        this.pending = analysis;
        return new Plan()
                .addStep("read the repository's metadata, never its code")
                .addStep("derive code, data and evaluation presence from the listing")
                .addStep("judge the README and write a note")
                .addToolCall("fetchRepo " + analysis.getPaper().getRepoUrl());
    }

    @Override
    protected Object executeTools(Plan plan) {
        String url = pending.getPaper().getRepoUrl();
        if (url == null || url.isBlank()) {
            // UC08 2a: a plain statement, not an error
            return RepoSummary.unavailable("", "no repository linked");
        }
        try {
            return tools.fetchRepo(url);
        } catch (RepositoryUnavailableException e) {
            // UC08 3a: inaccessible, with the reason
            return RepoSummary.unavailable(url, e.getMessage());
        }
    }

    @Override
    protected Object summarize(Object result) {
        if (result instanceof RepoSummary alreadyUnavailable) {
            return alreadyUnavailable;
        }
        RepoMetadata metadata = (RepoMetadata) result;

        RepoSummary summary = new RepoSummary();
        summary.setUrl(metadata.getUrl());
        summary.setLicense(metadata.hasLicense() ? metadata.getLicense() : "none stated");
        summary.setCodeAvailable(hasCode(metadata));
        summary.setHasDatasets(hasData(metadata));
        summary.setHasEvalScripts(hasEvaluation(metadata));

        addJudgement(summary, metadata);
        return summary;
    }

    // --- the deterministic half ----------------------------------------------

    static boolean hasCode(RepoMetadata metadata) {
        for (String entry : metadata.getTopLevelFiles()) {
            String lower = entry.toLowerCase(Locale.ROOT);
            if (CODE_SUFFIXES.stream().anyMatch(lower::endsWith)
                    || CODE_DIRECTORIES.contains(lower)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasData(RepoMetadata metadata) {
        for (String entry : metadata.getTopLevelFiles()) {
            String lower = entry.toLowerCase(Locale.ROOT);
            if (DATA_ENTRIES.contains(lower) || lower.endsWith(".csv")
                    || lower.endsWith(".tsv") || lower.endsWith(".parquet")) {
                return true;
            }
        }
        return false;
    }

    static boolean hasEvaluation(RepoMetadata metadata) {
        for (String entry : metadata.getTopLevelFiles()) {
            String lower = entry.toLowerCase(Locale.ROOT);
            boolean looksLikeCodeOrDir = CODE_SUFFIXES.stream().anyMatch(lower::endsWith)
                    || !lower.contains(".");
            if (looksLikeCodeOrDir && EVAL_ENTRY.matcher(lower).matches()) {
                return true;
            }
        }
        return false;
    }

    // --- the judged half -----------------------------------------------------

    /**
     * Asks the model to rate the README and write one note.
     *
     * <p>When it is unavailable the facts above still stand and the quality is
     * recorded as unknown, never as good.
     */
    private void addJudgement(RepoSummary summary, RepoMetadata metadata) {
        String listing = String.join(", ", metadata.getTopLevelFiles());
        try {
            String reply = tools.callLLM("""
                    Judge how reusable a paper's code repository looks, from its
                    top-level file listing alone. You cannot see file contents.

                    Repository: %s
                    Licence: %s
                    Top-level entries: %s

                    Return JSON only:
                    {"readme_quality":"none|minimal|adequate|thorough",
                     "note":"one or two sentences on what a researcher would find
                     here and what is missing"}

                    Judge only from the listing. Do not assume a file's contents,
                    and do not claim anything the listing does not show.
                    """.formatted(metadata.getUrl(), summary.getLicense(), listing));

            Matcher json = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(reply);
            if (!json.find()) {
                summary.setReadmeQuality("unknown");
                return;
            }
            JsonNode node = MAPPER.readTree(json.group());
            summary.setReadmeQuality(quality(node.path("readme_quality").asText("")));
            summary.setNote(node.path("note").asText("").strip());
        } catch (LLMUnavailableException e) {
            summary.setReadmeQuality("unknown");
            summary.setNote("");
        } catch (Exception e) {
            summary.setReadmeQuality("unknown");
        }
    }

    /** Keeps the rating to the four values the UI knows how to show. */
    private static String quality(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return List.of("none", "minimal", "adequate", "thorough").contains(value)
                ? value
                : "unknown";
    }
}
