package lt.mipt.notes.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import lt.mipt.notes.model.Note;

/**
 * Primary SQLite storage backend using explicit transactions for all writes.
 */
public final class SqliteNoteStorage implements NoteStorage {

    private static final String ORDER_BY_UPDATED =
            NotesDbHelper.COL_UPDATED_AT + " DESC, "
                    + NotesDbHelper.COL_CREATED_AT + " DESC, "
                    + NotesDbHelper.COL_ID + " ASC";

    private final NotesDbHelper dbHelper;

    public SqliteNoteStorage(NotesDbHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    @Override
    public List<Note> getLiveNotes() throws StorageException {
        return queryNotes(NotesDbHelper.COL_DELETED + " = 0", null);
    }

    @Override
    public List<Note> getAllRecordsIncludingDeleted() throws StorageException {
        return queryNotes(null, null);
    }

    @Override
    public Note getLiveNoteById(String id) throws StorageException {
        List<Note> notes = queryNotes(
                NotesDbHelper.COL_ID + " = ? AND " + NotesDbHelper.COL_DELETED + " = 0",
                new String[]{id});
        return notes.isEmpty() ? null : notes.get(0);
    }

    @Override
    public void upsertNote(Note note) throws StorageException {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                db.insertWithOnConflict(
                        NotesDbHelper.TABLE_NOTES,
                        null,
                        toContentValues(note),
                        SQLiteDatabase.CONFLICT_REPLACE);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (SQLException | IllegalStateException e) {
            throw new StorageException(
                    StorageException.ErrorType.DATABASE_ERROR,
                    "Failed to save note to SQLite",
                    e);
        }
    }

    @Override
    public void markDeleted(String id, long deletedAt) throws StorageException {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                Note existing = findAnyById(db, id);
                Note tombstone = existing != null
                        ? existing.asDeleted(deletedAt)
                        : Note.tombstone(id, deletedAt, deletedAt);
                db.insertWithOnConflict(
                        NotesDbHelper.TABLE_NOTES,
                        null,
                        toContentValues(tombstone),
                        SQLiteDatabase.CONFLICT_REPLACE);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (SQLException | IllegalStateException e) {
            throw new StorageException(
                    StorageException.ErrorType.DATABASE_ERROR,
                    "Failed to mark note as deleted in SQLite",
                    e);
        }
    }

    @Override
    public void replaceAllRecords(List<Note> records) throws StorageException {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.beginTransaction();
            try {
                db.delete(NotesDbHelper.TABLE_NOTES, null, null);
                for (Note note : records) {
                    db.insertWithOnConflict(
                            NotesDbHelper.TABLE_NOTES,
                            null,
                            toContentValues(note),
                            SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (SQLException | IllegalStateException e) {
            throw new StorageException(
                    StorageException.ErrorType.DATABASE_ERROR,
                    "Failed to replace SQLite records",
                    e);
        }
    }

    public void close() {
        dbHelper.close();
    }

    private List<Note> queryNotes(String selection, String[] selectionArgs) throws StorageException {
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            List<Note> result = new ArrayList<>();
            try (Cursor cursor = db.query(
                    NotesDbHelper.TABLE_NOTES,
                    null,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    ORDER_BY_UPDATED)) {
                while (cursor.moveToNext()) {
                    result.add(fromCursor(cursor));
                }
            }
            return result;
        } catch (SQLException | IllegalStateException e) {
            throw new StorageException(
                    StorageException.ErrorType.DATABASE_ERROR,
                    "Failed to query SQLite database",
                    e);
        }
    }

    private Note findAnyById(SQLiteDatabase db, String id) {
        try (Cursor cursor = db.query(
                NotesDbHelper.TABLE_NOTES,
                null,
                NotesDbHelper.COL_ID + " = ?",
                new String[]{id},
                null,
                null,
                null)) {
            if (cursor.moveToFirst()) {
                return fromCursor(cursor);
            }
            return null;
        }
    }

    private static Note fromCursor(Cursor cursor) {
        String id = cursor.getString(cursor.getColumnIndexOrThrow(NotesDbHelper.COL_ID));
        String title = cursor.getString(cursor.getColumnIndexOrThrow(NotesDbHelper.COL_TITLE));
        String content = cursor.getString(cursor.getColumnIndexOrThrow(NotesDbHelper.COL_CONTENT));
        long createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(NotesDbHelper.COL_CREATED_AT));
        long updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(NotesDbHelper.COL_UPDATED_AT));
        boolean deleted = cursor.getInt(cursor.getColumnIndexOrThrow(NotesDbHelper.COL_DELETED)) != 0;
        return new Note(id, title, content, createdAt, updatedAt, deleted);
    }

    private static ContentValues toContentValues(Note note) {
        ContentValues values = new ContentValues();
        values.put(NotesDbHelper.COL_ID, note.getId());
        values.put(NotesDbHelper.COL_TITLE, note.getTitle());
        values.put(NotesDbHelper.COL_CONTENT, note.getContent());
        values.put(NotesDbHelper.COL_CREATED_AT, note.getCreatedAt());
        values.put(NotesDbHelper.COL_UPDATED_AT, note.getUpdatedAt());
        values.put(NotesDbHelper.COL_DELETED, note.isDeleted() ? 1 : 0);
        return values;
    }
}
