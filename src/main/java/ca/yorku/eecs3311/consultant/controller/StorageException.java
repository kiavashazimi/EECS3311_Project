package ca.yorku.eecs3311.consultant.controller;

/**
 * Thrown when validation passed but the write failed — the
 * {@code StorageError} branch of SD01, where the error is shown and the
 * previous profile is left intact.
 */
public class StorageException extends Exception {

    private static final long serialVersionUID = 1L;

    public StorageException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
