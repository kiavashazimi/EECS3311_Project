package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.context.ProjectContextProvider;
import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * F02 and F10 — loads the live state of the researcher's own project, and says
 * what has changed since last time.
 *
 * <p>Holds the providers in order and asks every one that can read the
 * directory, which is the half of the Adapter pattern a class diagram cannot
 * show. They are complementary rather than interchangeable — the coding agent
 * supplies intent, the file reader supplies facts — so their snapshots are
 * merged instead of the first one winning. A provider that is present but
 * unusable contributes nothing and never blocks the import, which is UC02
 * flows 3a and 3b.
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
     * <p>Retained because the Stage 1 class diagram declares it, and because it
     * answers "which reader takes precedence here?" on its own. The import no
     * longer routes through it: every available provider contributes (C28), so
     * this reports the highest-priority one rather than the only one used.
     *
     * @return the highest-priority provider that can read the path, or null
     *     when none can
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

        List<ProjectContextProvider> usable = availableProviders(pending);
        if (usable.isEmpty()) {
            // UC02 1a: reported, and nothing is stored
            throw new AgentException("cannot read a project at " + pending);
        }
        Plan plan = new Plan()
                .addStep("read the project with " + usable.stream()
                        .map(ProjectContextProvider::name)
                        .collect(Collectors.joining(" and ")))
                .addStep("diff against the previous snapshot")
                .addStep("summarise what is notable");
        for (ProjectContextProvider provider : usable) {
            plan.addToolCall(provider.name());
        }
        return plan;
    }

    /** @return every provider that can read this directory, in declared order */
    private List<ProjectContextProvider> availableProviders(Path projectPath) {
        List<ProjectContextProvider> usable = new ArrayList<>();
        for (ProjectContextProvider provider : providers) {
            if (provider.isAvailable(projectPath)) {
                usable.add(provider);
            }
        }
        return usable;
    }

    @Override
    protected Object executeTools(Plan plan) throws AgentException {
        ContextSnapshot merged = null;
        IOException last = null;

        // Every available provider contributes, rather than the first one
        // winning. The two readers are complementary, not interchangeable:
        // the coding agent supplies intent and the file reader supplies facts,
        // so stopping at the first meant whichever answered silently discarded
        // the other's half. With a CLAUDE.md present that cost F10 its entire
        // basis for a diff, and made an unchanged project report its own result
        // files as removed. F02's Stage 1 contract asks for facts *plus* the
        // description, which only merging can deliver.
        //
        // A provider that is present but unusable still falls through to the
        // next, so a failing stretch-goal integration can never block the
        // import (UC02 3b).
        for (ProjectContextProvider provider : availableProviders(pending)) {
            try {
                merged = mergeInto(merged, provider.scan(pending));
            } catch (IOException e) {
                last = e;
            }
        }
        if (merged == null) {
            throw new AgentException("could not read the project at " + pending
                    + (last == null ? "" : ": " + last.getMessage()), last);
        }
        return merged;
    }

    /**
     * Folds one provider's snapshot into the running result.
     *
     * <p>First non-empty wins per field, so provider order still decides any
     * overlap and the result does not depend on how many providers ran. Today
     * the two implementations populate disjoint fields, which makes the rule
     * invisible; it exists so that a third provider cannot make the outcome
     * order-dependent in a way nobody notices.
     *
     * @param into the merged snapshot so far, or null for the first contributor
     * @param addition the snapshot just scanned, never null
     * @return the merged snapshot
     */
    private static ContextSnapshot mergeInto(ContextSnapshot into, ContextSnapshot addition) {
        if (into == null) {
            ContextSnapshot first = new ContextSnapshot(
                    addition.getScannedAt(), addition.getProjectPath());
            first.setProviderName(addition.getProviderName());
            first.setRecentCommits(addition.getRecentCommits());
            first.setConfigEntries(addition.getConfigEntries());
            first.setResultFiles(addition.getResultFiles());
            first.setAgentDescription(addition.getAgentDescription());
            return first;
        }
        if (!addition.getProviderName().isBlank()) {
            into.setProviderName(into.getProviderName().isBlank()
                    ? addition.getProviderName()
                    : into.getProviderName() + " + " + addition.getProviderName());
        }
        if (into.getRecentCommits().isEmpty()) {
            into.setRecentCommits(addition.getRecentCommits());
        }
        if (into.getConfigEntries().isEmpty()) {
            into.setConfigEntries(addition.getConfigEntries());
        }
        if (into.getResultFiles().isEmpty()) {
            into.setResultFiles(addition.getResultFiles());
        }
        if (into.getAgentDescription().isBlank()) {
            into.setAgentDescription(addition.getAgentDescription());
        }
        return into;
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
