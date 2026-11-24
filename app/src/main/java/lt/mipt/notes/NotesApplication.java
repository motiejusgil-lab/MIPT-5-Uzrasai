package lt.mipt.notes;

import android.app.Application;

import java.io.File;

import lt.mipt.notes.data.JsonNoteStorage;
import lt.mipt.notes.data.NotesDbHelper;
import lt.mipt.notes.data.NotesRepository;
import lt.mipt.notes.data.SqliteNoteStorage;
import lt.mipt.notes.data.StoragePreferences;

/**
 * Application entry point owning the shared repository instance.
 */
public class NotesApplication extends Application {

    private NotesRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        repository = createRepository();
    }

    public synchronized NotesRepository getRepository() {
        if (repository == null) {
            repository = createRepository();
        }
        return repository;
    }

    /**
     * Resets local database, JSON file, and preferences cleanly for isolated instrumentation tests.
     */
    public synchronized void resetForTests() {
        if (repository != null) {
            repository.shutdown();
            repository = null;
        }
        deleteDatabase(NotesDbHelper.DATABASE_NAME);
        File jsonFile = new File(getFilesDir(), JsonNoteStorage.DEFAULT_FILE_NAME);
        if (jsonFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            jsonFile.delete();
        }
        new StoragePreferences(this).clear();
        repository = createRepository();
    }

    private NotesRepository createRepository() {
        NotesDbHelper dbHelper = new NotesDbHelper(this);
        SqliteNoteStorage sqliteStorage = new SqliteNoteStorage(dbHelper);
        JsonNoteStorage jsonStorage = new JsonNoteStorage(this);
        StoragePreferences preferences = new StoragePreferences(this);
        return new NotesRepository(sqliteStorage, jsonStorage, preferences);
    }
}
