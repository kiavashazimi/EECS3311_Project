package ca.yorku.eecs3311.consultant.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ca.yorku.eecs3311.consultant.domain.StrategyNode;
import java.util.regex.Pattern;

/**
 * The vocabulary of research strategies a paper's method can be tagged with.
 *
 * <p>Deliberately deterministic. F05 is a hybrid feature: the language model
 * proposes tags, and this class decides which of them the system actually
 * recognises. That split is what makes "uncertain" meaningful — a tag no
 * taxonomy knows is a guess, and UC04 flow 5a requires it be marked as one
 * rather than asserted.
 *
 * <p>The seeded list is deliberately domain-general. A taxonomy tuned to one
 * research area would silently fail on another, and the whole point of the
 * project is that it adapts to whatever the researcher is working on.
 */
public class StrategyTaxonomy {

    /**
     * Canonical tag to the acronyms and surface forms that mean the same thing.
     *
     * <p>The inflected forms are listed explicitly — "fine-tuned" as well as
     * "fine-tuning" — because authors write the past participle at least as
     * often as the noun, and a first version that listed only the nouns missed
     * "the classifier is fine-tuned". Stemming would generalise better but
     * would also collide: "resampled" and "resampling" stem together, which is
     * wanted, while "calibration" and "calibre" do not.
     */
    private static final Map<String, List<String>> SEED = seed();

    private final Map<String, List<String>> known = new LinkedHashMap<>(SEED);

    private static Map<String, List<String>> seed() {
        Map<String, List<String>> s = new LinkedHashMap<>();
        s.put("data augmentation", List.of("augmentation", "augmented", "augmenting",
                "augmented training data"));
        s.put("synthetic data generation", List.of("synthetic data", "synthetic image",
                "synthetic example", "synthetic sample", "synthetically generated"));
        s.put("generative model", List.of("generative modelling", "generative modeling"));
        s.put("conditional generation", List.of("class-conditional", "label-conditioned",
                "classifier-free guidance", "conditioned on"));
        s.put("resampling", List.of("resampled", "oversampling", "undersampling",
                "class balancing", "rebalancing", "rebalanced"));
        s.put("reweighting", List.of("reweighted", "sample weighting", "importance weighting"));
        s.put("adversarial training", List.of("adversarial debiasing", "adversarial learning"));
        s.put("domain adaptation", List.of("domain shift", "domain generalization"));
        s.put("transfer learning", List.of("pretraining", "pre-training", "pretrained",
                "pre-trained"));
        s.put("fine-tuning", List.of("finetuning", "fine-tuned", "finetuned", "fine-tune",
                "parameter-efficient fine-tuning", "lora"));
        s.put("contrastive learning", List.of("contrastive"));
        s.put("self-supervised pretraining", List.of("self-supervised", "ssl"));
        s.put("knowledge distillation", List.of("distillation", "distilled"));
        s.put("ensembling", List.of("ensemble", "ensembled", "model averaging"));
        s.put("regularization", List.of("regularisation", "regularized", "regularised",
                "weight decay", "dropout"));
        s.put("causal intervention", List.of("causal", "do-calculus"));
        s.put("counterfactual generation", List.of("counterfactual", "counterfactuals"));
        s.put("fairness constraint", List.of("equalized odds", "demographic parity",
                "fairness regularizer"));
        s.put("post-hoc calibration", List.of("calibration", "calibrated",
                "threshold adjustment"));
        s.put("representation disentanglement", List.of("disentangled", "disentanglement"));
        s.put("retrieval augmentation", List.of("retrieval-augmented", "rag"));
        s.put("diffusion model", List.of("diffusion", "latent diffusion", "denoising diffusion"));
        s.put("generative adversarial network", List.of("gan", "gans"));
        s.put("variational autoencoder", List.of("vae", "autoencoder"));
        s.put("transformer architecture", List.of("transformer", "vision transformer", "vit"));
        s.put("convolutional architecture", List.of("convolutional", "cnn", "resnet",
                "densenet"));
        s.put("reinforcement learning", List.of("rl", "policy gradient"));
        s.put("active learning", List.of("query selection"));
        s.put("curriculum learning", List.of("curriculum"));
        s.put("multi-task learning", List.of("multi-task", "multitask"));
        s.put("federated learning", List.of("federated"));
        s.put("prompt engineering", List.of("prompting", "in-context learning"));
        return s;
    }

    /**
     * Finds every known strategy mentioned in a method description.
     *
     * @param method free text describing what the paper did; null or blank
     *     yields an empty list rather than an error, because an unknown method
     *     is a normal outcome of a failed extraction
     * @return the canonical tags found, in taxonomy order, without duplicates
     */
    public List<String> match(String method) {
        if (method == null || method.isBlank()) {
            return List.of();
        }
        String text = method.toLowerCase();

        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : known.entrySet()) {
            if (mentions(text, entry.getKey())) {
                found.add(entry.getKey());
                continue;
            }
            for (String variant : entry.getValue()) {
                if (mentions(text, variant)) {
                    found.add(entry.getKey());
                    break;
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Whole-word matching with an optional plural, not substring matching.
     *
     * <p>Two things this gets right that a naive version does not. Substring
     * matching would tag "organ segmentation" as a generative adversarial
     * network, because "organ" contains "gan". And requiring the exact singular
     * missed a real paper whose abstract said "learned augmentations" and
     * "synthetic examples" — authors pluralise, so one trailing "s" is allowed.
     */
    private static boolean mentions(String text, String term) {
        return Pattern.compile("\\b" + Pattern.quote(term) + "s?\\b").matcher(text).find();
    }

    /** @return true when this tag is part of the taxonomy, case-insensitively */
    public boolean isKnown(String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        String candidate = tag.strip().toLowerCase();
        if (known.containsKey(candidate)) {
            return true;
        }
        return known.values().stream().anyMatch(variants -> variants.contains(candidate));
    }

    /**
     * Resolves a tag to its canonical form, so "GAN" and "gans" become one tag.
     *
     * @return the canonical tag, or the input stripped and lowercased when it is
     *     not known
     */
    public String canonical(String tag) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        String candidate = tag.strip().toLowerCase();
        if (known.containsKey(candidate)) {
            return candidate;
        }
        for (Map.Entry<String, List<String>> entry : known.entrySet()) {
            if (entry.getValue().contains(candidate)) {
                return entry.getKey();
            }
        }
        return candidate;
    }

    /** Teaches the taxonomy a tag it did not know. */
    public void addKnownStrategy(String tag, String... variants) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("tag must not be null or blank");
        }
        known.put(tag.strip().toLowerCase(), List.of(variants));
    }

    /** @return every canonical tag, read-only */
    public List<String> getKnownStrategies() {
        return List.copyOf(new ArrayList<>(known.keySet()));
    }

    // --- the seeded top level of the solution landscape ---------------------

    /**
     * The fixed families of approach, and which canonical tags belong to each.
     *
     * <p>Seeded rather than derived. UC12 flow 3a requires a usable tree from
     * one or two analysed papers, and sub-branches invented from two papers
     * would be noise presented as structure. The top level is therefore stable
     * and the model only proposes what sits <em>beneath</em> it.
     */
    private static final Map<String, List<String>> FAMILIES = families();

    private static Map<String, List<String>> families() {
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("Data-level approaches", List.of("data augmentation",
                "synthetic data generation", "resampling", "reweighting"));
        f.put("Generative approaches", List.of("generative model", "diffusion model",
                "generative adversarial network", "variational autoencoder",
                "conditional generation", "counterfactual generation"));
        f.put("Training-level approaches", List.of("adversarial training", "curriculum learning",
                "multi-task learning", "transfer learning", "fine-tuning",
                "self-supervised pretraining", "contrastive learning", "active learning",
                "federated learning", "prompt engineering", "retrieval augmentation"));
        f.put("Model-level approaches", List.of("transformer architecture",
                "convolutional architecture", "ensembling", "regularization",
                "knowledge distillation", "reinforcement learning"));
        f.put("Constraint and representation approaches", List.of("fairness constraint",
                "representation disentanglement", "causal intervention", "domain adaptation"));
        f.put("Post-hoc approaches", List.of("post-hoc calibration"));
        return f;
    }

    /** The node every paper that fits no family is placed under (UC12 5a). */
    public static final String UNCLASSIFIED = "Unclassified";

    private StrategyNode root;

    /**
     * Builds a fresh top level: a root with one child per family, plus
     * {@code Unclassified}.
     *
     * @return the new root, which also becomes {@link #getRoot()}
     */
    public StrategyNode seedRoots() {
        StrategyNode seeded = new StrategyNode("Solution landscape");
        for (String family : FAMILIES.keySet()) {
            seeded.add(new StrategyNode(family));
        }
        // explicit, so a paper that fits nothing is never forced into the
        // nearest branch just to avoid an empty slot
        seeded.add(new StrategyNode(UNCLASSIFIED));
        this.root = seeded;
        return seeded;
    }

    /** @return the current root, seeding one if none exists yet */
    public StrategyNode getRoot() {
        return root == null ? seedRoots() : root;
    }

    /**
     * @param tag a canonical strategy tag
     * @return the family it belongs to, or {@link #UNCLASSIFIED}
     */
    public String familyOf(String tag) {
        String canonical = canonical(tag);
        for (Map.Entry<String, List<String>> family : FAMILIES.entrySet()) {
            if (family.getValue().contains(canonical)) {
                return family.getKey();
            }
        }
        return UNCLASSIFIED;
    }

    /** @return the family names, in seeded order, without Unclassified */
    public List<String> getFamilies() {
        return List.copyOf(new ArrayList<>(FAMILIES.keySet()));
    }
}
