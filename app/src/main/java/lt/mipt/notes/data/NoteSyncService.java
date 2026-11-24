package lt.mipt.notes.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lt.mipt.notes.model.Note;
import lt.mipt.notes.model.SyncResult;

/**
 * Bidirectional synchronization engine between SQLite and JSON storages using UUIDs,
 * updatedAt timestamps, deletion tombstones, and deterministic tie-breaking.
 */
public final class NoteSyncService {

    public SyncResult synchronize(NoteStorage sqliteStorage, NoteStorage jsonStorage)
            throws StorageException {
        List<Note> sqliteRecords = sqliteStorage.getAllRecordsIncludingDeleted();
        List<Note> jsonRecords = jsonStorage.getAllRecordsIncludingDeleted();

        Map<String, Note> mergedById = new LinkedHashMap<>();
        for (Note record : sqliteRecords) {
            mergedById.put(record.getId(), record);
        }
        for (Note jsonRecord : jsonRecords) {
            Note existing = mergedById.get(jsonRecord.getId());
            if (existing == null) {
                mergedById.put(jsonRecord.getId(), jsonRecord);
            } else {
                mergedById.put(jsonRecord.getId(), resolveConflict(existing, jsonRecord));
            }
        }

        List<Note> merged = new ArrayList<>(mergedById.values());
        merged.sort(JsonNoteStorage.DISPLAY_ORDER);

        boolean sqliteNeedsWrite = !sameRecordSet(sqliteRecords, merged);
        boolean jsonNeedsWrite = !sameRecordSet(jsonRecords, merged);

        if (sqliteNeedsWrite) {
            sqliteStorage.replaceAllRecords(merged);
        }
        if (jsonNeedsWrite) {
            try {
                jsonStorage.replaceAllRecords(merged);
            } catch (StorageException jsonWriteError) {
                if (sqliteNeedsWrite) {
                    try {
                        sqliteStorage.replaceAllRecords(sqliteRecords);
                    } catch (StorageException ignoredRollbackError) {
                        // Preserve original JSON write failure as primary cause.
                    }
                }
                throw jsonWriteError;
            }
        }

        int liveCount = 0;
        int tombstoneCount = 0;
        for (Note note : merged) {
            if (note.isDeleted()) {
                tombstoneCount++;
            } else {
                liveCount++;
            }
        }
        return new SyncResult(liveCount, tombstoneCount);
    }

    /**
     * Deterministic conflict resolution:
     * 1. Higher updatedAt wins.
     * 2. On equal updatedAt, deletion tombstone wins over a live note.
     * 3. Otherwise deterministic lexicographic comparison keeps merge commutative.
     */
    public static Note resolveConflict(Note first, Note second) {
        if (first.getUpdatedAt() > second.getUpdatedAt()) {
            return first;
        }
        if (second.getUpdatedAt() > first.getUpdatedAt()) {
            return second;
        }
        if (first.isDeleted() != second.isDeleted()) {
            return first.isDeleted() ? first : second;
        }
        if (first.isDeleted()) {
            return first.getCreatedAt() <= second.getCreatedAt() ? first : second;
        }
        int titleCmp = first.getTitle().compareTo(second.getTitle());
        if (titleCmp != 0) {
            return titleCmp > 0 ? first : second;
        }
        int contentCmp = first.getContent().compareTo(second.getContent());
        if (contentCmp != 0) {
            return contentCmp > 0 ? first : second;
        }
        return first.getCreatedAt() <= second.getCreatedAt() ? first : second;
    }

    private static boolean sameRecordSet(List<Note> current, List<Note> target) {
        if (current.size() != target.size()) {
            return false;
        }
        Map<String, Note> byId = new HashMap<>(current.size());
        for (Note note : current) {
            byId.put(note.getId(), note);
        }
        for (Note note : target) {
            Note existing = byId.get(note.getId());
            if (!note.equals(existing)) {
                return false;
            }
        }
        return true;
    }
}
