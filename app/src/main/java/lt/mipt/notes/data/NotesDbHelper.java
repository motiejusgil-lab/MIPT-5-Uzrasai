package lt.mipt.notes.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite schema helper for local notes and deletion tombstones.
 */
public final class NotesDbHelper extends SQLiteOpenHelper {

    public static final String DATABASE_NAME = "notes.db";
    public static final int DATABASE_VERSION = 1;

    public static final String TABLE_NOTES = "notes";
    public static final String COL_ID = "id";
    public static final String COL_TITLE = "title";
    public static final String COL_CONTENT = "content";
    public static final String COL_CREATED_AT = "created_at";
    public static final String COL_UPDATED_AT = "updated_at";
    public static final String COL_DELETED = "deleted";

    private static final String SQL_CREATE_NOTES_TABLE =
            "CREATE TABLE " + TABLE_NOTES + " ("
                    + COL_ID + " TEXT PRIMARY KEY NOT NULL, "
                    + COL_TITLE + " TEXT NOT NULL, "
                    + COL_CONTENT + " TEXT NOT NULL, "
                    + COL_CREATED_AT + " INTEGER NOT NULL, "
                    + COL_UPDATED_AT + " INTEGER NOT NULL, "
                    + COL_DELETED + " INTEGER NOT NULL DEFAULT 0"
                    + ")";

    private static final String SQL_CREATE_INDEX_NOTES =
            "CREATE INDEX idx_notes_deleted_updated ON "
                    + TABLE_NOTES + " (" + COL_DELETED + ", " + COL_UPDATED_AT + " DESC)";

    public NotesDbHelper(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_NOTES_TABLE);
        db.execSQL(SQL_CREATE_INDEX_NOTES);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_NOTES);
        onCreate(db);
    }
}
