package lt.mipt.notes.model;

import java.util.Objects;

/**
 * Immutable domain model representing either an active note or a deletion tombstone.
 */
public final class Note {

    private final String id;
    private final String title;
    private final String content;
    private final long createdAt;
    private final long updatedAt;
    private final boolean deleted;

    public Note(
            String id,
            String title,
            String content,
            long createdAt,
            long updatedAt,
            boolean deleted) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.title = title == null ? "" : title;
        this.content = content == null ? "" : content;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deleted = deleted;
    }

    public static Note createLive(String id, String title, String content, long nowMillis) {
        return new Note(id, title, content, nowMillis, nowMillis, false);
    }

    public static Note tombstone(String id, long createdAt, long deletedAt) {
        return new Note(id, "", "", createdAt, deletedAt, true);
    }

    public Note withUpdatedContent(String newTitle, String newContent, long nowMillis) {
        long nextUpdatedAt = Math.max(nowMillis, this.updatedAt);
        return new Note(id, newTitle, newContent, createdAt, nextUpdatedAt, false);
    }

    public Note asDeleted(long deletedAt) {
        long nextUpdatedAt = Math.max(deletedAt, this.updatedAt);
        return new Note(id, "", "", createdAt, nextUpdatedAt, true);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Note)) {
            return false;
        }
        Note note = (Note) o;
        return createdAt == note.createdAt
                && updatedAt == note.updatedAt
                && deleted == note.deleted
                && id.equals(note.id)
                && title.equals(note.title)
                && content.equals(note.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, title, content, createdAt, updatedAt, deleted);
    }
}
