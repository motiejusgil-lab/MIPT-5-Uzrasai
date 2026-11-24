package lt.mipt.notes.data;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import lt.mipt.notes.model.Note;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SqliteNoteStorageTest {

    private Context context;
    private NotesDbHelper dbHelper;
    private SqliteNoteStorage storage;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase(NotesDbHelper.DATABASE_NAME);
        dbHelper = new NotesDbHelper(context);
        storage = new SqliteNoteStorage(dbHelper);
    }

    @After
    public void tearDown() {
        storage.close();
        context.deleteDatabase(NotesDbHelper.DATABASE_NAME);
    }

    @Test
    public void crudAndReopenPersistenceWork() throws StorageException {
        Note created = Note.createLive("id-1", "Pirmas", "Turinys ąčęėįšųūž", 1000L);
        storage.upsertNote(created);

        Note updated = created.withUpdatedContent("Pirmas atnaujintas", "Naujas turinys", 2000L);
        storage.upsertNote(updated);
        storage.close();

        NotesDbHelper reopenedHelper = new NotesDbHelper(context);
        SqliteNoteStorage reopenedStorage = new SqliteNoteStorage(reopenedHelper);
        try {
            List<Note> liveNotes = reopenedStorage.getLiveNotes();
            assertEquals(1, liveNotes.size());
            assertEquals("Pirmas atnaujintas", liveNotes.get(0).getTitle());
            assertEquals("Naujas turinys", liveNotes.get(0).getContent());

            Note byId = reopenedStorage.getLiveNoteById("id-1");
            assertNotNull(byId);
            assertEquals(2000L, byId.getUpdatedAt());

            reopenedStorage.markDeleted("id-1", 3000L);
            assertTrue(reopenedStorage.getLiveNotes().isEmpty());
            assertNull(reopenedStorage.getLiveNoteById("id-1"));

            List<Note> allRecords = reopenedStorage.getAllRecordsIncludingDeleted();
            assertEquals(1, allRecords.size());
            assertTrue(allRecords.get(0).isDeleted());
            assertEquals(3000L, allRecords.get(0).getUpdatedAt());
        } finally {
            reopenedStorage.close();
        }
    }
}
