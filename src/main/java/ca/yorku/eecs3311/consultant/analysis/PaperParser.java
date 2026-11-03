package ca.yorku.eecs3311.consultant.analysis;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;

/**
 * Produces the text that analysis reads from a paper.
 *
 * <p>Currently the abstract, which is what the literature API already returned.
 * UC04 flow 3a sanctions this: when the full text cannot be obtained, analysis
 * "falls back to the abstract if one is available, otherwise it stops with a
 * message". Full-text PDF extraction is recorded as a deviation and a backlog
 * item rather than silently assumed — see the Stage 2 changes log.
 */
public class PaperParser {

    /**
     * @param paper the paper to read
     * @return the text to analyse
     * @throws UnparseablePaperException when there is no text at all, so the
     *     caller flags the paper instead of asking the model to invent an
     *     analysis from a title
     */
    public String parse(PaperRecord paper) throws UnparseablePaperException {
        if (paper == null) {
            throw new IllegalArgumentException("paper must not be null");
        }
        String abstractText = paper.getSummary();
        if (abstractText == null || abstractText.isBlank()) {
            throw new UnparseablePaperException(
                    "no abstract available for \"" + paper.getTitle() + "\"");
        }
        return abstractText.strip();
    }
}
