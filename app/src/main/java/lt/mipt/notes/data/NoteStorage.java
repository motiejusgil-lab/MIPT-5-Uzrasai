package lt.mipt.notes.data;

import java.util.List;

import lt.mipt.notes.model.Note;

/**
 * Abstraction over a single note persistence backend (SQLite or private JSON).
 */
public interface NoteStorage {

    List<Note> getLiveNotes() throws StorageException;

    List<Note> getAllRecordsIncludingDeleted() throws StorageException;

    Note getLiveNoteById(String id) throws StorageException;

    void upsertNote(Note note) throws StorageException;

    void markDeleted(String id, long deletedAt) throws StorageException;

    void replaceAllRecords(List<Note> records) throws StorageException;
}
