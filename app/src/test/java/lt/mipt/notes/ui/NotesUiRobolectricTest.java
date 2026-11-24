package lt.mipt.notes.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioButton;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlertDialog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import lt.mipt.notes.NotesApplication;
import lt.mipt.notes.R;
import lt.mipt.notes.data.JsonNoteStorage;
import lt.mipt.notes.data.NotesRepository;
import lt.mipt.notes.data.StorageException;
import lt.mipt.notes.model.Note;
import lt.mipt.notes.model.StorageType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class NotesUiRobolectricTest {

    private NotesApplication app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.resetForTests();
    }

    @After
    public void tearDown() {
        app.getRepository().setIoGateForTests(null);
    }

    @Test
    public void selectedStoragePersistsAcrossRecreation() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            flushIoAndLooper();
            scenario.onActivity(activity -> {
                RadioButton radioJson = activity.findViewById(R.id.radio_json);
                radioJson.performClick();
            });
            flushIoAndLooper();

            assertEquals(StorageType.JSON, app.getRepository().getActiveStorage());

            scenario.recreate();
            flushIoAndLooper();

            scenario.onActivity(activity -> {
                RadioButton radioJson = activity.findViewById(R.id.radio_json);
                assertTrue(radioJson.isChecked());
            });
        }
    }

    @Test
    public void rotationPreservesDraftAndPreventsDuplicateNotesOnSave() {
        Intent intent = new Intent(app, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_STORAGE_TYPE, StorageType.SQLITE.getPrefValue());

        try (ActivityScenario<NoteEditorActivity> editorScenario = ActivityScenario.launch(intent)) {
            editorScenario.onActivity(activity -> {
                EditText title = activity.findViewById(R.id.edit_note_title);
                EditText content = activity.findViewById(R.id.edit_note_content);
                title.setText("Juodraštis");
                content.setText("Tekstas po pasukimo");
            });

            editorScenario.recreate();

            editorScenario.onActivity(activity -> {
                EditText title = activity.findViewById(R.id.edit_note_title);
                EditText content = activity.findViewById(R.id.edit_note_content);
                assertEquals("Juodraštis", title.getText().toString());
                assertEquals("Tekstas po pasukimo", content.getText().toString());

                Button save = activity.findViewById(R.id.btn_save_note);
                save.performClick();
                save.performClick();
            });
            flushIoAndLooper();
        }

        try (ActivityScenario<MainActivity> mainScenario = ActivityScenario.launch(MainActivity.class)) {
            flushIoAndLooper();
            mainScenario.onActivity(activity -> {
                ListView listView = activity.findViewById(R.id.list_notes);
                assertEquals(1, listView.getCount());
            });
        }
    }

    @Test
    public void validationRejectsUnicodeSpacesInEditor() {
        try (ActivityScenario<NoteEditorActivity> scenario = ActivityScenario.launch(NoteEditorActivity.class)) {
            scenario.onActivity(activity -> {
                EditText title = activity.findViewById(R.id.edit_note_title);
                EditText content = activity.findViewById(R.id.edit_note_content);
                title.setText("\u00A0\u2003");
                content.setText("   ");
                activity.findViewById(R.id.btn_save_note).performClick();

                assertNotNull(title.getError());
                assertNotNull(content.getError());
                assertNull(ShadowAlertDialog.getLatestAlertDialog());
            });
        }
    }

    @Test
    public void corruptedJsonShowsErrorDialogAndPreservesCorruptedFile() throws Exception {
        File jsonFile = new File(app.getFilesDir(), JsonNoteStorage.DEFAULT_FILE_NAME);
        String corrupted = "{ invalid json";
        Files.write(jsonFile.toPath(), corrupted.getBytes(StandardCharsets.UTF_8));

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            flushIoAndLooper();
            scenario.onActivity(activity -> {
                RadioButton radioJson = activity.findViewById(R.id.radio_json);
                radioJson.performClick();
            });
            flushIoAndLooper();

            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());

            String fileAfterError = new String(Files.readAllBytes(jsonFile.toPath()), StandardCharsets.UTF_8);
            assertEquals(corrupted, fileAfterError);
        }
    }

    @Test
    public void rotationDuringInFlightSaveCompletesCleanlyWithoutDuplicates() throws Exception {
        Intent intent = new Intent(app, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_STORAGE_TYPE, StorageType.SQLITE.getPrefValue());

        CountDownLatch gateEntered = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);

        try (ActivityScenario<NoteEditorActivity> editorScenario = ActivityScenario.launch(intent)) {
            editorScenario.onActivity(activity -> {
                EditText title = activity.findViewById(R.id.edit_note_title);
                EditText content = activity.findViewById(R.id.edit_note_content);
                title.setText("Išsaugomas per pasukimą");
                content.setText("Turinys be dublikatų");
            });

            installGate(gateEntered, releaseGate);
            editorScenario.onActivity(activity -> activity.findViewById(R.id.btn_save_note).performClick());
            assertTrue("Save I/O did not start in time", gateEntered.await(5, TimeUnit.SECONDS));

            editorScenario.recreate();
            editorScenario.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.btn_save_note).isEnabled());
                assertFalse(activity.findViewById(R.id.btn_back).isEnabled());
            });

            app.getRepository().setIoGateForTests(null);
            releaseGate.countDown();
            flushIoAndLooper();

            java.util.concurrent.atomic.AtomicBoolean isFinishing = new java.util.concurrent.atomic.AtomicBoolean(false);
            editorScenario.onActivity(activity -> isFinishing.set(activity.isFinishing()));
            org.junit.Assert.assertTrue(
             "Naujoji Activity po recreate() ir foninės operacijos pabaigos turi iškviesti finish()",
             isFinishing.get());
        }

        List<Note> saved = loadNotesSync(StorageType.SQLITE);
        assertEquals(1, saved.size());
        assertEquals("Išsaugomas per pasukimą", saved.get(0).getTitle());
    }

    @Test
    public void rotationDuringInFlightDeletePreventsResurrectionAndClosesEditor() throws Exception {
        app.getRepository().saveNote(
                StorageType.SQLITE,
                "del-rot-1",
                "Trinamas užrašas",
                "Neturi prisikelti",
                new NotesRepository.Callback<>() {
                    @Override
                    public void onSuccess(Note result) {
                    }

                    @Override
                    public void onError(StorageException error) {
                        throw new AssertionError(error);
                    }
                });
        flushIoAndLooper();

        Intent intent = new Intent(app, NoteEditorActivity.class);
        intent.putExtra(NoteEditorActivity.EXTRA_STORAGE_TYPE, StorageType.SQLITE.getPrefValue());
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, "del-rot-1");

        CountDownLatch gateEntered = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);

        try (ActivityScenario<NoteEditorActivity> editorScenario = ActivityScenario.launch(intent)) {
            flushIoAndLooper();

            editorScenario.onActivity(activity -> {
                activity.findViewById(R.id.btn_delete_note).performClick();
                AlertDialog confirmDialog = ShadowAlertDialog.getLatestAlertDialog();
                assertNotNull(confirmDialog);
                assertTrue(confirmDialog.isShowing());

                installGate(gateEntered, releaseGate);
                confirmDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            });
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertTrue("Delete I/O did not start in time", gateEntered.await(5, TimeUnit.SECONDS));

            editorScenario.recreate();
            editorScenario.onActivity(activity -> {
                assertFalse(activity.findViewById(R.id.btn_save_note).isEnabled());
                assertFalse(activity.findViewById(R.id.btn_delete_note).isEnabled());
            });

            app.getRepository().setIoGateForTests(null);
            releaseGate.countDown();
            flushIoAndLooper();

            java.util.concurrent.atomic.AtomicBoolean isFinishing = new java.util.concurrent.atomic.AtomicBoolean(false);
            editorScenario.onActivity(activity -> isFinishing.set(activity.isFinishing()));
            org.junit.Assert.assertTrue(
             "Naujoji Activity po recreate() ir foninės operacijos pabaigos turi iškviesti finish()",
             isFinishing.get());
        }

        assertTrue(loadNotesSync(StorageType.SQLITE).isEmpty());
    }

    @Test
    public void rotationDuringInFlightSyncDeliversCorruptedJsonErrorDialog() throws Exception {
        File jsonFile = new File(app.getFilesDir(), JsonNoteStorage.DEFAULT_FILE_NAME);
        Files.write(jsonFile.toPath(), "{ corrupted".getBytes(StandardCharsets.UTF_8));

        CountDownLatch gateEntered = new CountDownLatch(1);
        CountDownLatch releaseGate = new CountDownLatch(1);

        try (ActivityScenario<MainActivity> mainScenario = ActivityScenario.launch(MainActivity.class)) {
            flushIoAndLooper();
            assertNull(ShadowAlertDialog.getLatestAlertDialog());

            installGate(gateEntered, releaseGate);
            mainScenario.onActivity(activity -> activity.findViewById(R.id.btn_sync).performClick());
            assertTrue("Sync I/O did not start in time", gateEntered.await(5, TimeUnit.SECONDS));

            mainScenario.recreate();
            mainScenario.onActivity(activity -> assertFalse(activity.findViewById(R.id.btn_sync).isEnabled()));

            app.getRepository().setIoGateForTests(null);
            releaseGate.countDown();
            flushIoAndLooper();

            AlertDialog errorDialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(errorDialog);
            assertTrue(errorDialog.isShowing());
            mainScenario.onActivity(activity -> assertTrue(activity.findViewById(R.id.btn_sync).isEnabled()));
        }
    }

    private void installGate(CountDownLatch gateEntered, CountDownLatch releaseGate) {
        app.getRepository().setIoGateForTests(() -> {
            gateEntered.countDown();
            try {
                if (!releaseGate.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for test I/O gate release");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for test I/O gate release", e);
            }
        });
    }

    private List<Note> loadNotesSync(StorageType storageType) {
        AtomicReference<List<Note>> loaded = new AtomicReference<>();
        app.getRepository().loadNotes(storageType, new NotesRepository.Callback<>() {
            @Override
            public void onSuccess(List<Note> result) {
                loaded.set(result);
            }

            @Override
            public void onError(StorageException error) {
                throw new AssertionError(error);
            }
        });
        flushIoAndLooper();
        return loaded.get();
    }

    private void flushIoAndLooper() {
        app.getRepository().waitForIoForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
