package lt.mipt.notes.data;

import android.content.Context;
import android.content.SharedPreferences;

import lt.mipt.notes.model.StorageType;

/**
 * Persists the user's selected active storage backend across app restarts.
 */
public final class StoragePreferences {

    public static final String PREFS_NAME = "notes_storage_prefs";
    private static final String KEY_ACTIVE_STORAGE = "active_storage";

    private final SharedPreferences preferences;

    public StoragePreferences(Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public StorageType getActiveStorage() {
        String value = preferences.getString(KEY_ACTIVE_STORAGE, StorageType.SQLITE.getPrefValue());
        return StorageType.fromPrefValue(value);
    }

    public void setActiveStorage(StorageType storageType) {
        preferences.edit().putString(KEY_ACTIVE_STORAGE, storageType.getPrefValue()).apply();
    }

    public void clear() {
        preferences.edit().clear().commit();
    }
}
