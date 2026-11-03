package ca.yorku.eecs3311.consultant.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the solution landscape — COMPONENT, COMPOSITE and LEAF in one type.
 *
 * <p>A node with no children is a leaf; nothing has to ask which kind it is.
 * That matters because the tree's depth is not known in advance: the top level
 * is seeded, and sub-branches are proposed at runtime from whatever papers have
 * been analysed. {@link #paperCount()} and a branch walk recurse to any depth
 * without a single type test.
 */
public class StrategyNode {

    private String name;
    private String mainIdea = "";
    private boolean ownApproach;
    private final List<StrategyNode> children = new ArrayList<>();
    private final List<PaperAnalysis> papers = new ArrayList<>();

    /** For Jackson. */
    public StrategyNode() {
        this.name = "";
    }

    public StrategyNode(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        this.name = name.strip();
    }

    // --- the composite operations -------------------------------------------

    /**
     * @param child the sub-branch to add
     * @throws IllegalArgumentException if the child is null, or is this node —
     *     a cycle would make {@link #paperCount()} recurse forever
     */
    public StrategyNode add(StrategyNode child) {
        if (child == null) {
            throw new IllegalArgumentException("child must not be null");
        }
        if (child == this || child.contains(this)) {
            throw new IllegalArgumentException(
                    "a node cannot contain itself: " + child.getName() + " -> " + name);
        }
        children.add(child);
        return this;
    }

    public boolean remove(StrategyNode child) {
        return children.remove(child);
    }

    /** @return the sub-branches, read-only */
    public List<StrategyNode> getChildren() {
        return List.copyOf(children);
    }

    /** @return true when this node has no sub-branches */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /** @return the papers under this node and every node beneath it */
    public int paperCount() {
        int total = papers.size();
        for (StrategyNode child : children) {
            total += child.paperCount();
        }
        return total;
    }

    /** @return true when {@code node} is this node or anywhere beneath it */
    public boolean contains(StrategyNode node) {
        if (node == this) {
            return true;
        }
        for (StrategyNode child : children) {
            if (child.contains(node)) {
                return true;
            }
        }
        return false;
    }

    /** @return every node in this subtree, this one first, depth first */
    public List<StrategyNode> flatten() {
        List<StrategyNode> all = new ArrayList<>();
        all.add(this);
        for (StrategyNode child : children) {
            all.addAll(child.flatten());
        }
        return all;
    }

    /** @return the node with this name anywhere in the subtree, or null */
    public StrategyNode find(String nodeName) {
        for (StrategyNode node : flatten()) {
            if (node.getName().equalsIgnoreCase(nodeName == null ? "" : nodeName.strip())) {
                return node;
            }
        }
        return null;
    }

    /** Drops every branch holding no papers, so an empty seeded root is hidden. */
    public void pruneEmptyBranches() {
        children.removeIf(child -> child.paperCount() == 0);
        children.forEach(StrategyNode::pruneEmptyBranches);
    }

    // --- papers --------------------------------------------------------------

    public void addPaper(PaperAnalysis paper) {
        if (paper == null) {
            throw new IllegalArgumentException("paper must not be null");
        }
        if (!papers.contains(paper)) {
            papers.add(paper);
        }
    }

    public List<PaperAnalysis> getPapers() {
        return List.copyOf(papers);
    }

    public void setPapers(List<PaperAnalysis> value) {
        papers.clear();
        if (value != null) {
            papers.addAll(value);
        }
    }

    // --- plain state ---------------------------------------------------------

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name.strip();
    }

    /** @return one line on what this family of approaches actually does */
    public String getMainIdea() {
        return mainIdea;
    }

    public void setMainIdea(String mainIdea) {
        this.mainIdea = mainIdea == null ? "" : mainIdea.strip();
    }

    /** @return true when this is where the researcher's own approach sits */
    public boolean isOwnApproach() {
        return ownApproach;
    }

    public void setOwnApproach(boolean ownApproach) {
        this.ownApproach = ownApproach;
    }

    /** @return true when no branch summary could be written (UC12 6a) */
    public boolean isMainIdeaMissing() {
        return mainIdea.isEmpty();
    }

    @Override
    public String toString() {
        return name + " (" + paperCount() + " papers, " + children.size() + " branches)";
    }
}
