package lt.mipt.notes.data;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lt.mipt.notes.model.Note;
import lt.mipt.notes.model.SyncResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NoteSyncServiceTest {

    private final NoteSyncService syncService = new NoteSyncService();

    @Test
    public void bidirectionalMergeCopiesUniqueNotesBothWays() throws StorageException {
        InMemoryStorage sqlite = new InMemoryStorage();
        InMemoryStorage json = new InMemoryStorage();

        sqlite.upsertNote(Note.createLive("s-1", "SQLite note", "From SQLite", 100L));
        json.upsertNote(Note.createLive("j-1", "JSON note", "From JSON", 200L));

        SyncResult result = syncService.synchronize(sqlite, json);
        assertEquals(2, result.getTotalLiveNotes());
        assertEquals(0, result.getTotalTombstones());
        assertEquals(2, sqlite.getLiveNotes().size());
        assertEquals(2, json.getLiveNotes().size());
        assertNotNull(sqlite.getLiveNoteById("j-1"));
        assertNotNull(json.getLiveNoteById("s-1"));
    }

    @Test
    public void newerUpdateWinsRegardlessOfStorageSide() throws StorageException {
        InMemoryStorage sqlite = new InMemoryStorage();
        InMemoryStorage json = new InMemoryStorage();

        sqlite.upsertNote(new Note("n-1", "Old SQLite", "Old", 100L, 200L, false));
        json.upsertNote(new Note("n-1", "New JSON", "New", 100L, 500L, false));

        sqlite.upsertNote(new Note("n-2", "New SQLite", "New2", 100L, 700L, false));
        json.upsertNote(new Note("n-2", "Old JSON", "Old2", 100L, 300L, false));

        syncService.synchronize(sqlite, json);

        assertEquals("New JSON", sqlite.getLiveNoteById("n-1").getTitle());
        assertEquals("New JSON", json.getLiveNoteById("n-1").getTitle());
        assertEquals("New SQLite", sqlite.getLiveNoteById("n-2").getTitle());
        assertEquals("New SQLite", json.getLiveNoteById("n-2").getTitle());
    }

    @Test
    public void deletionTombstonePropagatesAndDoesNotResurrect() throws StorageException {
        InMemoryStorage sqlite = new InMemoryStorage();
        InMemoryStorage json = new InMemoryStorage();

        Note initial = Note.createLive("n-del", "To delete", "Body", 100L);
        sqlite.upsertNote(initial);
        json.upsertNote(initial);

        sqlite.markDeleted("n-del", 300L);

        SyncResult firstSync = syncService.synchronize(sqlite, json);
        assertEquals(0, firstSync.getTotalLiveNotes());
        assertEquals(1, firstSync.getTotalTombstones());
        assertNull(sqlite.getLiveNoteById("n-del"));
        assertNull(json.getLiveNoteById("n-del"));

        SyncResult secondSync = syncService.synchronize(sqlite, json);
        assertEquals(0, secondSync.getTotalLiveNotes());
        assertEquals(1, secondSync.getTotalTombstones());
        assertTrue(sqlite.getLiveNotes().isEmpty());
        assertTrue(json.getLiveNotes().isEmpty());
    }

    @Test
    public void equalTimestampGivesDeletePriorityAndDeterministicTieBreak() throws StorageException {
        InMemoryStorage sqlite = new InMemoryStorage();
        InMemoryStorage json = new InMemoryStorage();

        // Equal updatedAt between live and tombstone -> tombstone wins.
        sqlite.upsertNote(new Note("same-ts-del", "Alive", "Text", 100L, 500L, false));
        json.upsertNote(Note.tombstone("same-ts-del", 100L, 500L));

        // Equal updatedAt between two live notes -> deterministic & commutative.
        Note liveA = new Note("same-ts-live", "Alpha", "Body A", 100L, 600L, false);
        Note liveB = new Note("same-ts-live", "Beta", "Body B", 100L, 600L, false);
        sqlite.upsertNote(liveA);
        json.upsertNote(liveB);

        syncService.synchronize(sqlite, json);

        assertNull(sqlite.getLiveNoteById("same-ts-del"));
        assertNull(json.getLiveNoteById("same-ts-del"));
        assertEquals(
                NoteSyncService.resolveConflict(liveA, liveB),
                NoteSyncService.resolveConflict(liveB, liveA));
        assertEquals(
                sqlite.getLiveNoteById("same-ts-live"),
                json.getLiveNoteById("same-ts-live"));
    }

    @Test
    public void corruptedJsonAbortsSyncWithoutTouchingSqlite() throws StorageException {
        InMemoryStorage sqlite = new InMemoryStorage();
        sqlite.upsertNote(Note.createLive("safe-1", "Safe", "Must stay", 100L));

        NoteStorage brokenJson = new InMemoryStorage() {
            @Override
            public List<Note> getAllRecordsIncludingDeleted() throws StorageException {
                throw new StorageException(StorageException.ErrorType.CORRUPTED_JSON, "Broken");
            }
        };

        try {
            syncService.synchronize(sqlite, brokenJson);
            fail("Expected sync to fail on corrupted JSON");
        } catch (StorageException expected) {
            assertEquals(StorageException.ErrorType.CORRUPTED_JSON, expected.getErrorType());
        }

        assertFalse(sqlite.getLiveNotes().isEmpty());
        assertEquals("Safe", sqlite.getLiveNoteById("safe-1").getTitle());
    }

    @Test
    public void jsonWriteFailureRollsBackSqliteChanges() throws StorageException {
        InMemoryStorage sqlite = new InMemoryStorage();
        Note originalSqlite = Note.createLive("orig-1", "Original SQLite", "Keep me", 100L);
        sqlite.upsertNote(originalSqlite);

        InMemoryStorage failingJson = new InMemoryStorage() {
            @Override
            public void replaceAllRecords(List<Note> newRecords) throws StorageException {
                throw new StorageException(StorageException.ErrorType.IO_ERROR, "Simulated JSON write failure");
            }
        };
        failingJson.upsertNote(Note.createLive("json-1", "JSON note", "Should not remain in SQLite", 200L));

        try {
            syncService.synchronize(sqlite, failingJson);
            fail("Expected sync to fail when JSON write throws");
        } catch (StorageException expected) {
            assertEquals(StorageException.ErrorType.IO_ERROR, expected.getErrorType());
        }

        List<Note> sqliteAfterRollback = sqlite.getAllRecordsIncludingDeleted();
        assertEquals(1, sqliteAfterRollback.size());
        assertEquals(originalSqlite, sqliteAfterRollback.get(0));
    }

    private static class InMemoryStorage implements NoteStorage {
        private final Map<String, Note> records = new LinkedHashMap<>();

        @Override
        public List<Note> getLiveNotes() {
            List<Note> live = new ArrayList<>();
            for (Note note : records.values()) {
                if (!note.isDeleted()) {
                    live.add(note);
                }
            }
            return live;
        }

        @Override
        public List<Note> getAllRecordsIncludingDeleted() throws StorageException {
            return new ArrayList<>(records.values());
        }

        @Override
        public Note getLiveNoteById(String id) {
            Note note = records.get(id);
            return (note != null && !note.isDeleted()) ? note : null;
        }

        @Override
        public void upsertNote(Note note) {
            records.put(note.getId(), note);
        }

        @Override
        public void markDeleted(String id, long deletedAt) {
            Note existing = records.get(id);
            records.put(id, existing != null
                    ? existing.asDeleted(deletedAt)
                    : Note.tombstone(id, deletedAt, deletedAt));
        }

        @Override
        public void replaceAllRecords(List<Note> newRecords) throws StorageException {
            records.clear();
            for (Note note : newRecords) {
                records.put(note.getId(), note);
            }
        }
    }
}
