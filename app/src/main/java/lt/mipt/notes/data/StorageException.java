package lt.mipt.notes.data;

/**
 * Typed checked exception for local persistence failures so corrupted or unreadable data is never swallowed.
 */
public class StorageException extends Exception {

    public enum ErrorType {
        CORRUPTED_JSON,
        IO_ERROR,
        DATABASE_ERROR,
        NOT_FOUND
    }

    private final ErrorType errorType;

    public StorageException(ErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }

    public StorageException(ErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}
