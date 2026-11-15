package ca.yorku.eecs3311.consultant.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyTaxonomyTest {

    private final StrategyTaxonomy taxonomy = new StrategyTaxonomy();

    // --- C31: the families, on one axis ------------------------------------

    @Test
    void everyCanonicalTagBelongsToAFamily() {
        // A tag added to the vocabulary but not to a family would silently land
        // every paper carrying it under Unclassified, which reads as "this
        // paper fits nothing" rather than "we forgot to file the tag".
        List<String> orphans = taxonomy.getKnownStrategies().stream()
                .filter(tag -> StrategyTaxonomy.UNCLASSIFIED.equals(taxonomy.familyOf(tag)))
                .toList();

        assertTrue(orphans.isEmpty(), "tags in no family: " + orphans);
    }

    @Test
    void theFamiliesSitOnOneAxis() {
        // Before C31 the six families mixed pipeline stage, model type and
        // objective, so a paper could belong to several at once. The question
        // each family answers now is the same one: what does the paper change?
        assertEquals("Data", taxonomy.familyOf("synthetic data generation"));
        assertEquals("Training objective", taxonomy.familyOf("fairness constraint"));
        assertEquals("Training procedure", taxonomy.familyOf("transfer learning"));
        assertEquals("Representation and architecture",
                taxonomy.familyOf("transformer architecture"));
        assertEquals("Inference and deployment", taxonomy.familyOf("post-hoc calibration"));
        assertEquals("Generative modelling", taxonomy.familyOf("diffusion model"));
    }

    @Test
    void theThreeMisfiledTagsAreWhereTheyBelong() {
        // reinforcement learning sat under Model-level approaches, which is how
        // a concept-mapping paper tagged by term collision ended up filed as an
        // architecture paper.
        assertEquals("Training procedure", taxonomy.familyOf("reinforcement learning"));
        assertEquals("Training objective", taxonomy.familyOf("regularization"));
        assertEquals("Inference and deployment", taxonomy.familyOf("prompt engineering"));
    }

    @Test
    void papersThatMeasureRatherThanInterveneHaveAFamily() {
        // The gap that sent a live fairness corpus through the regrouping path:
        // auditing, benchmarking and metric design had nowhere to go.
        assertEquals("Problem framing and measurement", taxonomy.familyOf("bias auditing"));
        assertEquals("Problem framing and measurement",
                taxonomy.familyOf("benchmark construction"));
        assertTrue(taxonomy.match("we run a fairness audit across race and sex subgroups")
                .contains("bias auditing"));
    }

    @Test
    void newVocabularyIsPhrasesRatherThanSingleWords() {
        // The taxonomy's worst failure is a term collision - "reinforcement
        // learning" on a pedagogy paper. A one-word tag collides far more
        // readily than a phrase, so vocabulary added in C31 is phrases and
        // acronyms only.
        //
        // Four single-word tags predate the rule. They stay because each is
        // technical enough that an unrelated abstract will not contain it;
        // unlike "curriculum" or "active", none has an everyday sense.
        List<String> grandfathered =
                List.of("resampling", "reweighting", "ensembling", "regularization");

        List<String> risky = taxonomy.getKnownStrategies().stream()
                .filter(tag -> !tag.contains(" ") && !tag.contains("-"))
                .filter(tag -> !grandfathered.contains(tag))
                .toList();

        assertTrue(risky.isEmpty(), "single-word tags collide too easily: " + risky);
    }

    @Test
    void findsAStrategyNamedInFull() {
        assertTrue(taxonomy.match("we use data augmentation to balance the classes")
                .contains("data augmentation"));
    }

    @Test
    void findsAStrategyNamedByItsAcronym() {
        assertTrue(taxonomy.match("a GAN generates the images")
                .contains("generative adversarial network"));
    }

    @Test
    void doesNotMatchAnAcronymInsideAnotherWord() {
        // substring matching would tag "organ segmentation" as a GAN
        assertFalse(taxonomy.match("organ segmentation of the lungs")
                .contains("generative adversarial network"));
    }

    @Test
    void findsSeveralStrategiesInOneDescription() {
        List<String> tags = taxonomy.match(
                "a latent diffusion model produces synthetic data, then a DenseNet "
                        + "classifier is fine-tuned with resampling");

        assertTrue(tags.contains("diffusion model"), tags.toString());
        assertTrue(tags.contains("synthetic data generation"), tags.toString());
        assertTrue(tags.contains("convolutional architecture"), tags.toString());
        assertTrue(tags.contains("fine-tuning"), tags.toString());
        assertTrue(tags.contains("resampling"), tags.toString());
    }

    @Test
    void returnsNoDuplicatesWhenAStrategyIsMentionedTwice() {
        List<String> tags = taxonomy.match("diffusion, and more diffusion, denoising diffusion");
        assertEquals(1, tags.stream().filter(t -> t.equals("diffusion model")).count());
    }

    @Test
    void anUnknownMethodMatchesNothingRatherThanGuessing() {
        assertTrue(taxonomy.match("we invented something nobody has named yet").isEmpty());
    }

    @Test
    void aMissingMethodIsNotAnError() {
        // a failed extraction leaves the method unknown; that is normal
        assertTrue(taxonomy.match(null).isEmpty());
        assertTrue(taxonomy.match("   ").isEmpty());
    }

    // --- recognising a tag ---------------------------------------------------

    @Test
    void recognisesItsOwnTagsAndTheirVariants() {
        assertTrue(taxonomy.isKnown("diffusion model"));
        assertTrue(taxonomy.isKnown("GAN"));
        assertTrue(taxonomy.isKnown("  Diffusion  "), "case and padding must not matter");
    }

    @Test
    void doesNotRecogniseAnInventedTag() {
        // this is what makes "uncertain" meaningful rather than decorative
        assertFalse(taxonomy.isKnown("quantum fairness alignment"));
        assertFalse(taxonomy.isKnown(null));
        assertFalse(taxonomy.isKnown(""));
    }

    @Test
    void resolvesVariantsToOneCanonicalTag() {
        assertEquals("generative adversarial network", taxonomy.canonical("GANs"));
        assertEquals("generative adversarial network", taxonomy.canonical("gan"));
        assertEquals("convolutional architecture", taxonomy.canonical("ResNet"));
    }

    @Test
    void leavesAnUnknownTagAloneApartFromTidyingIt() {
        assertEquals("quantum fairness", taxonomy.canonical("  Quantum Fairness  "));
    }

    // --- extending it --------------------------------------------------------

    @Test
    void canBeTaughtANewStrategy() {
        taxonomy.addKnownStrategy("subgroup rebalancing", "subgroup balancing");

        assertTrue(taxonomy.isKnown("subgroup rebalancing"));
        assertTrue(taxonomy.match("we apply subgroup balancing before training")
                .contains("subgroup rebalancing"));
    }

    @Test
    void teachingItDoesNotAffectAnotherInstance() {
        taxonomy.addKnownStrategy("one-off", "oneoff");
        assertFalse(new StrategyTaxonomy().isKnown("one-off"),
                "the seeded vocabulary must not be shared mutable state");
    }

    @Test
    void rejectsABlankStrategy() {
        assertThrows(IllegalArgumentException.class, () -> taxonomy.addKnownStrategy("  "));
    }

    @Test
    void exposesItsVocabularyReadOnly() {
        assertFalse(taxonomy.getKnownStrategies().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> taxonomy.getKnownStrategies().add("forged"));
    }

    // --- regressions from real abstracts ------------------------------------

    @Test
    void matchesAPluralisedStrategyName() {
        // a real abstract said "learned augmentations"; requiring the exact
        // singular classified that paper as "none recognised"
        assertTrue(taxonomy.match("these are used as learned augmentations")
                .contains("data augmentation"));
    }

    @Test
    void classifiesTheAbstractThatFirstFailed() {
        // verbatim from arXiv 2304.09218, which came back "none recognised"
        String method = "They train generative models on abundant unlabelled data, conditioned "
                + "on appropriate labels, to produce realistic synthetic examples that steer "
                + "the distribution toward specific conditions and subgroups. These synthetic "
                + "samples complement real training data as learned augmentations, which are "
                + "compared with heuristic augmentations.";

        List<String> tags = taxonomy.match(method);

        assertTrue(tags.contains("synthetic data generation"), tags.toString());
        assertTrue(tags.contains("data augmentation"), tags.toString());
        assertTrue(tags.contains("generative model"), tags.toString());
        assertFalse(tags.isEmpty());
    }

    @Test
    void thePluralRuleStillRejectsAnAcronymInsideAWord() {
        assertFalse(taxonomy.match("organs and organisms").contains(
                "generative adversarial network"));
    }
}
