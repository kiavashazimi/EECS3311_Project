package ca.yorku.eecs3311.consultant.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyTaxonomyTest {

    private final StrategyTaxonomy taxonomy = new StrategyTaxonomy();

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
