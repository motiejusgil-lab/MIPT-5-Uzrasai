package lt.mipt.notes.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.UUID;

import lt.mipt.notes.NotesApplication;
import lt.mipt.notes.R;
import lt.mipt.notes.data.NotesRepository;
import lt.mipt.notes.data.StorageException;
import lt.mipt.notes.model.Note;
import lt.mipt.notes.model.StorageType;
import lt.mipt.notes.validation.NoteValidator;

/**
 * Secondary screen for creating, viewing, editing, and deleting a single note.
 * Preserves unsaved drafts across configuration changes and prevents duplicate creation.
 */
public class NoteEditorActivity extends Activity {

    public static final String EXTRA_NOTE_ID = "extra_note_id";
    public static final String EXTRA_STORAGE_TYPE = "extra_storage_type";

    private static final String STATE_NOTE_ID = "state_note_id";
    private static final String STATE_EDIT_MODE = "state_edit_mode";
    private static final String STATE_STORAGE_TYPE = "state_storage_type";
    private static final String STATE_INITIAL_TITLE = "state_initial_title";
    private static final String STATE_INITIAL_CONTENT = "state_initial_content";
    private static final String STATE_UPDATED_AT = "state_updated_at";
    private static final String STATE_DATA_LOADED = "state_data_loaded";
    private static final String STATE_PENDING_DELETE_DIALOG = "state_pending_delete_dialog";
    private static final String STATE_PENDING_DISCARD_DIALOG = "state_pending_discard_dialog";
    private static final String STATE_PENDING_OP_ID = "state_pending_op_id";
    private static final String STATE_PENDING_OP_TYPE = "state_pending_op_type";
    private static final String STATE_PENDING_ERROR_TYPE = "state_pending_error_type";
    private static final String STATE_PENDING_ERROR_FINISH = "state_pending_error_finish";

    private static final int OP_NONE = 0;
    private static final int OP_LOAD = 1;
    private static final int OP_SAVE = 2;
    private static final int OP_DELETE = 3;

    private NotesRepository repository;

    private TextView textHeader;
    private TextView textStorageChip;
    private TextView textUpdatedAt;
    private ProgressBar progressEditor;
    private EditText editTitle;
    private EditText editContent;
    private Button btnBack;
    private Button btnSave;
    private Button btnDelete;

    private String noteId;
    private boolean editMode;
    private StorageType storageType;
    private String initialTitle = "";
    private String initialContent = "";
    private long noteUpdatedAt;
    private boolean dataLoaded;
    private boolean operationInProgress;
    private boolean pendingDeleteDialog;
    private boolean pendingDiscardDialog;
    private long pendingOpId = NotesRepository.NO_OPERATION;
    private int pendingOpType = OP_NONE;
    private StorageException.ErrorType pendingErrorType;
    private boolean pendingErrorFinish;
    private boolean suppressingDialogDismiss;
    private AlertDialog activeDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_note_editor);

        repository = ((NotesApplication) getApplication()).getRepository();

        View root = findViewById(R.id.root_editor);
        View header = findViewById(R.id.header_editor);
        View bottomBar = findViewById(R.id.bottom_bar_editor);
        WindowInsetsHelper.applyEdgeToEdgePadding(root, header, bottomBar);

        textHeader = findViewById(R.id.text_editor_header);
        textStorageChip = findViewById(R.id.text_editor_storage_chip);
        textUpdatedAt = findViewById(R.id.text_editor_updated);
        progressEditor = findViewById(R.id.progress_editor);
        editTitle = findViewById(R.id.edit_note_title);
        editContent = findViewById(R.id.edit_note_content);
        btnBack = findViewById(R.id.btn_back);
        btnSave = findViewById(R.id.btn_save_note);
        btnDelete = findViewById(R.id.btn_delete_note);

        if (savedInstanceState != null) {
            noteId = savedInstanceState.getString(STATE_NOTE_ID);
            editMode = savedInstanceState.getBoolean(STATE_EDIT_MODE, false);
            storageType = StorageType.fromPrefValue(savedInstanceState.getString(STATE_STORAGE_TYPE));
            initialTitle = savedInstanceState.getString(STATE_INITIAL_TITLE, "");
            initialContent = savedInstanceState.getString(STATE_INITIAL_CONTENT, "");
            noteUpdatedAt = savedInstanceState.getLong(STATE_UPDATED_AT, 0L);
            dataLoaded = savedInstanceState.getBoolean(STATE_DATA_LOADED, false);
            pendingDeleteDialog = savedInstanceState.getBoolean(STATE_PENDING_DELETE_DIALOG, false);
            pendingDiscardDialog = savedInstanceState.getBoolean(STATE_PENDING_DISCARD_DIALOG, false);
            pendingOpId = savedInstanceState.getLong(STATE_PENDING_OP_ID, NotesRepository.NO_OPERATION);
            pendingOpType = savedInstanceState.getInt(STATE_PENDING_OP_TYPE, OP_NONE);
            String savedErrorName = savedInstanceState.getString(STATE_PENDING_ERROR_TYPE);
            if (savedErrorName != null) {
                try {
                    pendingErrorType = StorageException.ErrorType.valueOf(savedErrorName);
                } catch (IllegalArgumentException ignored) {
                    pendingErrorType = null;
                }
            }
            pendingErrorFinish = savedInstanceState.getBoolean(STATE_PENDING_ERROR_FINISH, false);
        } else {
            String extraId = getIntent().getStringExtra(EXTRA_NOTE_ID);
            editMode = extraId != null && !extraId.isEmpty();
            noteId = editMode ? extraId : UUID.randomUUID().toString();
            String extraStorage = getIntent().getStringExtra(EXTRA_STORAGE_TYPE);
            storageType = extraStorage != null
                    ? StorageType.fromPrefValue(extraStorage)
                    : repository.getActiveStorage();
            dataLoaded = !editMode;
        }

        configureHeaderAndMetadata();
        attachErrorClearingWatcher(editTitle);
        attachErrorClearingWatcher(editContent);

        btnBack.setOnClickListener(v -> handleExitAttempt());
        btnSave.setOnClickListener(v -> attemptSave());
        btnDelete.setOnClickListener(v -> showDeleteConfirmationDialog());

        if (pendingOpId != NotesRepository.NO_OPERATION && pendingOpType != OP_NONE) {
            setOperationInProgress(true);
        } else if (editMode && !dataLoaded) {
            loadExistingNote();
        } else if (pendingDeleteDialog) {
            showDeleteConfirmationDialog();
        } else if (pendingDiscardDialog) {
            showDiscardChangesDialog();
        } else if (pendingErrorType != null) {
            showError(new StorageException(pendingErrorType, "Restored storage error"), pendingErrorFinish);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        reattachPendingOperation();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        repository.detachOperation(pendingOpId);
        outState.putString(STATE_NOTE_ID, noteId);
        outState.putBoolean(STATE_EDIT_MODE, editMode);
        outState.putString(STATE_STORAGE_TYPE, storageType.getPrefValue());
        outState.putString(STATE_INITIAL_TITLE, initialTitle);
        outState.putString(STATE_INITIAL_CONTENT, initialContent);
        outState.putLong(STATE_UPDATED_AT, noteUpdatedAt);
        outState.putBoolean(STATE_DATA_LOADED, dataLoaded);
        outState.putBoolean(STATE_PENDING_DELETE_DIALOG, pendingDeleteDialog);
        outState.putBoolean(STATE_PENDING_DISCARD_DIALOG, pendingDiscardDialog);
        outState.putLong(STATE_PENDING_OP_ID, pendingOpId);
        outState.putInt(STATE_PENDING_OP_TYPE, pendingOpType);
        outState.putString(STATE_PENDING_ERROR_TYPE, pendingErrorType != null ? pendingErrorType.name() : null);
        outState.putBoolean(STATE_PENDING_ERROR_FINISH, pendingErrorFinish);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            repository.abandonOperation(pendingOpId);
        }
        dismissActiveDialog();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleExitAttempt();
    }

    private void configureHeaderAndMetadata() {
        textHeader.setText(editMode ? R.string.editor_title_edit : R.string.editor_title_new);
        String storageName = UiErrorHelper.getStorageDisplayName(this, storageType);
        textStorageChip.setText(getString(R.string.editor_storage_chip_format, storageName));
        btnDelete.setVisibility(editMode ? View.VISIBLE : View.GONE);
        if (editMode && noteUpdatedAt > 0L) {
            textUpdatedAt.setText(getString(
                    R.string.updated_at_format,
                    UiErrorHelper.formatTimestamp(noteUpdatedAt)));
            textUpdatedAt.setVisibility(View.VISIBLE);
        } else {
            textUpdatedAt.setVisibility(View.GONE);
        }
    }

    private void reattachPendingOperation() {
        if (pendingOpId == NotesRepository.NO_OPERATION || pendingOpType == OP_NONE) {
            return;
        }
        final boolean attached;
        switch (pendingOpType) {
            case OP_LOAD:
                attached = repository.attachOperation(pendingOpId, createLoadCallback());
                break;
            case OP_SAVE:
                attached = repository.attachOperation(pendingOpId, createSaveCallback());
                break;
            case OP_DELETE:
                attached = repository.attachOperation(pendingOpId, createDeleteCallback());
                break;
            default:
                attached = false;
                break;
        }
        if (!attached) {
            clearPendingOperation();
            setOperationInProgress(false);
            if (editMode && !dataLoaded) {
                loadExistingNote();
            }
        }
    }

    private void clearPendingOperation() {
        pendingOpId = NotesRepository.NO_OPERATION;
        pendingOpType = OP_NONE;
    }

    private void loadExistingNote() {
        setOperationInProgress(true);
        pendingOpType = OP_LOAD;
        pendingOpId = repository.loadNoteById(storageType, noteId, createLoadCallback());
    }

    private NotesRepository.Callback<Note> createLoadCallback() {
        return new NotesRepository.Callback<>() {
            @Override
            public void onSuccess(Note note) {
                clearPendingOperation();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setOperationInProgress(false);
                initialTitle = note.getTitle();
                initialContent = note.getContent();
                noteUpdatedAt = note.getUpdatedAt();
                dataLoaded = true;
                editTitle.setText(note.getTitle());
                editContent.setText(note.getContent());
                configureHeaderAndMetadata();
            }

            @Override
            public void onError(StorageException error) {
                clearPendingOperation();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setOperationInProgress(false);
                showError(error, true);
            }
        };
    }

    private void attemptSave() {
        if (operationInProgress || !dataLoaded) {
            return;
        }
        editTitle.setError(null);
        editContent.setError(null);

        NoteValidator.ValidationResult validation = NoteValidator.validate(
                editTitle.getText().toString(),
                editContent.getText().toString());
        if (!validation.isValid()) {
            if (!validation.isTitleValid()) {
                editTitle.setError(getString(R.string.error_title_required));
            }
            if (!validation.isContentValid()) {
                editContent.setError(getString(R.string.error_content_required));
            }
            if (!validation.isTitleValid()) {
                editTitle.requestFocus();
            } else {
                editContent.requestFocus();
            }
            return;
        }

        setOperationInProgress(true);
        pendingOpType = OP_SAVE;
        pendingOpId = repository.saveNote(
                storageType,
                noteId,
                validation.getNormalizedTitle(),
                validation.getNormalizedContent(),
                createSaveCallback());
    }

    private NotesRepository.Callback<Note> createSaveCallback() {
        return new NotesRepository.Callback<>() {
            @Override
            public void onSuccess(Note savedNote) {
                clearPendingOperation();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setOperationInProgress(false);
                initialTitle = savedNote.getTitle();
                initialContent = savedNote.getContent();
                noteUpdatedAt = savedNote.getUpdatedAt();
                editMode = true;
                Toast.makeText(
                        NoteEditorActivity.this,
                        R.string.msg_note_saved,
                        Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onError(StorageException error) {
                clearPendingOperation();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setOperationInProgress(false);
                showError(error, false);
            }
        };
    }

    private void showDeleteConfirmationDialog() {
        if (!editMode || operationInProgress || !dataLoaded) {
            return;
        }
        dismissActiveDialog();
        pendingDeleteDialog = true;
        activeDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_delete_title)
                .setMessage(R.string.dialog_delete_message)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    pendingDeleteDialog = false;
                    performDelete();
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> {
                    pendingDeleteDialog = false;
                    dialog.dismiss();
                })
                .setOnCancelListener(dialog -> pendingDeleteDialog = false)
                .create();
        activeDialog.show();
    }

    private void performDelete() {
        if (operationInProgress) {
            return;
        }
        setOperationInProgress(true);
        pendingOpType = OP_DELETE;
        pendingOpId = repository.deleteNote(storageType, noteId, createDeleteCallback());
    }

    private NotesRepository.Callback<Void> createDeleteCallback() {
        return new NotesRepository.Callback<>() {
            @Override
            public void onSuccess(Void result) {
                clearPendingOperation();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setOperationInProgress(false);
                Toast.makeText(
                        NoteEditorActivity.this,
                        R.string.msg_note_deleted,
                        Toast.LENGTH_SHORT).show();
                finish();
            }

            @Override
            public void onError(StorageException error) {
                clearPendingOperation();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                setOperationInProgress(false);
                showError(error, false);
            }
        };
    }

    private void handleExitAttempt() {
        if (operationInProgress) {
            return;
        }
        if (hasUnsavedChanges()) {
            showDiscardChangesDialog();
        } else {
            finish();
        }
    }

    private boolean hasUnsavedChanges() {
        if (!dataLoaded) {
            return false;
        }
        String currentTitle = editTitle.getText().toString();
        String currentContent = editContent.getText().toString();
        return !currentTitle.equals(initialTitle) || !currentContent.equals(initialContent);
    }

    private void showDiscardChangesDialog() {
        dismissActiveDialog();
        pendingDiscardDialog = true;
        activeDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_discard_title)
                .setMessage(R.string.dialog_discard_message)
                .setPositiveButton(R.string.action_discard, (dialog, which) -> {
                    pendingDiscardDialog = false;
                    finish();
                })
                .setNegativeButton(R.string.action_stay, (dialog, which) -> {
                    pendingDiscardDialog = false;
                    dialog.dismiss();
                })
                .setOnCancelListener(dialog -> pendingDiscardDialog = false)
                .create();
        activeDialog.show();
    }

    private void setOperationInProgress(boolean inProgress) {
        operationInProgress = inProgress;
        progressEditor.setVisibility(inProgress ? View.VISIBLE : View.GONE);
        btnSave.setEnabled(!inProgress);
        btnDelete.setEnabled(!inProgress);
        btnBack.setEnabled(!inProgress);
        editTitle.setEnabled(!inProgress);
        editContent.setEnabled(!inProgress);
    }

    private void showError(StorageException error, boolean finishOnDismiss) {
        dismissActiveDialog();
        pendingErrorType = error.getErrorType();
        pendingErrorFinish = finishOnDismiss;
        activeDialog = UiErrorHelper.showErrorDialog(this, error, () -> {
            if (suppressingDialogDismiss) {
                return;
            }
            activeDialog = null;
            pendingErrorType = null;
            pendingErrorFinish = false;
            if (finishOnDismiss && !isFinishing() && !isDestroyed()) {
                finish();
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

    private static void attachErrorClearingWatcher(EditText field) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (field.getError() != null) {
                    field.setError(null);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }
}
