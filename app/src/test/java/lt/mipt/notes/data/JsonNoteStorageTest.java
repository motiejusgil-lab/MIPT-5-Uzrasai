package lt.mipt.notes.data;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import lt.mipt.notes.model.Note;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
public class JsonNoteStorageTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void crudAndReopenWithUtf8CharactersWork() throws Exception {
        File jsonFile = new File(tempFolder.getRoot(), "notes_storage.json");
        JsonNoteStorage storage = new JsonNoteStorage(jsonFile);

        Note note = Note.createLive("json-1", "Užrašas ĄČĘĖĮŠŲŪŽ", "Tekstas su emoji 😊 ir €", 1500L);
        storage.upsertNote(note);

        Note updated = note.withUpdatedContent("Atnaujintas užrašas", "Atnaujintas tekstas", 2500L);
        storage.upsertNote(updated);

        JsonNoteStorage reopened = new JsonNoteStorage(jsonFile);
        List<Note> live = reopened.getLiveNotes();
        assertEquals(1, live.size());
        assertEquals("Atnaujintas užrašas", live.get(0).getTitle());
        assertEquals("Atnaujintas tekstas", live.get(0).getContent());
        assertNotNull(reopened.getLiveNoteById("json-1"));

        reopened.markDeleted("json-1", 3500L);
        assertTrue(reopened.getLiveNotes().isEmpty());
        assertNull(reopened.getLiveNoteById("json-1"));

        List<Note> allRecords = reopened.getAllRecordsIncludingDeleted();
        assertEquals(1, allRecords.size());
        assertTrue(allRecords.get(0).isDeleted());
        assertEquals(3500L, allRecords.get(0).getUpdatedAt());
    }

    @Test
    public void corruptedJsonThrowsTypedErrorAndPreservesFileContents() throws IOException {
        File jsonFile = new File(tempFolder.getRoot(), "corrupted.json");
        String corruptedPayload = "{ \"schemaVersion\": 1, \"notes\": [ BROKEN";
        Files.write(jsonFile.toPath(), corruptedPayload.getBytes(StandardCharsets.UTF_8));

        JsonNoteStorage storage = new JsonNoteStorage(jsonFile);
        try {
            storage.getLiveNotes();
            fail("Expected CORRUPTED_JSON StorageException");
        } catch (StorageException expected) {
            assertEquals(StorageException.ErrorType.CORRUPTED_JSON, expected.getErrorType());
        }

        try {
            storage.upsertNote(Note.createLive("new-id", "Title", "Body", 5000L));
            fail("Expected CORRUPTED_JSON when saving over corrupted file");
        } catch (StorageException expected) {
            assertEquals(StorageException.ErrorType.CORRUPTED_JSON, expected.getErrorType());
        }

        String preserved = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
        assertEquals(corruptedPayload, preserved);
    }

    @Test
    public void emptyOrSchemaInvalidJsonThrowsCorruptedJson() throws IOException {
        File emptyFile = new File(tempFolder.getRoot(), "empty.json");
        Files.write(emptyFile.toPath(), "   \n ".getBytes(StandardCharsets.UTF_8));
        JsonNoteStorage emptyStorage = new JsonNoteStorage(emptyFile);
        try {
            emptyStorage.getAllRecordsIncludingDeleted();
            fail("Expected CORRUPTED_JSON for whitespace-only file");
        } catch (StorageException expected) {
            assertEquals(StorageException.ErrorType.CORRUPTED_JSON, expected.getErrorType());
        }

        File invalidFieldsFile = new File(tempFolder.getRoot(), "invalid_fields.json");
        String badFields = "{\"schemaVersion\":1,\"notes\":[{\"id\":\"1\",\"title\":\"A\",\"content\":\"B\",\"createdAt\":1,\"updatedAt\":2,\"deleted\":\"not_bool\"}]}";
        Files.write(invalidFieldsFile.toPath(), badFields.getBytes(StandardCharsets.UTF_8));
        JsonNoteStorage badFieldsStorage = new JsonNoteStorage(invalidFieldsFile);
        try {
            badFieldsStorage.getLiveNotes();
            fail("Expected CORRUPTED_JSON for wrong field type");
        } catch (StorageException expected) {
            assertEquals(StorageException.ErrorType.CORRUPTED_JSON, expected.getErrorType());
        }
    }

    @Test
    public void failedMovePreservesExistingFileAndThrowsIoError() throws Exception {
        File jsonFile = new File(tempFolder.getRoot(), "notes_storage.json");
        JsonNoteStorage storage = new JsonNoteStorage(jsonFile);
        storage.upsertNote(Note.createLive("id-1", "Original title", "Original body", 1000L));
        String beforeFailure = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);

        JsonNoteStorage failingStorage = new JsonNoteStorage(jsonFile, (source, target) -> {
            throw new IOException("Simulated atomic replace failure");
        });

        try {
            failingStorage.upsertNote(Note.createLive("id-1", "Overwritten", "Lost", 2000L));
            fail("Expected IO_ERROR when atomic replace fails");
        } catch (StorageException expected) {
            assertEquals(StorageException.ErrorType.IO_ERROR, expected.getErrorType());
        }

        String afterFailure = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
        assertEquals(beforeFailure, afterFailure);
        List<Note> notes = storage.getLiveNotes();
        assertEquals(1, notes.size());
        assertEquals("Original title", notes.get(0).getTitle());

        File[] leftoverTemps = tempFolder.getRoot().listFiles((dir, name) -> name.endsWith(".tmp"));
        assertNotNull(leftoverTemps);
        assertEquals(0, leftoverTemps.length);
    }

    @Test
    public void duplicateIdsRejectedAsCorruptedJson() throws IOException {
        File jsonFile = new File(tempFolder.getRoot(), "duplicate_ids.json");
        String duplicatePayload = "{\"schemaVersion\":1,\"notes\":["
                + "{\"id\":\"dup-1\",\"title\":\"First\",\"content\":\"A\",\"createdAt\":1,\"updatedAt\":2,\"deleted\":false},"
                + "{\"id\":\"dup-1\",\"title\":\"Second\",\"content\":\"B\",\"createdAt\":1,\"updatedAt\":3,\"deleted\":false}"
                + "]}";
        Files.write(jsonFile.toPath(), duplicatePayload.getBytes(StandardCharsets.UTF_8));

        JsonNoteStorage storage = new JsonNoteStorage(jsonFile);
        try {
            storage.getLiveNotes();
            fail("Expected CORRUPTED_JSON for duplicate note IDs");
        } catch (StorageException expected) {
            assertEquals(StorageException.ErrorType.CORRUPTED_JSON, expected.getErrorType());
        }

        String preserved = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
        assertEquals(duplicatePayload, preserved);
    }
}
