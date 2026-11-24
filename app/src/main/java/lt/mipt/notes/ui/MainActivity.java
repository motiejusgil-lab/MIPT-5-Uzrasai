package lt.mipt.notes.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;

import lt.mipt.notes.NotesApplication;
import lt.mipt.notes.R;
import lt.mipt.notes.data.NotesRepository;
import lt.mipt.notes.data.StorageException;
import lt.mipt.notes.model.Note;
import lt.mipt.notes.model.StorageType;
import lt.mipt.notes.model.SyncResult;

/**
 * Primary screen displaying notes from the selected storage, storage switcher, and sync control.
 */
public class MainActivity extends Activity {

    private static final String STATE_PENDING_OP_ID = "state_pending_op_id";
    private static final String STATE_PENDING_OP_TYPE = "state_pending_op_type";
    private static final String STATE_PENDING_LOAD_STORAGE = "state_pending_load_storage";
    private static final String STATE_PENDING_ERROR_TYPE = "state_pending_error_type";

    private static final int OP_NONE = 0;
    private static final int OP_LOAD = 1;
    private static final int OP_SYNC = 2;

    private NotesRepository repository;
    private NoteListAdapter adapter;

    private RadioGroup radioGroupStorage;
    private RadioButton radioSqlite;
    private RadioButton radioJson;
    private TextView textStorageStatus;
    private ProgressBar progressMain;
    private Button btnSync;
    private Button btnAddNote;
    private ListView listNotes;
    private View emptyState;

    private StorageType activeStorage;
    private boolean suppressStorageRadioCallback;
    private boolean operationInProgress;
    private boolean launchingEditor;
    private long pendingOpId = NotesRepository.NO_OPERATION;
    private int pendingOpType = OP_NONE;
    private StorageType pendingLoadStorage;
    private StorageException.ErrorType pendingErrorType;
    private boolean suppressingDialogDismiss;
    private boolean hasLoadedOnce;
    private AlertDialog activeDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = ((NotesApplication) getApplication()).getRepository();

        View root = findViewById(R.id.root_main);
        View header = findViewById(R.id.header_main);
        View bottomBar = findViewById(R.id.bottom_bar_main);
        WindowInsetsHelper.applyEdgeToEdgePadding(root, header, bottomBar);

        radioGroupStorage = findViewById(R.id.radio_group_storage);
        radioSqlite = findViewById(R.id.radio_sqlite);
        radioJson = findViewById(R.id.radio_json);
        textStorageStatus = findViewById(R.id.text_storage_status);
        progressMain = findViewById(R.id.progress_main);
        btnSync = findViewById(R.id.btn_sync);
        btnAddNote = findViewById(R.id.btn_add_note);
        listNotes = findViewById(R.id.list_notes);
        emptyState = findViewById(R.id.empty_state);

        adapter = new NoteListAdapter(this);
        listNotes.setAdapter(adapter);

        listNotes.setOnItemClickListener((parent, view, position, id) -> {
            if (operationInProgress || launchingEditor) {
                return;
            }
            Note note = adapter.getItem(position);
            openEditor(note.getId());
        });

        btnAddNote.setOnClickListener(v -> {
            if (operationInProgress || launchingEditor) {
                return;
            }
            btnAddNote.setEnabled(false);
            openEditor(null);
        });

        btnSync.setOnClickListener(v -> startSync());

        radioGroupStorage.setOnCheckedChangeListener((group, checkedId) -> {
            if (suppressStorageRadioCallback || operationInProgress) {
                return;
            }
            StorageType selected = checkedId == R.id.radio_json
                    ? StorageType.JSON
                    : StorageType.SQLITE;
            if (selected != activeStorage) {
                activeStorage = selected;
                repository.setActiveStorage(activeStorage);
                loadNotes();
            }
        });

        if (savedInstanceState != null) {
            pendingOpId = savedInstanceState.getLong(STATE_PENDING_OP_ID, NotesRepository.NO_OPERATION);
            pendingOpType = savedInstanceState.getInt(STATE_PENDING_OP_TYPE, OP_NONE);
            String savedLoadStorage = savedInstanceState.getString(STATE_PENDING_LOAD_STORAGE);
            if (savedLoadStorage != null) {
                pendingLoadStorage = StorageType.fromPrefValue(savedLoadStorage);
            }
            String savedErrorName = savedInstanceState.getString(STATE_PENDING_ERROR_TYPE);
            if (savedErrorName != null) {
                try {
                    pendingErrorType = StorageException.ErrorType.valueOf(savedErrorName);
                } catch (IllegalArgumentException ignored) {
                    pendingErrorType = null;
                }
            }
        }

        syncStorageSelectionFromPrefs();
        if (pendingOpId != NotesRepository.NO_OPERATION && pendingOpType != OP_NONE) {
            setOperationInProgress(true);
        } else if (pendingErrorType != null) {
            showError(new StorageException(pendingErrorType, "Restored storage error"));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        reattachPendingOperation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        launchingEditor = false;
        if (!operationInProgress) {
            btnAddNote.setEnabled(true);
        }
        syncStorageSelectionFromPrefs();
        if (pendingOpId == NotesRepository.NO_OPERATION && pendingErrorType == null) {
            loadNotes();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        repository.detachOperation(pendingOpId);
        outState.putLong(STATE_PENDING_OP_ID, pendingOpId);
        outState.putInt(STATE_PENDING_OP_TYPE, pendingOpType);
        outState.putString(
                STATE_PENDING_LOAD_STORAGE,
                pendingLoadStorage != null ? pendingLoadStorage.getPrefValue() : null);
        outState.putString(
                STATE_PENDING_ERROR_TYPE,
                pendingErrorType != null ? pendingErrorType.name() : null);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            repository.abandonOperation(pendingOpId);
        }
        dismissActiveDialog();
        super.onDestroy();
    }

    private void syncStorageSelectionFromPrefs() {
        activeStorage = repository.getActiveStorage();
        suppressStorageRadioCallback = true;
        if (activeStorage == StorageType.JSON) {
            radioJson.setChecked(true);
        } else {
            radioSqlite.setChecked(true);
        }
        suppressStorageRadioCallback = false;
    }

    private void reattachPendingOperation() {
        if (pendingOpId == NotesRepository.NO_OPERATION || pendingOpType == OP_NONE) {
            return;
        }
        final boolean attached;
        if (pendingOpType == OP_SYNC) {
            attached = repository.attachOperation(pendingOpId, createSyncCallback());
        } else if (pendingOpType == OP_LOAD) {
            StorageType targetStorage = pendingLoadStorage != null ? pendingLoadStorage : activeStorage;
            attached = repository.attachOperation(pendingOpId, createLoadCallback(targetStorage));
        } else {
            attached = false;
        }
        if (!attached) {
            clearPendingOperation();
            setOperationInProgress(false);
        }
    }

    private void clearPendingOperation() {
        pendingOpId = NotesRepository.NO_OPERATION;
        pendingOpType = OP_NONE;
        pendingLoadStorage = null;
    }

    private void loadNotes() {
        final StorageType requestedStorage = activeStorage;
        pendingLoadStorage = requestedStorage;
        pendingOpType = OP_LOAD;
        setOperationInProgress(true);
        pendingOpId = repository.loadNotes(requestedStorage, createLoadCallback(requestedStorage));
    }

    private NotesRepository.Callback<List<Note>> createLoadCallback(StorageType requestedStorage) {
        return new NotesRepository.Callback<>() {
            @Override
            public void onSuccess(List<Note> result) {
                clearPendingOperation();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (requestedStorage != activeStorage) {
                    setOperationInProgress(false);
                    return;
                }
                hasLoadedOnce = true;
                setOperationInProgress(false);
                renderNotes(result, requestedStorage);
            }

            @Override
            public void onError(StorageException error) {
                clearPendingOperation();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                hasLoadedOnce = true;
                setOperationInProgress(false);
                renderNotes(Collections.emptyList(), requestedStorage);
                showError(error);
            }
        };
    }

    private void startSync() {
        if (operationInProgress) {
            return;
        }
        pendingOpType = OP_SYNC;
        setOperationInProgress(true);
        pendingOpId = repository.syncStorages(createSyncCallback());
    }

    private NotesRepository.Callback<SyncResult> createSyncCallback() {
        return new NotesRepository.Callback<>() {
            @Override
            public void onSuccess(SyncResult result) {
                clearPendingOperation();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                Toast.makeText(
                        MainActivity.this,
                        getString(R.string.msg_sync_success_format, result.getTotalLiveNotes()),
                        Toast.LENGTH_SHORT).show();
                loadNotes();
            }

            @Override
            public void onError(StorageException error) {
                clearPendingOperation();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setOperationInProgress(false);
                showError(error);
            }
        };
    }

    private void renderNotes(List<Note> notes, StorageType storageType) {
        adapter.setNotes(notes);
        boolean isEmpty = notes.isEmpty();
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        listNotes.setVisibility(isEmpty ? View.GONE : View.VISIBLE);

        String storageLabel = UiErrorHelper.getStorageDisplayName(this, storageType);
        if (notes.size() == 1) {
            textStorageStatus.setText(getString(R.string.storage_status_one_format, storageLabel));
        } else {
            textStorageStatus.setText(getString(R.string.storage_status_format, notes.size(), storageLabel));
        }
    }

    private void setOperationInProgress(boolean inProgress) {
        operationInProgress = inProgress;
        progressMain.setVisibility(inProgress ? View.VISIBLE : View.GONE);
        btnSync.setEnabled(!inProgress);
        btnAddNote.setEnabled(!inProgress);
        radioSqlite.setEnabled(!inProgress);
        radioJson.setEnabled(!inProgress);
    }

    private void openEditor(String noteId) {
        launchingEditor = true;
        Intent intent = new Intent(this, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_STORAGE_TYPE, activeStorage.getPrefValue());
        if (noteId != null) {
            intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId);
        }
        startActivity(intent);
    }

    private void showError(StorageException error) {
        dismissActiveDialog();
        pendingErrorType = error.getErrorType();
        activeDialog = UiErrorHelper.showErrorDialog(this, error, () -> {
            if (suppressingDialogDismiss) {
                return;
            }
            activeDialog = null;
            pendingErrorType = null;
            if (!hasLoadedOnce && activeStorage != StorageType.JSON && !isFinishing() && !isDestroyed()) {
                loadNotes();
            }
        });
    }

    private void dismissActiveDialog() {
        if (activeDialog != null) {
            suppressingDialogDismiss = true;
            if (activeDialog.isShowing()) {
                activeDialog.dismiss();
            }
            suppressingDialogDismiss = false;
            activeDialog = null;
        }
    }
}
