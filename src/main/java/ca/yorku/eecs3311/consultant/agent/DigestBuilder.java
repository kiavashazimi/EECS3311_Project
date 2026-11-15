package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.domain.ContextSnapshot;
import ca.yorku.eecs3311.consultant.domain.Digest;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import ca.yorku.eecs3311.consultant.memory.ProjectMemory;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F12 — the weekly digest, and the consumer side of the Observer.
 *
 * <p>Most of the work happens long before a digest is asked for. Registered as
 * a {@link PaperFeedListener}, this receives {@link #onNewPaper} every time F03
 * discovers something, so by the time {@link #buildDigest()} is called it
 * already holds the new papers and queries nothing. A polling design would have
 * to ask what had changed; this one was told as it happened, which is why the
 * pattern matters here and not merely in the GUI feed.
 *
 * <p>Assembly is deterministic and the narrative is not: the papers, their
 * stored strategy tags and the latest project scan are collected without the
 * model, and only the prose comes from {@code LLMClient}. An unreachable model
 * therefore costs the summary and nothing else (UC11 4a).
 */
public class DigestBuilder implements PaperFeedListener {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ToolManager tools;
    private final ProjectMemory memory;

    public DigestBuilder(ToolManager tools, ProjectMemory memory) {
        if (tools == null) {
            throw new IllegalArgumentException("tools must not be null");
        }
        if (memory == null) {
            throw new IllegalArgumentException("memory must not be null");
        }
        this.tools = tools;
        this.memory = memory;
    }

    /**
     * Records a discovered paper.
     *
     * <p>Never throws: {@link PaperFeedListener} forbids it, because the
     * subject broadcasts to every listener and one failure must not stop the
     * others from being told. The papers go into {@link ProjectMemory} rather
     * than a field here, so that {@code rc digest} — a different JVM from the
     * {@code rc discover} that found them — still has something to report.
     */
    @Override
    public void onNewPaper(PaperRecord paper) {
        memory.addDigestPaper(paper);
    }

    /** @return how many papers are waiting to be reported */
    public int pendingCount() {
        return memory.getDigestPending().size();
    }

    /**
     * Assembles the digest and marks its contents reported.
     *
     * @return the digest. {@code isEmpty()} means there was nothing new, which
     *     the caller states explicitly rather than showing a blank view
     *     (UC11 3a). {@code isSummaryMissing()} means the model could not be
     *     reached and the structured digest stands on its own
     */
    public Digest buildDigest() {
        String now = LocalDateTime.now().format(STAMP);
        Digest digest = new Digest(now);

        List<PaperRecord> pending = memory.getDigestPending();

        // Tags are taken from the whole batch, before anything is filtered out.
        // The only papers that carry tags are analysed ones, and analysed ones
        // are exactly what the filter removes - reading tags afterwards would
        // leave this line permanently empty.
        List<String> highlights = highlightsFor(pending);

        // "New" has to mean new to the researcher, not new to this queue.
        // Discovery re-finds the same papers every run and the queue is cleared
        // after each digest, so without this a paper is announced again every
        // week, including papers already read and analysed.
        List<PaperRecord> unseen = new ArrayList<>();
        for (PaperRecord paper : pending) {
            if (!alreadySeen(paper)) {
                unseen.add(paper);
            }
        }

        for (PaperRecord paper : unseen) {
            digest.addPaper(paper);
        }
        digest.setStrategyHighlights(highlights);
        digest.setProjectChanges(describeLatestScan(memory.getLastSnapshot()));

        if (digest.isEmpty()) {
            // Nothing to narrate, so the model is not called at all - and the
            // marker is deliberately left alone. Stamping it here made the
            // first ever digest report "nothing new since <this very minute>",
            // claiming a previous digest that never ran. The marker means "when
            // a digest last had something to report", which is the only reading
            // that makes "nothing new since" a useful sentence.
            //
            // The queue is still emptied: everything in it was filtered out as
            // already seen, so keeping it would only re-filter the same papers
            // on every future digest while the queue grew without bound.
            memory.clearDigestPending();
            return digest;
        }

        try {
            digest.setSummaryText(tools.callLLM(prompt(digest)));
        } catch (LLMUnavailableException e) {
            // UC11 4a: the assembled digest is still worth showing
            digest.setSummaryText("");
        }

        // cleared whether or not the narrative succeeded: these papers have
        // been listed, and repeating them in the next digest would be noise
        memory.markReported(unseen.stream().map(PaperRecord::getId).toList());
        memory.clearDigestPending();
        memory.setLastDigestAt(now);
        return digest;
    }

    /**
     * The strategy tags of those new papers that have been analysed.
     *
     * <p>A paper nobody has analysed contributes nothing. Tagging it from its
     * title would put a guess in a report the researcher reads as a record of
     * what was found.
     *
     * <p>A paper whose tags the classifier marked uncertain contributes nothing
     * either, for the same reason the solution landscape refuses to place one
     * (C26): an uncertain tag is a guess, and printing it here states it as
     * fact. On a set of concept-mapping papers the two tags that survived were
     * {@code reinforcement learning} and {@code curriculum learning}, both from
     * term collisions, and both on papers already flagged uncertain.
     */
    private List<String> highlightsFor(List<PaperRecord> papers) {
        Set<String> tags = new LinkedHashSet<>();
        for (PaperRecord paper : papers) {
            memory.findAnalysis(paper.getId())
                    .filter(analysis -> !analysis.isTagsUncertain())
                    .map(PaperAnalysis::getStrategyTags)
                    .ifPresent(tags::addAll);
        }
        return new ArrayList<>(tags);
    }

    /**
     * @param paper a paper waiting in the queue
     * @return true when the researcher has already met this paper - a previous
     *     digest listed it, or they analysed it, which they could only do after
     *     seeing it
     */
    private boolean alreadySeen(PaperRecord paper) {
        return memory.hasBeenReported(paper.getId())
                || memory.findAnalysis(paper.getId()).isPresent();
    }

    /**
     * Describes the most recent scan of the researcher's project.
     *
     * <p>Stage 1 (UC11 step 3) says the digest includes "the latest change
     * summary". There is none to include: F10 computes its summary on demand
     * and deliberately does not persist it, because a stored judgement would
     * disagree with a project that has since moved on. The digest therefore
     * reports what the latest scan actually found, which is stored, rather
     * than a narrative that is not. Recorded as a Stage 2 change.
     *
     * @return a description, or blank when the project has never been scanned —
     *     which is not the same claim as nothing having changed
     */
    private String describeLatestScan(ContextSnapshot snapshot) {
        if (snapshot == null || snapshot.getScannedAt().isBlank()) {
            return "";
        }
        StringBuilder b = new StringBuilder("Your project at ")
                .append(snapshot.getProjectPath())
                .append(", last read ").append(snapshot.getScannedAt()).append('.');
        if (!snapshot.getRecentCommits().isEmpty()) {
            b.append(" Recent commits: ")
                    .append(String.join("; ", snapshot.getRecentCommits())).append('.');
        }
        if (!snapshot.getConfigEntries().isEmpty()) {
            b.append(" Config keys: ")
                    .append(String.join(", ", snapshot.getConfigEntries().keySet()))
                    .append('.');
        }
        if (!snapshot.getResultFiles().isEmpty()) {
            b.append(' ').append(snapshot.getResultFiles().size()).append(" result file(s).");
        }
        return b.toString();
    }

    private String prompt(Digest digest) {
        StringBuilder papers = new StringBuilder();
        for (PaperRecord p : digest.getNewPapers()) {
            papers.append("- ").append(p.getId()).append(' ').append(p.getTitle())
                    .append(" (").append(p.getDate()).append("): ")
                    .append(p.getSummary()).append('\n');
        }
        return """
                Write a short digest for a researcher, in at most 120 words.

                Their research question: %s

                New papers since the last digest:
                %s
                Approaches the researcher has already catalogued from this same
                batch, from papers they analysed and which are therefore not
                listed above: %s

                Their own project: %s

                Say what is worth their attention and why, in relation to their
                research question. Use only what is above. Do not rank the papers
                by importance unless the text supports it, and do not describe a
                paper that is not listed.
                """.formatted(
                        memory.getProfile().getResearchQuestion(),
                        papers.isEmpty() ? "(none)\n" : papers,
                        digest.getStrategyHighlights().isEmpty()
                                ? "(none analysed yet)"
                                : String.join(", ", digest.getStrategyHighlights()),
                        digest.getProjectChanges().isBlank()
                                ? "(never scanned)" : digest.getProjectChanges());
    }
}
