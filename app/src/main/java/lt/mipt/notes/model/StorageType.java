package lt.mipt.notes.model;

/**
 * Supported local persistence backends.
 */
public enum StorageType {
    SQLITE("sqlite"),
    JSON("json");

    private final String prefValue;

    StorageType(String prefValue) {
        this.prefValue = prefValue;
    }

    public String getPrefValue() {
        return prefValue;
    }

    public static StorageType fromPrefValue(String value) {
        if (JSON.prefValue.equals(value)) {
            return JSON;
        }
        return SQLITE;
    }
}
