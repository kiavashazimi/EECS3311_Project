package ca.yorku.eecs3311.consultant.controller;

import java.util.List;

/**
 * Thrown when input fails the facade's validation and nothing was stored.
 *
 * <p>This is the {@code ValidationError(field)} of sequence diagram SD01. It
 * carries the field <em>names</em>, not just a message, because both the GUI
 * and the CLI have to say which field is at fault — the GUI marks that field
 * inline, the CLI names it in its error.
 */
public class ValidationException extends Exception {

    private static final long serialVersionUID = 1L;

    private final List<String> fields;

    public ValidationException(List<String> fields) {
        super("missing required field(s): " + String.join(", ", fields));
        this.fields = List.copyOf(fields);
    }

    /** @return the names of the fields that failed, never empty */
    public List<String> getFields() {
        return fields;
    }
}
