package lt.mipt.notes.data;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import lt.mipt.notes.model.Note;
import lt.mipt.notes.model.StorageType;
import lt.mipt.notes.model.SyncResult;

/**
 * Coordinates background-threaded persistence operations across SQLite and JSON storages.
 */
public final class NotesRepository {

    public static final long NO_OPERATION = 0L;

    public interface Callback<T> {
        void onSuccess(T result);

        void onError(StorageException error);
    }

    private final SqliteNoteStorage sqliteStorage;
    private final JsonNoteStorage jsonStorage;
    private final StoragePreferences storagePreferences;
    private final NoteSyncService syncService;
    private final Clock clock;
    private final ExecutorService ioExecutor;
    private final Executor callbackExecutor;
    private final AtomicLong nextOperationId = new AtomicLong(1L);
    private final Object operationsLock = new Object();
    private final Map<Long, PendingOperation<?>> pendingOperations = new HashMap<>();
    private volatile Runnable ioGateForTests;

    public NotesRepository(
            SqliteNoteStorage sqliteStorage,
            JsonNoteStorage jsonStorage,
            StoragePreferences storagePreferences) {
        this(
                sqliteStorage,
                jsonStorage,
                storagePreferences,
                new NoteSyncService(),
                Clock.SYSTEM,
                Executors.newSingleThreadExecutor(),
                new MainThreadExecutor());
    }

    public NotesRepository(
            SqliteNoteStorage sqliteStorage,
            JsonNoteStorage jsonStorage,
            StoragePreferences storagePreferences,
            NoteSyncService syncService,
            Clock clock,
            ExecutorService ioExecutor,
            Executor callbackExecutor) {
        this.sqliteStorage = sqliteStorage;
        this.jsonStorage = jsonStorage;
        this.storagePreferences = storagePreferences;
        this.syncService = syncService;
        this.clock = clock;
        this.ioExecutor = ioExecutor;
        this.callbackExecutor = callbackExecutor;
    }

    public StorageType getActiveStorage() {
        return storagePreferences.getActiveStorage();
    }

    public void setActiveStorage(StorageType storageType) {
        storagePreferences.setActiveStorage(storageType);
    }

    public long loadNotes(StorageType storageType, Callback<List<Note>> callback) {
        return runAsync(() -> getStorage(storageType).getLiveNotes(), callback);
    }

    public long loadNoteById(StorageType storageType, String id, Callback<Note> callback) {
        return runAsync(() -> {
            Note note = getStorage(storageType).getLiveNoteById(id);
            if (note == null) {
                throw new StorageException(
                        StorageException.ErrorType.NOT_FOUND,
                        "Note not found: " + id);
            }
            return note;
        }, callback);
    }

    public long saveNote(
            StorageType storageType,
            String id,
            String title,
            String content,
            Callback<Note> callback) {
        return runAsync(() -> {
            NoteStorage storage = getStorage(storageType);
            long now = clock.nowMillis();
            Note existing = findExistingRecord(storage, id);
            Note toSave = existing != null
                    ? existing.withUpdatedContent(title, content, now)
                    : Note.createLive(id, title, content, now);
            storage.upsertNote(toSave);
            return toSave;
        }, callback);
    }

    public long deleteNote(StorageType storageType, String id, Callback<Void> callback) {
        return runAsync(() -> {
            NoteStorage storage = getStorage(storageType);
            storage.markDeleted(id, clock.nowMillis());
            return null;
        }, callback);
    }

    public long syncStorages(Callback<SyncResult> callback) {
        return runAsync(() -> syncService.synchronize(sqliteStorage, jsonStorage), callback);
    }

    /**
     * Re-attaches an Activity callback to a previously started operation after a configuration change.
     * Delivers any already-completed result or error immediately; returns false if unknown.
     */
    public <T> boolean attachOperation(long operationId, Callback<T> callback) {
        if (operationId == NO_OPERATION) {
            return false;
        }
        boolean alreadyCompleted = false;
        T result = null;
        StorageException error = null;
        synchronized (operationsLock) {
            @SuppressWarnings("unchecked")
            PendingOperation<T> op = (PendingOperation<T>) pendingOperations.get(operationId);
            if (op == null) {
                return false;
            }
            if (op.completed) {
                alreadyCompleted = true;
                result = op.result;
                error = op.error;
                pendingOperations.remove(operationId);
            } else {
                op.listener = callback;
            }
        }
        if (alreadyCompleted) {
            if (error != null) {
                callback.onError(error);
            } else {
                callback.onSuccess(result);
            }
        }
        return true;
    }

    /**
     * Detaches the current UI listener without cancelling or discarding the operation result.
     */
    public void detachOperation(long operationId) {
        if (operationId == NO_OPERATION) {
            return;
        }
        synchronized (operationsLock) {
            PendingOperation<?> op = pendingOperations.get(operationId);
            if (op != null) {
                op.listener = null;
            }
        }
    }

    /**
     * Abandons a tracked operation when its owning screen is permanently finishing.
     */
    public void abandonOperation(long operationId) {
        if (operationId == NO_OPERATION) {
            return;
        }
        synchronized (operationsLock) {
            pendingOperations.remove(operationId);
        }
    }

    /**
     * Installs a deterministic test barrier invoked on the background I/O thread
     * before executing queued storage tasks.
     */
    public void setIoGateForTests(Runnable ioGate) {
        this.ioGateForTests = ioGate;
    }

    public void shutdown() {
        ioGateForTests = null;
        synchronized (operationsLock) {
            pendingOperations.clear();
        }
        ioExecutor.shutdown();
        sqliteStorage.close();
    }

    /**
     * Blocks until all currently queued background I/O tasks complete (for deterministic tests).
     */
    public void waitForIoForTests() {
        try {
            ioExecutor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted waiting for background I/O", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new AssertionError("Failed waiting for background I/O", e);
        }
    }

    private NoteStorage getStorage(StorageType storageType) {
        return storageType == StorageType.JSON ? jsonStorage : sqliteStorage;
    }

    private static Note findExistingRecord(NoteStorage storage, String id) throws StorageException {
        for (Note record : storage.getAllRecordsIncludingDeleted()) {
            if (record.getId().equals(id)) {
                return record;
            }
        }
        return null;
    }

    private <T> long runAsync(StorageTask<T> task, Callback<T> callback) {
        long opId = nextOperationId.getAndIncrement();
        PendingOperation<T> op = new PendingOperation<>(callback);
        synchronized (operationsLock) {
            pendingOperations.put(opId, op);
        }
        ioExecutor.execute(() -> {
            Runnable gate = ioGateForTests;
            if (gate != null) {
                gate.run();
            }
            T result = null;
            StorageException error = null;
            try {
                result = task.execute();
            } catch (StorageException e) {
                error = e;
            }
            final T finalResult = result;
            final StorageException finalError = error;
            callbackExecutor.execute(() -> completeOperation(opId, finalResult, finalError));
        });
        return opId;
    }

    private <T> void completeOperation(long opId, T result, StorageException error) {
        Callback<T> listenerToNotify = null;
        synchronized (operationsLock) {
            @SuppressWarnings("unchecked")
            PendingOperation<T> op = (PendingOperation<T>) pendingOperations.get(opId);
            if (op == null) {
                return;
            }
            op.completed = true;
            op.result = result;
            op.error = error;
            if (op.listener != null) {
                listenerToNotify = op.listener;
                op.listener = null;
                pendingOperations.remove(opId);
            }
        }
        if (listenerToNotify != null) {
            if (error != null) {
                listenerToNotify.onError(error);
            } else {
                listenerToNotify.onSuccess(result);
            }
        }
    }

    private static final class PendingOperation<T> {
        Callback<T> listener;
        boolean completed;
        T result;
        StorageException error;

        PendingOperation(Callback<T> listener) {
            this.listener = listener;
        }
    }

    private interface StorageTask<T> {
        T execute() throws StorageException;
    }

    private static final class MainThreadExecutor implements Executor {
        private final Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(Runnable command) {
            handler.post(command);
        }
    }
}
