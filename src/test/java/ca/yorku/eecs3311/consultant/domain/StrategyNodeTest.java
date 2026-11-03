package ca.yorku.eecs3311.consultant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The Composite. The operations that matter are the ones that work the same on
 * a leaf and on a branch, at any depth, with no type test.
 */
class StrategyNodeTest {

    private static PaperAnalysis paper(String id) {
        return new PaperAnalysis(new PaperRecord(id, "Paper " + id, "arXiv", "2026-01-01"));
    }

    @Test
    void aFreshNodeIsALeaf() {
        StrategyNode node = new StrategyNode("Data-level approaches");
        assertTrue(node.isLeaf());
        assertEquals(0, node.paperCount());
    }

    @Test
    void addingAChildMakesItABranch() {
        StrategyNode parent = new StrategyNode("parent").add(new StrategyNode("child"));
        assertFalse(parent.isLeaf());
        assertEquals(1, parent.getChildren().size());
    }

    @Test
    void paperCountRecursesToAnyDepth() {
        // the whole point: one call, no knowledge of the shape
        StrategyNode root = new StrategyNode("root");
        StrategyNode mid = new StrategyNode("mid");
        StrategyNode deep = new StrategyNode("deep");

        root.addPaper(paper("1"));
        mid.addPaper(paper("2"));
        deep.addPaper(paper("3"));
        deep.addPaper(paper("4"));
        mid.add(deep);
        root.add(mid);

        assertEquals(4, root.paperCount());
        assertEquals(3, mid.paperCount());
        assertEquals(2, deep.paperCount());
    }

    @Test
    void theSamePaperIsNotCountedTwiceInOneNode() {
        StrategyNode node = new StrategyNode("n");
        PaperAnalysis once = paper("1");
        node.addPaper(once);
        node.addPaper(once);
        assertEquals(1, node.paperCount());
    }

    @Test
    void aNodeCannotContainItself() {
        // without this, paperCount() would recurse until the stack ran out
        StrategyNode node = new StrategyNode("n");
        assertThrows(IllegalArgumentException.class, () -> node.add(node));
    }

    @Test
    void aCycleThroughAChildIsRefused() {
        StrategyNode parent = new StrategyNode("parent");
        StrategyNode child = new StrategyNode("child");
        parent.add(child);
        assertThrows(IllegalArgumentException.class, () -> child.add(parent));
    }

    @Test
    void removingAChildDropsItsPapersFromTheCount() {
        StrategyNode root = new StrategyNode("root");
        StrategyNode child = new StrategyNode("child");
        child.addPaper(paper("1"));
        root.add(child);

        assertEquals(1, root.paperCount());
        assertTrue(root.remove(child));
        assertEquals(0, root.paperCount());
    }

    @Test
    void flattenReturnsTheWholeSubtreeDepthFirst() {
        StrategyNode root = new StrategyNode("root");
        StrategyNode a = new StrategyNode("a");
        a.add(new StrategyNode("a1"));
        root.add(a).add(new StrategyNode("b"));

        assertEquals(4, root.flatten().size());
        assertEquals("root", root.flatten().get(0).getName());
    }

    @Test
    void findLocatesANodeAtAnyDepthIgnoringCase() {
        StrategyNode root = new StrategyNode("root");
        StrategyNode deep = new StrategyNode("Generative approaches");
        root.add(new StrategyNode("mid").add(deep));

        assertEquals(deep, root.find("generative APPROACHES"));
        assertNull(root.find("nothing here"));
    }

    @Test
    void pruningRemovesBranchesWithNoPapersAtAnyDepth() {
        StrategyNode root = new StrategyNode("root");
        StrategyNode kept = new StrategyNode("kept");
        kept.addPaper(paper("1"));
        StrategyNode emptyBranch = new StrategyNode("empty");
        emptyBranch.add(new StrategyNode("also empty"));
        root.add(kept).add(emptyBranch);

        root.pruneEmptyBranches();

        assertEquals(1, root.getChildren().size());
        assertEquals("kept", root.getChildren().get(0).getName());
    }

    @Test
    void aBranchWithPapersOnlyDeepInItIsKept() {
        StrategyNode root = new StrategyNode("root");
        StrategyNode mid = new StrategyNode("mid");
        StrategyNode deep = new StrategyNode("deep");
        deep.addPaper(paper("1"));
        mid.add(deep);
        root.add(mid);

        root.pruneEmptyBranches();

        assertEquals(1, root.getChildren().size(), "an outer branch is not empty");
        assertEquals(1, root.getChildren().get(0).getChildren().size());
    }

    @Test
    void theChildrenAndPapersAreReadOnlyFromOutside() {
        StrategyNode node = new StrategyNode("n");
        node.addPaper(paper("1"));
        node.add(new StrategyNode("c"));

        assertThrows(UnsupportedOperationException.class,
                () -> node.getChildren().add(new StrategyNode("forged")));
        assertThrows(UnsupportedOperationException.class,
                () -> node.getPapers().add(paper("2")));
    }

    @Test
    void aMissingMainIdeaIsReportable() {
        StrategyNode node = new StrategyNode("n");
        assertTrue(node.isMainIdeaMissing());
        node.setMainIdea("what these approaches do");
        assertFalse(node.isMainIdeaMissing());
    }

    @Test
    void rejectsABlankName() {
        assertThrows(IllegalArgumentException.class, () -> new StrategyNode("  "));
        assertThrows(IllegalArgumentException.class, () -> new StrategyNode(null));
    }

    @Test
    void rejectsANullChildOrPaper() {
        StrategyNode node = new StrategyNode("n");
        assertThrows(IllegalArgumentException.class, () -> node.add(null));
        assertThrows(IllegalArgumentException.class, () -> node.addPaper(null));
    }
}
