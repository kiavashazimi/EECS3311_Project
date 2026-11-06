package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.analysis.ComparabilityChecker;
import ca.yorku.eecs3311.consultant.analysis.StrategyTaxonomy;
import ca.yorku.eecs3311.consultant.domain.Comparability;
import ca.yorku.eecs3311.consultant.domain.ComparisonResult;
import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.ProjectProfile;
import ca.yorku.eecs3311.consultant.domain.StrategyNode;
import ca.yorku.eecs3311.consultant.llm.LLMUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F06 and F07 — explains how a paper relates to the researcher's own project,
 * and whether the two sets of numbers can fairly be compared.
 *
 * <p>The order is deliberate and is what UC05's {@code <<include>>} of UC06
 * means in code: the **deterministic** verdict is computed first, then the
 * model is asked to put it into words. The reverse order would let a fluent
 * narrative talk the researcher into a comparison the data does not support.
 * When the model is unavailable the verdict and the raw mismatch list still
 * stand, which is UC06 flow 3a.
 */
public class ResearchConsultantAgent extends Agent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ComparabilityChecker checker;
    private final StrategyTaxonomy taxonomy;

    /** Below this, sub-branches would be noise presented as structure. */
    static final int MIN_PAPERS_FOR_SUBBRANCHES = 3;

    /** Carried from {@link #plan} to the later steps. */
    private Comparison pending;

    public ResearchConsultantAgent(ToolManager tools) {
        this(tools, new ComparabilityChecker(), new StrategyTaxonomy());
    }

    public ResearchConsultantAgent(ToolManager tools, ComparabilityChecker checker) {
        this(tools, checker, new StrategyTaxonomy());
    }

    public ResearchConsultantAgent(ToolManager tools, ComparabilityChecker checker,
            StrategyTaxonomy taxonomy) {
        super(tools);
        if (checker == null) {
            throw new IllegalArgumentException("checker must not be null");
        }
        if (taxonomy == null) {
            throw new IllegalArgumentException("taxonomy must not be null");
        }
        this.checker = checker;
        this.taxonomy = taxonomy;
    }

    /** What the agent works from: a paper and the project to compare it against. */
    public record Comparison(PaperAnalysis analysis, ProjectProfile profile) {
        public Comparison {
            if (analysis == null) {
                throw new IllegalArgumentException("analysis must not be null");
            }
            if (profile == null) {
                throw new IllegalArgumentException("profile must not be null");
            }
        }
    }

    /** F06 — the method the Stage 1 class diagram names. */
    public ComparisonResult compare(PaperAnalysis analysis, ProjectProfile profile)
            throws AgentException {
        return (ComparisonResult) run(new Comparison(analysis, profile));
    }

    public ComparabilityChecker getChecker() {
        return checker;
    }

    // --- Template Method hooks ----------------------------------------------

    @Override
    protected Plan plan(Object input) throws AgentException {
        if (!(input instanceof Comparison comparison)) {
            throw new AgentException("comparison needs a Comparison, got "
                    + (input == null ? "null" : input.getClass().getSimpleName()));
        }
        this.pending = comparison;
        return new Plan()
                .addStep("check result comparability deterministically")
                .addStep("list every specific mismatch")
                .addStep("ask the model to explain the comparison in context")
                .addToolCall("comparability check");
    }

    @Override
    protected Object executeTools(Plan plan) throws AgentException {
        if (pending == null) {
            throw new AgentException("nothing to compare");
        }
        // F07 runs here, before the model is involved at all
        ComparisonResult result = new ComparisonResult(pending.analysis().getPaper().getTitle());
        result.setComparability(checker.isComparable(pending.analysis(), pending.profile()));
        result.setMismatches(checker.findMismatches(pending.analysis(), pending.profile()));
        result.setComparabilityNote(checker.explain(pending.analysis(), pending.profile()));
        return result;
    }

    @Override
    protected Object summarize(Object result) {
        ComparisonResult comparison = (ComparisonResult) result;
        try {
            addNarrative(comparison, tools.callLLM(prompt(comparison)));
        } catch (LLMUnavailableException e) {
            // UC06 3a / UC05 5a: the verdict and the mismatches are already
            // set and stand on their own; only the prose is missing
            comparison.setNarrative("");
        }
        return comparison;
    }

    // --- the narrative -------------------------------------------------------

    private String prompt(ComparisonResult computed) {
        PaperAnalysis a = pending.analysis();
        ProjectProfile p = pending.profile();
        return """
                Compare a paper with a researcher's own project.

                THE RESEARCHER'S PROJECT
                Question: %s
                Datasets: %s
                Models: %s
                Metrics: %s
                Their results so far: %s

                THE PAPER: %s
                Problem: %s
                Method: %s
                Dataset: %s
                Model: %s
                Metrics: %s
                Results: %s
                Limitations: %s

                A deterministic check has ALREADY decided whether the two sets of
                numbers can be compared. Its verdict is %s, because: %s
                Do not contradict that verdict or claim the numbers are comparable
                when it says they are not. Explain it, in context.

                Return JSON only:
                {"similarities":["..."],"differences":["..."],
                 "narrative":"two or three sentences: what this paper means for
                 this project, and what is genuinely novel on either side"}

                Be concrete and use the two projects' own terms. Do not invent
                numbers that are not given above.
                """.formatted(
                p.getResearchQuestion(),
                String.join(", ", p.getDatasets()),
                String.join(", ", p.getModels()),
                String.join(", ", p.getMetrics()),
                String.join("; ", p.getOwnResults()),
                a.getPaper().getTitle(),
                a.getProblem(), a.getMethod(), a.getDataset(), a.getModel(),
                String.join(", ", a.getMetrics()), a.getResults(), a.getLimitations(),
                computed.getComparability().label(),
                computed.getMismatches().isEmpty()
                        ? "the dataset and the measure agree"
                        : String.join("; ", computed.getMismatches()));
    }

    /**
     * Reads the model's reply into the result, leaving the deterministic fields
     * untouched. A reply that cannot be parsed costs the narrative only.
     */
    private static void addNarrative(ComparisonResult result, String reply) {
        Matcher json = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(reply);
        if (!json.find()) {
            result.setNarrative("");
            return;
        }
        try {
            JsonNode node = MAPPER.readTree(json.group());
            for (JsonNode item : node.path("similarities")) {
                addIfPresent(item.asText(""), result::addSimilarity);
            }
            for (JsonNode item : node.path("differences")) {
                addIfPresent(item.asText(""), result::addDifference);
            }
            result.setNarrative(node.path("narrative").asText("").strip());
        } catch (Exception e) {
            result.setNarrative("");
        }
    }

    private static void addIfPresent(String value, java.util.function.Consumer<String> sink) {
        if (value != null && !value.isBlank()) {
            sink.accept(value.strip());
        }
    }

    /** @return true when the verdict says the numbers line up */
    public static boolean linesUp(ComparisonResult result) {
        return result.getComparability() == Comparability.COMPARABLE;
    }

    // --- F13: the solution landscape (UC12) ---------------------------------

    /**
     * Builds the tree of solution approaches and marks where the researcher's
     * own sits.
     *
     * <p>The top level is seeded by {@link StrategyTaxonomy#seedRoots()} and
     * never invented. Each analysed paper is placed under the family of its
     * first recognised strategy tag, or under {@code Unclassified} when it has
     * none — UC12 flow 5a, so a paper is never forced into the nearest branch.
     *
     * @param items the analysed papers; may be empty
     * @param profile the researcher's project, used to mark their own branch
     * @return the root, with empty families pruned
     */
    public StrategyNode buildLandscape(List<PaperAnalysis> items, ProjectProfile profile) {
        if (items == null || profile == null) {
            throw new IllegalArgumentException("items and profile are required");
        }
        StrategyNode root = taxonomy.seedRoots();

        for (PaperAnalysis paper : items) {
            placementFor(root, paper).addPaper(paper);
        }
        root.pruneEmptyBranches();

        markOwnApproach(root, profile);
        if (root.paperCount() >= MIN_PAPERS_FOR_SUBBRANCHES) {
            if (builtInFamiliesFit(root)) {
                // summaries first: they list each family's papers, and
                // subdividing moves those papers down into the new children
                describeBranches(root);
                for (StrategyNode family : root.getChildren()) {
                    subdivide(family, profile);
                }
            } else {
                regroupFromPapers(root, profile);
            }
        } else {
            // UC12 3a: the seeded roots stand, and the caller says why
            root.setMainIdea("");
        }
        return root;
    }

    /**
     * The family most of a paper's tags point at.
     *
     * <p>This used to return the first tag that matched any family. Tags come
     * back from {@code StrategyTaxonomy.match()} in taxonomy declaration order,
     * so "first match" meant "whichever family is declared earliest" — and
     * {@code Data-level approaches} is declared first. A pretraining paper
     * tagged {@code synthetic data generation, transfer learning, fine-tuning}
     * was filed under data because of the order of a map literal, not because
     * of what the paper does.
     *
     * <p>A tie is broken in taxonomy order, which is what insertion order gives
     * here. That is arbitrary too, but it is arbitrary <em>and</em> stable: the
     * same papers must produce the same tree every time, or a researcher cannot
     * tell a change in the literature from a change in the tool.
     */
    private StrategyNode placementFor(StrategyNode root, PaperAnalysis paper) {
        // classify() marks the tags uncertain when the taxonomy recognised
        // nothing the model proposed. Measured live: an education paper whose
        // method text said "curriculum" was tagged "curriculum learning" and
        // filed under Training-level approaches, and the branch summary then
        // wrote a rationale for it. The flag already existed and placement
        // ignored it, so a guess was presented exactly like a match.
        if (paper.isTagsUncertain()) {
            StrategyNode unsure = root.find(StrategyTaxonomy.UNCLASSIFIED);
            return unsure == null ? root : unsure;
        }

        Map<String, Integer> votes = new LinkedHashMap<>();
        for (String tag : paper.getStrategyTags()) {
            String family = taxonomy.familyOf(tag);
            if (family != null && root.find(family) != null) {
                votes.merge(family, 1, Integer::sum);
            }
        }

        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> vote : votes.entrySet()) {
            if (vote.getValue() > bestCount) {
                best = vote.getKey();
                bestCount = vote.getValue();
            }
        }
        if (best != null) {
            return root.find(best);
        }

        StrategyNode unclassified = root.find(StrategyTaxonomy.UNCLASSIFIED);
        return unclassified == null ? root : unclassified;
    }

    /**
     * Flags the branch the researcher's own work belongs to.
     *
     * <p>UC12 flow 7a: the profile has no explicit method field, so the branch
     * is derived from the models and the research question. When nothing
     * matches, no branch is marked rather than one being guessed.
     */
    private void markOwnApproach(StrategyNode root, ProjectProfile profile) {
        String ownWork = String.join(" ", profile.getModels()) + " "
                + profile.getResearchQuestion();
        for (String tag : taxonomy.match(ownWork)) {
            StrategyNode node = root.find(taxonomy.familyOf(tag));
            if (node != null) {
                node.setOwnApproach(true);
                return;
            }
        }
    }

    /**
     * Asks the model for one line per branch, in a single call.
     *
     * <p>One call rather than one per branch: the model sees the whole tree and
     * can say how the families differ from each other, which is the useful part.
     */
    private void describeBranches(StrategyNode root) {
        List<StrategyNode> branches = new ArrayList<>();
        for (StrategyNode child : root.getChildren()) {
            branches.add(child);
        }
        if (branches.isEmpty()) {
            return;
        }

        StringBuilder listing = new StringBuilder();
        for (StrategyNode branch : branches) {
            listing.append("- ").append(branch.getName()).append(" (")
                    .append(branch.paperCount()).append(" papers): ");
            for (PaperAnalysis paper : branch.getPapers()) {
                listing.append(paper.getPaper().getTitle()).append("; ");
            }
            listing.append('\n');
        }

        try {
            String reply = tools.callLLM("""
                    These are families of solution approach, with the papers that
                    fall under each.

                    %s
                    For each family, write one line saying what the approaches in it
                    actually do, and how they differ from the other families. Return
                    JSON only, keyed by the family name exactly as written above:
                    {"Family name":"one line", ...}
                    Do not invent families that are not listed.
                    """.formatted(listing));

            Matcher json = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(reply);
            if (!json.find()) {
                return;
            }
            JsonNode node = MAPPER.readTree(json.group());
            for (StrategyNode branch : branches) {
                String idea = node.path(branch.getName()).asText("").strip();
                if (!idea.isBlank()) {
                    branch.setMainIdea(idea);
                }
            }
        } catch (LLMUnavailableException | RuntimeException e) {
            // UC12 6a: the tree stands, branch summaries are marked unavailable
        } catch (Exception e) {
            // a malformed reply costs the summaries only
        }
    }

    /**
     * Whether the built-in families are the right frame for this project.
     *
     * <p>The six top-level families are machine-learning intervention points.
     * They describe one field well and every other field not at all, and a tree
     * headed by {@code Data-level approaches} tells a reader that their field
     * is organised that way. Pointed at education research, the taxonomy placed
     * three papers of twelve — all three by accident, on the words "curriculum",
     * "active learning" and "reinforcement" — and the other nine piled into
     * {@code Unclassified}.
     *
     * <p>So the taxonomy is treated as a hypothesis about the field rather than
     * a fact: it has to account for at least half the analysed papers to head
     * the tree. Below that it is the wrong frame, and the structure comes from
     * the papers instead. The threshold is deliberately blunt — a half is easy
     * to explain to the researcher, and any finer rule would be a guess
     * dressed up as a measurement.
     *
     * @return true when the named families hold at least half the papers
     */
    private boolean builtInFamiliesFit(StrategyNode root) {
        int inNamedFamilies = 0;
        for (StrategyNode child : root.getChildren()) {
            if (!StrategyTaxonomy.UNCLASSIFIED.equals(child.getName())) {
                inNamedFamilies += child.paperCount();
            }
        }
        return inNamedFamilies * 2 >= root.paperCount();
    }

    /**
     * Throws the built-in families away and lets the papers define the top
     * level of the tree.
     *
     * <p>Used when {@link #builtInFamiliesFit} says the taxonomy does not
     * describe this project. The model groups every analysed paper, so the
     * branches are named in the field's own words and no machine-learning
     * family appears at all.
     *
     * <p>If the grouping fails the papers stay on the root, flat. That is a
     * worse tree than the one before this method ran, but it is an honest one:
     * the alternative was six family names that had already been measured as
     * not applying.
     */
    private void regroupFromPapers(StrategyNode root, ProjectProfile profile) {
        List<PaperAnalysis> all = new ArrayList<>();
        for (StrategyNode child : root.getChildren()) {
            collectPapers(child, all);
        }

        List<StrategyNode> groups = proposeGroups("every paper analysed so far", all, profile);
        if (groups.size() < 2) {
            // nothing came back, so the tree is left exactly as the
            // deterministic placement made it. The families are a poor frame
            // for this project, but they are real information, and an
            // unreachable model is no reason to throw them away — and UC12 5a
            // still wants an explicit Unclassified node rather than papers
            // sitting loose on the root
            return;
        }

        for (StrategyNode child : root.getChildren()) {
            root.remove(child);
        }
        root.setPapers(all);
        applyGroups(root, all, groups);
    }

    private void collectPapers(StrategyNode node, List<PaperAnalysis> into) {
        for (PaperAnalysis paper : node.getPapers()) {
            if (!into.contains(paper)) {
                into.add(paper);
            }
        }
        for (StrategyNode child : node.getChildren()) {
            collectPapers(child, into);
        }
    }

    /**
     * Asks the model to divide one family into sub-branches, and rebuilds that
     * part of the tree from the answer.
     *
     * <p><strong>This is the adaptive half of F13.</strong> The six top-level
     * families are a fixed vocabulary of machine-learning intervention points,
     * which is useful inside the field they were written for and meaningless
     * outside it — point the tool at education research and every paper lands
     * in {@code Unclassified}. Stage 1 specified both halves ("the fixed
     * top-level categories and all tree traversal are deterministic; proposing
     * sub-branches, placing papers and writing branch summaries are AI") and
     * only the deterministic half was built. This is the other half.
     *
     * <p>Papers are offered to the model by position, not by id. Ids here are
     * 40-character Semantic Scholar hashes, and a model that has to echo one
     * back can corrupt it in a way nothing downstream would catch; a small
     * integer can be range-checked exactly.
     *
     * <p>Every failure leaves the family exactly as it was. A tree with no
     * sub-branches is the tree this feature produced before today, so the
     * degraded path is a known-good state rather than a broken one.
     */
    private void subdivide(StrategyNode family, ProjectProfile profile) {
        List<PaperAnalysis> papers = new ArrayList<>(family.getPapers());
        List<StrategyNode> groups = proposeGroups(family.getName(), papers, profile);
        if (groups.size() < 2) {
            // one group restates the family; none means the model declined
            return;
        }
        applyGroups(family, papers, groups);
    }

    /**
     * Asks the model how a set of papers divides, and keeps only what it is
     * allowed to say.
     *
     * @param label what these papers have in common already, for the prompt
     * @return the proposed sub-branches, each holding at least one paper;
     *     empty whenever anything at all went wrong
     */
    private List<StrategyNode> proposeGroups(String label, List<PaperAnalysis> papers,
            ProjectProfile profile) {
        if (papers.size() < MIN_PAPERS_FOR_SUBBRANCHES) {
            // two papers cannot support a division that is not invented
            return List.of();
        }

        StringBuilder listing = new StringBuilder();
        for (int i = 0; i < papers.size(); i++) {
            PaperAnalysis p = papers.get(i);
            listing.append(i + 1).append(". ").append(p.getPaper().getTitle())
                    .append(" - ").append(p.getMethod()).append('\n');
        }

        try {
            String reply = tools.callLLM("""
                    These papers were all placed in the family "%s" of a solution
                    landscape. The researcher's question is: %s

                    %s
                    Divide them into 2 to 5 sub-approaches describing how they differ
                    from each other WITHIN this family. Name each one the way this
                    field would name it. Return JSON only:
                    {"groups":[{"name":"short name","idea":"one line","papers":[1,2]}]}
                    Use only the numbers above, each at most once. If these papers do
                    not divide meaningfully, return {"groups":[]}.
                    """.formatted(label, profile.getResearchQuestion(), listing));
            return readGroups(reply, papers);
        } catch (LLMUnavailableException | RuntimeException e) {
            // UC12 6a: the caller keeps whatever tree it already had
            return List.of();
        }
    }

    /** Hangs the proposed groups off {@code parent}, keeping every paper. */
    private void applyGroups(StrategyNode parent, List<PaperAnalysis> papers,
            List<StrategyNode> groups) {
        List<PaperAnalysis> placed = new ArrayList<>();
        for (StrategyNode group : groups) {
            placed.addAll(group.getPapers());
            parent.add(group);
        }
        if (StrategyTaxonomy.UNCLASSIFIED.equals(parent.getName())) {
            // "Unclassified" was a statement about the built-in families, and
            // it stopped being true the moment this node acquired named
            // sub-branches: the papers are classified, just not by that
            // vocabulary. UC12 5a asks that such papers are not forced into a
            // family they do not belong to, which this still honours.
            parent.setName("Outside the built-in families");
        }
        // whatever the model did not mention stays on the family itself rather
        // than being dropped: losing a paper the researcher analysed is worse
        // than an untidy branch
        List<PaperAnalysis> leftover = new ArrayList<>();
        for (PaperAnalysis paper : papers) {
            if (!placed.contains(paper)) {
                leftover.add(paper);
            }
        }
        parent.setPapers(leftover);
    }

    /**
     * Turns the model's reply into sub-branches, keeping only what it is
     * allowed to say.
     *
     * @param papers the family's papers, indexed from 1 in the prompt
     * @return the proposed sub-branches, each holding at least one paper
     */
    private List<StrategyNode> readGroups(String reply, List<PaperAnalysis> papers) {
        List<StrategyNode> groups = new ArrayList<>();
        Matcher json = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(reply);
        if (!json.find()) {
            return groups;
        }

        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(json.group());
        } catch (Exception e) {
            return groups;
        }

        Set<Integer> taken = new LinkedHashSet<>();
        for (JsonNode group : parsed.path("groups")) {
            String name = group.path("name").asText("").strip();
            if (name.isBlank()) {
                continue;
            }
            StrategyNode node = new StrategyNode(name);
            node.setMainIdea(group.path("idea").asText("").strip());

            for (JsonNode number : group.path("papers")) {
                int index = number.asInt(0);
                // a number outside the listing, or one already used, is dropped
                // rather than clamped: a wrong paper in a named group reads as
                // a finding, and there is no way to tell it from a right one
                if (index >= 1 && index <= papers.size() && taken.add(index)) {
                    node.addPaper(papers.get(index - 1));
                }
            }
            if (node.paperCount() > 0) {
                groups.add(node);
            }
        }
        return groups;
    }

    /**
     * One line describing a branch, recursing into it when it has none of its
     * own.
     *
     * <p>Works at any depth without testing whether the node is a leaf, which
     * is the point of the Composite.
     */
    public String summarizeBranch(StrategyNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        StringBuilder b = new StringBuilder(node.getName());
        b.append(" - ").append(node.paperCount()).append(" paper(s)");
        if (node.isOwnApproach()) {
            b.append(", your approach");
        }
        if (!node.isMainIdeaMissing()) {
            b.append("\n   ").append(node.getMainIdea());
        }
        return b.toString();
    }

    /**
     * @param paperCount how many papers have been analysed
     * @return true when there are too few for branch summaries, so the caller
     *     can say so rather than leave the gap unexplained (UC12 3a)
     */
    public static boolean tooFewPapersForBranchSummaries(int paperCount) {
        return paperCount < MIN_PAPERS_FOR_SUBBRANCHES;
    }

    /** @return the taxonomy, whose seeded roots define the top level */
    public StrategyTaxonomy getTaxonomy() {
        return taxonomy;
    }

    /** @return the mismatch list, for a caller that wants it without the prose */
    public List<String> mismatchesFor(PaperAnalysis analysis, ProjectProfile profile) {
        return checker.findMismatches(analysis, profile);
    }
}
