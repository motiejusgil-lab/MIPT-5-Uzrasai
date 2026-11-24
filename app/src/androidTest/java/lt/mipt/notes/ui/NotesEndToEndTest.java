package lt.mipt.notes.ui;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.espresso.Espresso;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import lt.mipt.notes.NotesApplication;
import lt.mipt.notes.R;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class NotesEndToEndTest {

    private NotesApplication app;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        app.resetForTests();
    }

    @Test
    public void createEditSyncAndDeleteNoteAcrossStorages() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitForAppIdle();
            onView(withId(R.id.radio_sqlite)).check(matches(isChecked()));
            onView(withId(R.id.empty_state)).check(matches(isDisplayed()));

            // Open editor and verify blank input validation keeps us in editor.
            onView(withId(R.id.btn_add_note)).perform(click());
            onView(withId(R.id.edit_note_title)).perform(replaceText("   "), closeSoftKeyboard());
            onView(withId(R.id.btn_save_note)).perform(click());
            onView(withId(R.id.edit_note_title)).check(matches(isDisplayed()));

            // Create SQLite note.
            onView(withId(R.id.edit_note_title)).perform(replaceText("SQLite užrašas"), closeSoftKeyboard());
            onView(withId(R.id.edit_note_content)).perform(replaceText("Pirminis turinys"), closeSoftKeyboard());
            onView(withId(R.id.btn_save_note)).perform(click());
            waitForAppIdle();

            onView(withText("SQLite užrašas")).check(matches(isDisplayed()));

            // View & Edit the note.
            onView(withText("SQLite užrašas")).perform(click());
            waitForAppIdle();
            onView(withId(R.id.edit_note_title)).perform(replaceText("SQLite redaguotas"), closeSoftKeyboard());
            onView(withId(R.id.btn_save_note)).perform(click());
            waitForAppIdle();

            onView(withText("SQLite redaguotas")).check(matches(isDisplayed()));

            // Switch to JSON storage; SQLite note should not be shown before sync.
            onView(withId(R.id.radio_json)).perform(click());
            waitForAppIdle();
            onView(withId(R.id.empty_state)).check(matches(isDisplayed()));

            // Create a JSON note.
            onView(withId(R.id.btn_add_note)).perform(click());
            onView(withId(R.id.edit_note_title)).perform(replaceText("JSON užrašas"), closeSoftKeyboard());
            onView(withId(R.id.edit_note_content)).perform(replaceText("Antrinės saugyklos tekstas"), closeSoftKeyboard());
            onView(withId(R.id.btn_save_note)).perform(click());
            waitForAppIdle();

            onView(withText("JSON užrašas")).check(matches(isDisplayed()));

            // Sync both storages and verify both notes exist in JSON and SQLite.
            onView(withId(R.id.btn_sync)).perform(click());
            waitForAppIdle();
            onView(withText("SQLite redaguotas")).check(matches(isDisplayed()));
            onView(withText("JSON užrašas")).check(matches(isDisplayed()));

            onView(withId(R.id.radio_sqlite)).perform(click());
            waitForAppIdle();
            onView(withText("SQLite redaguotas")).check(matches(isDisplayed()));
            onView(withText("JSON užrašas")).check(matches(isDisplayed()));

            // Delete one note via confirmation dialog.
            onView(withText("JSON užrašas")).perform(click());
            waitForAppIdle();
            onView(withId(R.id.btn_delete_note)).perform(click());
            onView(withText(R.string.action_delete)).inRoot(isDialog()).perform(click());
            waitForAppIdle();

            onView(withText("JSON užrašas")).check(doesNotExist());
            onView(withText("SQLite redaguotas")).check(matches(isDisplayed()));
        }
    }

    @Test
    public void unsavedDraftPromptsBeforeLeavingEditor() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitForAppIdle();
            onView(withId(R.id.btn_add_note)).perform(click());

            onView(withId(R.id.edit_note_title)).perform(replaceText("Nebaigtas juodraštis"), closeSoftKeyboard());
            Espresso.pressBack();

            onView(withText(R.string.dialog_discard_title)).inRoot(isDialog()).check(matches(isDisplayed()));
            onView(withText(R.string.action_stay)).inRoot(isDialog()).perform(click());
            onView(withId(R.id.edit_note_title)).check(matches(withText("Nebaigtas juodraštis")));

            Espresso.pressBack();
            onView(withText(R.string.action_discard)).inRoot(isDialog()).perform(click());
            waitForAppIdle();

            onView(withId(R.id.empty_state)).check(matches(isDisplayed()));
        }
    }

    @Test
    public void updateSameJsonNoteTwicePersistsLatestContentInReopenedEditor() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            waitForAppIdle();

            onView(withId(R.id.radio_json)).perform(click());
            waitForAppIdle();

            // Create initial JSON note.
            onView(withId(R.id.btn_add_note)).perform(click());
            onView(withId(R.id.edit_note_title)).perform(replaceText("JSON pradinis"), closeSoftKeyboard());
            onView(withId(R.id.edit_note_content)).perform(replaceText("Pirmas turinys"), closeSoftKeyboard());
            onView(withId(R.id.btn_save_note)).perform(click());
            waitForAppIdle();
            onView(withText("JSON pradinis")).check(matches(isDisplayed()));

            // First update of the same JSON note.
            onView(withText("JSON pradinis")).perform(click());
            waitForAppIdle();
            onView(withId(R.id.edit_note_title)).perform(replaceText("JSON antras"), closeSoftKeyboard());
            onView(withId(R.id.edit_note_content)).perform(replaceText("Antras turinys"), closeSoftKeyboard());
            onView(withId(R.id.btn_save_note)).perform(click());
            waitForAppIdle();
            onView(withText("JSON antras")).check(matches(isDisplayed()));

            // Second consecutive update of the same JSON note.
            onView(withText("JSON antras")).perform(click());
            waitForAppIdle();
            onView(withId(R.id.edit_note_title)).perform(replaceText("JSON galutinis"), closeSoftKeyboard());
            onView(withId(R.id.edit_note_content)).perform(replaceText("Trečias turinys"), closeSoftKeyboard());
            onView(withId(R.id.btn_save_note)).perform(click());
            waitForAppIdle();
            onView(withText("JSON galutinis")).check(matches(isDisplayed()));

            // Recreate MainActivity and reopen the note in editor to verify persisted latest content.
            scenario.recreate();
            waitForAppIdle();
            onView(withId(R.id.radio_json)).check(matches(isChecked()));
            onView(withText("JSON galutinis")).perform(click());
            waitForAppIdle();

            onView(withId(R.id.edit_note_title)).check(matches(withText("JSON galutinis")));
            onView(withId(R.id.edit_note_content)).check(matches(withText("Trečias turinys")));
        }
    }

    private void waitForAppIdle() {
        app.getRepository().waitForIoForTests();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        app.getRepository().waitForIoForTests();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
