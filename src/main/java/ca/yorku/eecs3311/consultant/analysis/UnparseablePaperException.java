package ca.yorku.eecs3311.consultant.analysis;

/**
 * Thrown when no analysable text can be obtained for a paper — UC04 flow 3a.
 *
 * <p>Checked, so a caller cannot forget that a paper with no abstract has to be
 * flagged rather than analysed. Asking the model to extract a method from a
 * title alone would produce a confident fabrication.
 */
public class UnparseablePaperException extends Exception {

    private static final long serialVersionUID = 1L;

    public UnparseablePaperException(String reason) {
        super(reason);
    }
}
