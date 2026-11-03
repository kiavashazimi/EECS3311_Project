package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.context.ProjectContextProvider;
import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * F02 and F10 — loads the live state of the researcher's own project, and says
 * what has changed since last time.
 *
 * <p>Holds the providers in order and picks one at runtime, which is the half
 * of the Adapter pattern a class diagram cannot show. A provider that is
 * present but unusable falls through to the next, so a failing stretch-goal
 * integration can never block the import — UC02 flows 3a and 3b.
 */
public class ProjectContextAgent extends Agent {

    private final List<ProjectContextProvider> providers;

    /** Carried from {@link #plan} to the later steps. */
    private Path pending;
    private ContextSnapshot previous;

    /**
     * @param providers tried in order; the last one should be the fallback that
     *     is always available for a readable directory
     */
    public ProjectContextAgent(ToolManager tools, List<ProjectContextProvider> providers) {
        super(tools);
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("at least one provider is required");
        }
        this.providers = List.copyOf(providers);
    }

    /**
     * Picks the first provider that says it can read this directory.
     *
     * @return the chosen provider, or null when none can
     */
    public ProjectContextProvider selectProvider(Path projectPath) {
        for (ProjectContextProvider provider : providers) {
            if (provider.isAvailable(projectPath)) {
                return provider;
            }
        }
        return null;
    }

    /**
     * What an import produced: the stored facts, plus prose about them.
     *
     * <p>The summary is deliberately <em>not</em> a field on
     * {@link ContextSnapshot}. A snapshot is persisted state and the summary is
     * a rendering of it — storing prose generated from the facts would mean a
     * file whose summary could silently disagree with the facts beside it, and
     * would be regenerated on every scan anyway.
     *
     * @param snapshot the facts, always present
     * @param summary readable prose, or "" when the model was unavailable
     *     (UC02 flow 5a)
     */
    public record ImportedContext(ContextSnapshot snapshot, String summary) {
        public ImportedContext {
            if (snapshot == null) {
                throw new IllegalArgumentException("snapshot must not be null");
            }
            summary = summary == null ? "" : summary.strip();
        }

        /** @return true when the facts stand but the prose is missing */
        public boolean isSummaryMissing() {
            return summary.isEmpty();
        }
    }

    /** What the agent works from. */
    public record Import(Path projectPath, ContextSnapshot previous) {
        public Import {
            if (projectPath == null) {
                throw new IllegalArgumentException("projectPath must not be null");
            }
        }
    }

    // --- Template Method hooks ----------------------------------------------

    @Override
    protected Plan plan(Object input) throws AgentException {
        if (!(input instanceof Import request)) {
            throw new AgentException("import needs an Import, got "
                    + (input == null ? "null" : input.getClass().getSimpleName()));
        }
        this.pending = request.projectPath();
        this.previous = request.previous();

        ProjectContextProvider chosen = selectProvider(pending);
        if (chosen == null) {
            // UC02 1a: reported, and nothing is stored
            throw new AgentException("cannot read a project at " + pending);
        }
        return new Plan()
                .addStep("read the project with " + chosen.name())
                .addStep("diff against the previous snapshot")
                .addStep("summarise what is notable")
                .addToolCall(chosen.name());
    }

    @Override
    protected Object executeTools(Plan plan) throws AgentException {
        ContextSnapshot snapshot = null;
        IOException last = null;

        // tries each provider in turn: one that is present but unusable must
        // not block the import (UC02 3b)
        for (ProjectContextProvider provider : providers) {
            if (!provider.isAvailable(pending)) {
                continue;
            }
            try {
                snapshot = provider.scan(pending);
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (snapshot == null) {
            throw new AgentException("could not read the project at " + pending
                    + (last == null ? "" : ": " + last.getMessage()), last);
        }
        return snapshot;
    }

    @Override
    protected Object summarize(Object result) {
        ContextSnapshot snapshot = (ContextSnapshot) result;
        List<String> notable = notableItems(snapshot);

        try {
            String summary = tools.callLLM("""
                    Summarise what a researcher's project is currently doing, for
                    their own reference. Two or three sentences, plain prose, no
                    bullet points and no preamble.

                    %s

                    Say what the project appears to be working on and what changed
                    most recently. Do not invent anything that is not listed above.
                    """.formatted(String.join("\n", notable)));
            return new ImportedContext(snapshot, summary);
        } catch (LLMUnavailableException e) {
            // UC02 5a: the snapshot still stands, the summary is marked missing
            return new ImportedContext(snapshot, "");
        }
    }

    /** The facts worth summarising, including the diff when there is one. */
    private List<String> notableItems(ContextSnapshot snapshot) {
        List<String> items = new ArrayList<>();
        items.add("Project path: " + snapshot.getProjectPath());
        items.add("Facts came from: " + snapshot.getProviderName());

        if (!snapshot.getAgentDescription().isBlank()) {
            items.add("The coding agent's own description of the project:\n"
                    + snapshot.getAgentDescription());
        }
        if (!snapshot.getRecentCommits().isEmpty()) {
            items.add("Recent commits:\n- " + String.join("\n- ", snapshot.getRecentCommits()));
        }
        if (!snapshot.getConfigEntries().isEmpty()) {
            items.add("Configuration files: " + snapshot.getConfigEntries());
        }
        if (!snapshot.getResultFiles().isEmpty()) {
            items.add("Result files: " + String.join(", ", snapshot.getResultFiles()));
        }
        if (previous != null && !previous.getScannedAt().isBlank()) {
            List<String> changes = snapshot.changesSince(previous);
            items.add(changes.isEmpty()
                    ? "Nothing has changed since " + previous.getScannedAt()
                    : "Changes since " + previous.getScannedAt() + ":\n- "
                            + String.join("\n- ", changes));
        }
        return items;
    }

    // --- F10 -----------------------------------------------------------------

    /**
     * The deterministic half of F10: what differs between two snapshots.
     *
     * <p>Separate from the prose so that "what changed" survives an unavailable
     * model — the diff is computed from the two snapshots and needs nothing else.
     */
    public List<String> changesBetween(ContextSnapshot older, ContextSnapshot current) {
        if (older == null || current == null) {
            throw new IllegalArgumentException("both snapshots are required");
        }
        return current.changesSince(older);
    }

    /** @return a readable summary of the diff, or "" when the model is unavailable */
    public String summarizeChanges(ContextSnapshot older, ContextSnapshot current) {
        List<String> changes = changesBetween(older, current);
        if (changes.isEmpty()) {
            // UC09 4a: an explicit "no changes", not an empty panel
            return "No changes since " + older.getScannedAt() + ".";
        }
        try {
            return tools.callLLM("""
                    A researcher's own project changed. Describe what they appear to
                    have been doing, in two or three sentences of plain prose.

                    Changes:
                    - %s

                    Do not invent anything beyond the list.
                    """.formatted(String.join("\n- ", changes))).strip();
        } catch (LLMUnavailableException e) {
            return "";
        }
    }
}
