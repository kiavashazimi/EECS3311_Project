package ca.yorku.eecs3311.consultant.agent;

import ca.yorku.eecs3311.consultant.domain.PaperRecord;

/**
 * Notified whenever discovery finds a paper — the OBSERVER of section 4 of the
 * Stage 1 report.
 *
 * <p>{@link PaperDiscoveryAgent} is the subject and knows only this interface.
 * The GUI feed redraws, the digest builder accumulates, and a future
 * email-alert feature could be added without the agent changing at all.
 */
public interface PaperFeedListener {

    /**
     * Called once per discovered paper, in ranked order.
     *
     * <p>Implementations must not throw to signal trouble: the subject
     * broadcasts to every listener and one failing listener must not stop the
     * others from being told. A listener that does throw is isolated and
     * skipped.
     *
     * @param paper the discovered paper, never null
     */
    void onNewPaper(PaperRecord paper);
}
