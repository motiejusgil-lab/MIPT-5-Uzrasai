package lt.mipt.notes.model;

/**
 * Summary of a bidirectional synchronization pass between SQLite and JSON storages.
 */
public final class SyncResult {

    private final int totalLiveNotes;
    private final int totalTombstones;

    public SyncResult(int totalLiveNotes, int totalTombstones) {
        this.totalLiveNotes = totalLiveNotes;
        this.totalTombstones = totalTombstones;
    }

    public int getTotalLiveNotes() {
        return totalLiveNotes;
    }

    public int getTotalTombstones() {
        return totalTombstones;
    }
}
