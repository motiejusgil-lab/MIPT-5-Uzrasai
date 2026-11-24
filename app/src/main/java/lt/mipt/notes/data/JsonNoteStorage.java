package lt.mipt.notes.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lt.mipt.notes.model.Note;
import lt.mipt.notes.validation.NoteValidator;

/**
 * Secondary private UTF-8 JSON file storage using temp-file + fsync + atomic replace
 * and strict validation so corrupted or failed writes never silently lose or overwrite data.
 */
public final class JsonNoteStorage implements NoteStorage {

    interface FileMover {
        void move(Path source, Path target) throws IOException;
    }

    public static final String DEFAULT_FILE_NAME = "notes_storage.json";
    private static final int SCHEMA_VERSION = 1;
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_NOTES = "notes";
    private static final String KEY_ID = "id";
    private static final String KEY_TITLE = "title";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_CREATED_AT = "createdAt";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String KEY_DELETED = "deleted";

    public static final Comparator<Note> DISPLAY_ORDER = (a, b) -> {
        int updatedCmp = Long.compare(b.getUpdatedAt(), a.getUpdatedAt());
        if (updatedCmp != 0) {
            return updatedCmp;
        }
        int createdCmp = Long.compare(b.getCreatedAt(), a.getCreatedAt());
        if (createdCmp != 0) {
            return createdCmp;
        }
        return a.getId().compareTo(b.getId());
    };

    private final File storageFile;
    private final FileMover fileMover;

    public JsonNoteStorage(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), DEFAULT_FILE_NAME));
    }

    public JsonNoteStorage(File storageFile) {
        this(storageFile, JsonNoteStorage::moveReplacing);
    }

    JsonNoteStorage(File storageFile, FileMover fileMover) {
        this.storageFile = storageFile;
        this.fileMover = fileMover;
    }

    public File getStorageFile() {
        return storageFile;
    }

    @Override
    public List<Note> getLiveNotes() throws StorageException {
        List<Note> all = getAllRecordsIncludingDeleted();
        List<Note> live = new ArrayList<>();
        for (Note note : all) {
            if (!note.isDeleted()) {
                live.add(note);
            }
        }
        live.sort(DISPLAY_ORDER);
        return live;
    }

    @Override
    public List<Note> getAllRecordsIncludingDeleted() throws StorageException {
        List<Note> records = readAllInternal();
        records.sort(DISPLAY_ORDER);
        return records;
    }

    @Override
    public Note getLiveNoteById(String id) throws StorageException {
        List<Note> all = readAllInternal();
        for (Note note : all) {
            if (note.getId().equals(id) && !note.isDeleted()) {
                return note;
            }
        }
        return null;
    }

    @Override
    public void upsertNote(Note note) throws StorageException {
        List<Note> records = readAllInternal();
        boolean replaced = false;
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).getId().equals(note.getId())) {
                records.set(i, note);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            records.add(note);
        }
        writeAllInternal(records);
    }

    @Override
    public void markDeleted(String id, long deletedAt) throws StorageException {
        List<Note> records = readAllInternal();
        boolean found = false;
        for (int i = 0; i < records.size(); i++) {
            Note existing = records.get(i);
            if (existing.getId().equals(id)) {
                records.set(i, existing.asDeleted(deletedAt));
                found = true;
                break;
            }
        }
        if (!found) {
            records.add(Note.tombstone(id, deletedAt, deletedAt));
        }
        writeAllInternal(records);
    }

    @Override
    public void replaceAllRecords(List<Note> records) throws StorageException {
        writeAllInternal(new ArrayList<>(records));
    }

    private List<Note> readAllInternal() throws StorageException {
        File legacyBackupFile = new File(storageFile.getPath() + ".bak");
        if (!storageFile.exists()) {
            if (!legacyBackupFile.exists()) {
                return new ArrayList<>();
            }
            try {
                fileMover.move(legacyBackupFile.toPath(), storageFile.toPath());
            } catch (IOException e) {
                throw new StorageException(
                        StorageException.ErrorType.IO_ERROR,
                        "Failed to recover JSON storage backup",
                        e);
            }
        }

        final String raw;
        try {
            byte[] bytes = Files.readAllBytes(storageFile.toPath());
            raw = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new StorageException(
                    StorageException.ErrorType.IO_ERROR,
                    "Failed to read JSON storage file",
                    e);
        }

        if (NoteValidator.isBlankUnicode(raw)) {
            throw new StorageException(
                    StorageException.ErrorType.CORRUPTED_JSON,
                    "JSON storage file is empty");
        }

        try {
            JSONObject root = new JSONObject(raw);
            Object versionObj = root.opt(KEY_SCHEMA_VERSION);
            if (!(versionObj instanceof Number) || ((Number) versionObj).intValue() != SCHEMA_VERSION) {
                throw new StorageException(
                        StorageException.ErrorType.CORRUPTED_JSON,
                        "Unsupported or missing JSON schemaVersion");
            }
            Object notesObj = root.opt(KEY_NOTES);
            if (!(notesObj instanceof JSONArray)) {
                throw new StorageException(
                        StorageException.ErrorType.CORRUPTED_JSON,
                        "JSON root is missing notes array");
            }
            JSONArray notesArray = (JSONArray) notesObj;
            List<Note> result = new ArrayList<>(notesArray.length());
            Set<String> seenIds = new HashSet<>(notesArray.length());
            for (int i = 0; i < notesArray.length(); i++) {
                Object element = notesArray.get(i);
                if (!(element instanceof JSONObject)) {
                    throw new StorageException(
                            StorageException.ErrorType.CORRUPTED_JSON,
                            "JSON note entry at index " + i + " is not an object");
                }
                Note note = parseNoteObject((JSONObject) element, i);
                if (!seenIds.add(note.getId())) {
                    throw new StorageException(
                            StorageException.ErrorType.CORRUPTED_JSON,
                            "Duplicate note id in JSON at index " + i);
                }
                result.add(note);
            }
            return result;
        } catch (JSONException e) {
            throw new StorageException(
                    StorageException.ErrorType.CORRUPTED_JSON,
                    "Malformed JSON storage file",
                    e);
        }
    }

    private static Note parseNoteObject(JSONObject obj, int index) throws StorageException {
        Object idObj = obj.opt(KEY_ID);
        Object titleObj = obj.opt(KEY_TITLE);
        Object contentObj = obj.opt(KEY_CONTENT);
        Object createdAtObj = obj.opt(KEY_CREATED_AT);
        Object updatedAtObj = obj.opt(KEY_UPDATED_AT);
        Object deletedObj = obj.opt(KEY_DELETED);

        if (!(idObj instanceof String) || NoteValidator.isBlankUnicode((String) idObj)) {
            throw new StorageException(
                    StorageException.ErrorType.CORRUPTED_JSON,
                    "Invalid id in JSON note at index " + index);
        }
        if (!(titleObj instanceof String)
                || !(contentObj instanceof String)
                || !(createdAtObj instanceof Number)
                || !(updatedAtObj instanceof Number)
                || !(deletedObj instanceof Boolean)) {
            throw new StorageException(
                    StorageException.ErrorType.CORRUPTED_JSON,
                    "Invalid note fields in JSON at index " + index);
        }

        return new Note(
                (String) idObj,
                (String) titleObj,
                (String) contentObj,
                ((Number) createdAtObj).longValue(),
                ((Number) updatedAtObj).longValue(),
                (Boolean) deletedObj);
    }

    private void writeAllInternal(List<Note> records) throws StorageException {
        File parent = storageFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new StorageException(
                    StorageException.ErrorType.IO_ERROR,
                    "Failed to create directory for JSON storage");
        }

        JSONObject root = new JSONObject();
        JSONArray notesArray = new JSONArray();
        try {
            root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION);
            for (Note note : records) {
                JSONObject obj = new JSONObject();
                obj.put(KEY_ID, note.getId());
                obj.put(KEY_TITLE, note.getTitle());
                obj.put(KEY_CONTENT, note.getContent());
                obj.put(KEY_CREATED_AT, note.getCreatedAt());
                obj.put(KEY_UPDATED_AT, note.getUpdatedAt());
                obj.put(KEY_DELETED, note.isDeleted());
                notesArray.put(obj);
            }
            root.put(KEY_NOTES, notesArray);
        } catch (JSONException e) {
            throw new StorageException(
                    StorageException.ErrorType.IO_ERROR,
                    "Failed to serialize notes to JSON",
                    e);
        }

        byte[] payload;
        try {
            payload = root.toString(2).getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new StorageException(
                    StorageException.ErrorType.IO_ERROR,
                    "Failed to format JSON payload",
                    e);
        }

        Path targetPath = storageFile.toPath();
        Path parentDir = targetPath.toAbsolutePath().getParent();
        Path tempPath = null;
        try {
            tempPath = Files.createTempFile(parentDir, storageFile.getName(), ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempPath.toFile())) {
                fos.write(payload);
                fos.flush();
                fos.getFD().sync();
            }
            fileMover.move(tempPath, targetPath);
            tempPath = null;
            cleanupLegacySidecarFiles();
        } catch (IOException e) {
            throw new StorageException(
                    StorageException.ErrorType.IO_ERROR,
                    "Failed to write JSON storage atomically",
                    e);
        } finally {
            if (tempPath != null) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignored) {
                    // Best-effort cleanup of temp file; preserve primary error.
                }
            }
        }
    }

    private void cleanupLegacySidecarFiles() {
        try {
            Files.deleteIfExists(new File(storageFile.getPath() + ".bak").toPath());
            Files.deleteIfExists(new File(storageFile.getPath() + ".new").toPath());
        } catch (IOException ignored) {
            // Non-fatal cleanup of legacy AtomicFile sidecars.
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
