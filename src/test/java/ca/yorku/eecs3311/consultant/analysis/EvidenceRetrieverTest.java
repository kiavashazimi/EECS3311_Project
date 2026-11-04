package ca.yorku.eecs3311.consultant.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.yorku.eecs3311.consultant.domain.PaperAnalysis;
import ca.yorku.eecs3311.consultant.domain.PaperRecord;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Retrieval is deterministic and runs before the model is involved.
 *
 * <p>This is the half of F08 and F11 that decides what the model is allowed to
 * see. If it returns nothing, the feature must say nothing was found rather
 * than let the model answer from its own knowledge, so "finds nothing" is as
 * important a behaviour here as "finds the right thing".
 */
class EvidenceRetrieverTest {

    private final EvidenceRetriever retriever = new EvidenceRetriever();

    private static PaperAnalysis analysis(String id, String title) {
        return new PaperAnalysis(new PaperRecord(id, title, "arXiv cs.CV", "2025-01-01"));
    }

    private static PaperAnalysis fairDiffusion() {
        PaperAnalysis a = analysis("2412.20374v2", "FairDiffusion");
        a.setMethod("adds a fair Bayesian perturbation to a latent diffusion model");
        a.setDataset("CheXpert");
        a.setMetrics(List.of("AUROC", "subgroup AUROC gap"));
        a.setStrategyTags(List.of("diffusion model", "generative model"));
        return a;
    }

    private static PaperAnalysis contrastive() {
        PaperAnalysis a = analysis("2401.15111v1", "Improving Fairness by Contrastive Learning");
        a.setMethod("supervised contrastive learning to produce fair image embeddings");
        a.setDataset("MIDRC, NIH-CXR");
        a.setStrategyTags(List.of("contrastive learning", "fine-tuning"));
        return a;
    }

    private static PaperAnalysis turbulence() {
        PaperAnalysis a = analysis("2610.35123v1", "Generative AI in Turbulence");
        a.setMethod("benchmarks flow solvers");
        a.setDataset("JHTDB");
        return a;
    }

    @Test
    void aPaperIsFoundThroughItsMethodTextNotJustItsTitle() {
        List<PaperAnalysis> found = retriever.relevantTo(
                "has anyone used a latent diffusion model for this?",
                List.of(fairDiffusion(), turbulence()));

        assertEquals(1, found.size(), found.toString());
        assertEquals("2412.20374v2", found.get(0).getPaper().getId());
    }

    @Test
    void nothingRelevantReturnsEmptyRatherThanTheWholeLibrary() {
        // the whole point: with no evidence, the caller must be able to tell
        List<PaperAnalysis> found = retriever.relevantTo(
                "reinforcement learning for robot locomotion",
                List.of(fairDiffusion(), contrastive()));

        assertTrue(found.isEmpty(), found.toString());
    }

    @Test
    void oneSharedGenericWordIsNotEvidence() {
        // "learning" appears in both "reinforcement learning" and "contrastive
        // learning". A one-hit rule handed the contrastive chest X-ray paper to
        // the model as the retrieved context for a question about robots.
        List<PaperAnalysis> found = retriever.relevantTo(
                "reinforcement learning for robot locomotion", List.of(contrastive()));

        assertTrue(found.isEmpty(), "a single common term must not qualify: " + found);
    }

    @Test
    void aShortQueryStillMatchesOnOneStrongTerm() {
        // the bar scales with the question; it must not make short queries useless
        List<PaperAnalysis> found = retriever.relevantTo(
                "CheXpert", List.of(fairDiffusion(), turbulence()));

        assertEquals(1, found.size(), found.toString());
        assertEquals("2412.20374v2", found.get(0).getPaper().getId());
    }

    @Test
    void morePointsOfOverlapRanksHigher() {
        List<PaperAnalysis> found = retriever.relevantTo(
                "fairness of a diffusion model measured by subgroup AUROC on CheXpert",
                List.of(contrastive(), fairDiffusion()));

        assertFalse(found.isEmpty());
        assertEquals("2412.20374v2", found.get(0).getPaper().getId(),
                "the paper matching more of the query should come first: " + found);
    }

    @Test
    void commonWordsDoNotMakeEverythingRelevant() {
        // without stopword removal, "the" and "that" match every paper, and
        // retrieval would hand the model the entire library as evidence
        List<PaperAnalysis> found = retriever.relevantTo(
                "what about the thing that was in the other one?",
                List.of(fairDiffusion(), contrastive(), turbulence()));

        assertTrue(found.isEmpty(), "a query of only common words matches nothing: " + found);
    }

    @Test
    void aPluralInTheQueryStillMatchesTheSingularInThePaper() {
        List<PaperAnalysis> found = retriever.relevantTo(
                "diffusion models for chest x-rays", List.of(fairDiffusion()));

        assertEquals(1, found.size(), "\"models\" must match \"model\": " + found);
    }

    @Test
    void theNumberOfPapersHandedToTheModelIsCapped() {
        List<PaperAnalysis> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            PaperAnalysis a = analysis("id" + i, "Paper " + i);
            a.setMethod("a latent diffusion model for chest radiographs");
            many.add(a);
        }

        List<PaperAnalysis> found = retriever.relevantTo("latent diffusion model", many);

        assertEquals(EvidenceRetriever.MAX_EVIDENCE, found.size(),
                "a prompt cannot carry the whole library: " + found.size());
    }

    @Test
    void anEmptyLibraryIsNotAnError() {
        assertTrue(retriever.relevantTo("anything", List.of()).isEmpty());
    }

    @Test
    void aBlankQueryIsRejectedRatherThanMatchingEverything() {
        assertThrows(IllegalArgumentException.class,
                () -> retriever.relevantTo("   ", List.of(fairDiffusion())));
        assertThrows(IllegalArgumentException.class,
                () -> retriever.relevantTo(null, List.of(fairDiffusion())));
    }

    @Test
    void theSearchTermsAreVisibleSoAClientCanShowWhatWasSearchedFor() {
        List<String> terms = retriever.termsOf("Has anyone tried conditioning the generator?");

        assertTrue(terms.contains("conditioning"), terms.toString());
        assertTrue(terms.contains("generator"), terms.toString());
        assertFalse(terms.contains("has"), "stopwords are dropped: " + terms);
        assertFalse(terms.contains("anyone"), "stopwords are dropped: " + terms);
    }
}
