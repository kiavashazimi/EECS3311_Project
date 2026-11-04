package ca.yorku.eecs3311.consultant.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An answer that is traceable back to the records it was built from.
 *
 * <p>Carries its sources rather than just its text, because an answer a
 * researcher cannot check is not usable: "three of your papers condition on
 * demographics" is only actionable if the three can be named. F08 and F11 both
 * return this (SD06).
 *
 * <p>{@link #isGrounded()} is the field that matters. False means nothing
 * relevant was retrieved, so there is no answer — not that the answer is no.
 * For F08 that is the whole point: two searches cannot prove absence, and
 * reporting "no one has tried this" from an empty result could send a
 * researcher into months of rediscovery.
 */
public class Answer {

    private String question = "";
    private String text = "";
    private final List<String> sources = new ArrayList<>();
    private boolean grounded;
    private String note = "";

    public Answer() {
    }

    public Answer(String question) {
        this.question = question == null ? "" : question.strip();
    }

    /**
     * @return true when the answer rests on retrieved records. False means
     *     nothing relevant was stored, and {@link #getText()} says so rather
     *     than answering from the model's own knowledge
     */
    public boolean isGrounded() {
        return grounded;
    }

    public void setGrounded(boolean grounded) {
        this.grounded = grounded;
    }

    /** @return true when the model could not be reached, so only sources are available */
    public boolean isTextMissing() {
        return text.isBlank();
    }

    public void addSource(String source) {
        if (source != null && !source.isBlank() && !sources.contains(source.strip())) {
            sources.add(source.strip());
        }
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question == null ? "" : question.strip();
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text == null ? "" : text.strip();
    }

    /** @return the records this answer rests on, read-only */
    public List<String> getSources() {
        return Collections.unmodifiableList(new ArrayList<>(sources));
    }

    public void setSources(List<String> sources) {
        this.sources.clear();
        if (sources != null) {
            for (String s : sources) {
                addSource(s);
            }
        }
    }

    /**
     * @return what the answer could not cover — an unreachable literature API,
     *     a search that found nothing. Blank when there is nothing to qualify
     */
    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note == null ? "" : note.strip();
    }
}
