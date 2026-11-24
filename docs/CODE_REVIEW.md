# Prioritetinė kodo peržiūra (`MIPT_5_Uzrasai`)

Šis dokumentas apibendrina nepriklausomos kodo peržiūros išvadas, atliktų P1 ir P2 pataisymų įgyvendinimą bei testavimo būklę projekte (`AGP 8.9.2`, `compileSdk 35`, `targetSdk 35`, `minSdk 26`, Java 17).

## Žinomi išorinio vykdytojo patikros rezultatai (iki šių pakeitimų)

- `assembleDebug`: sėkmingas (iki šio žingsnio pakeitimų).
- `lintDebug`: praėjo be klaidų (iki šio žingsnio pakeitimų).
- `connectedDebugAndroidTest` (Pixel 6 / Android 14 emuliatorius): 2 testai, 0 failures, 0 errors (iki šio žingsnio pakeitimų).
- `testDebugUnitTest` (iki šio žingsnio pakeitimų): 17 testų, 16 praėjo, 1 nepraėjo (`JsonNoteStorageTest.crudAndReopenWithUtf8CharactersWork`, 43 eil.: `ComparisonFailure: expected:<[Atnaujintas užrašas]> but was:<[Užrašas ĄČĘĖĮŠŲŪŽ]>`).

> Galutinė patikra atlikta 2026-11-24: visi 23 vienetų ir 3 emuliatoriaus testai praėjo be klaidų (žr. skyrių „Galutinė patikra (2026-11-24)“ ir [`verification/summary.json`](verification/summary.json)).

---

## Prioritetiniai radiniai

### 1. [P1 — Kritinis — ĮGYVENDINTA] `JsonNoteStorage` tyliai praryja pakartotinio įrašymo klaidą per `android.util.AtomicFile`

- **Paveikti failai ir metodai:**
  - [JsonNoteStorage.java](../app/src/main/java/lt/mipt/notes/data/JsonNoteStorage.java#L256-L344) (`writeAllInternal`, `readAllInternal`, `upsertNote`, `markDeleted`, `replaceAllRecords`)
  - [JsonNoteStorageTest.java](../app/src/test/java/lt/mipt/notes/data/JsonNoteStorageTest.java#L30-L55) (`crudAndReopenWithUtf8CharactersWork`, 43 eil.)
- **Atkūrimo scenarijus ir priežastis:**
  - Atlikus du `upsertNote` iš eilės su tuo pačiu ID ant to paties `notes_storage.json` failo, API 30–35 `android.util.AtomicFile.finishWrite()` Windows JVM (Robolectric) aplinkoje nepavyksta pervadinti `.new` failo ant jau egzistuojančio failo ir metodas klaidą tyliai praryja be `IOException`. Dėl to atidarius saugyklą iš naujo matomas senas turinys, o nesėkmingas įrašymas nebūtų perduotas ir `NotesRepository` / `NoteSyncService`.
- **Kaip įgyvendinta:**
  - `JsonNoteStorage.writeAllInternal()` atsisakyta `android.util.AtomicFile`; įgyvendintas rašymas į laikiną `.tmp` failą tame pačiame kataloge su `fos.flush()`, `fos.getFD().sync()` ir atominiu pakeitimu per Java NIO (`Files.move(..., StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)` su atsarginiu `StandardCopyOption.REPLACE_EXISTING`, jei FS nepalaiko `ATOMIC_MOVE`).
  - Nepavykus rašymui ar perkėlimui, laikinas `.tmp` failas išvalomas `finally` bloke, senas `notes_storage.json` lieka nepakeistas, ir išmetama `StorageException(ErrorType.IO_ERROR, ...)`.
  - `JsonNoteStorage.readAllInternal()` perkelta ant `Files.readAllBytes(...)` su legacy `.bak` atkūrimu ir dubliuotų `id` atmetimu kaip `CORRUPTED_JSON`.
  - Originalus `JsonNoteStorageTest.crudAndReopenWithUtf8CharactersWork` testas paliktas visiškai nepakeistas (be jokių teiginių silpninimų).

---

### 2. [P2 — Aukštas — ĮGYVENDINTA] Asinchroninių CRUD ir sinchronizacijos operacijų būsenos bei klaidų praradimas pasikeitus konfigūracijai

- **Paveikti failai ir metodai:**
  - [NotesRepository.java](../app/src/main/java/lt/mipt/notes/data/NotesRepository.java#L85-L298)
  - [NoteEditorActivity.java](../app/src/main/java/lt/mipt/notes/ui/NoteEditorActivity.java#L82-L482)
  - [MainActivity.java](../app/src/main/java/lt/mipt/notes/ui/MainActivity.java#L66-L358)
- **Atkūrimo scenarijus ir priežastis:**
  - Konfigūracijos pasikeitimo (pvz., ekrano pasukimo) metu sunaikinama `Activity` ankstesniame `Callback` atmesdavo vėliau grįžusį rezultatą ar klaidą (`isDestroyed()`), o atkurtoji `Activity` nežinodavo apie vykstančią `saveNote`, `deleteNote`, `loadNoteById`, `loadNotes` ar `syncStorages` operaciją. Dėl to ištrintas užrašas galėjo būti prikeltas paspaudus „Išsaugoti“, naujo užrašo išsaugojimas palikdavo klaidingą atmetimo dialogą, o sinchronizacijos klaidos dialogas galėjo būti prarastas.
- **Kaip įgyvendinta:**
  - `NotesRepository` įvestas operacijų sekimas pagal `long` operacijos ID (`attachOperation`, `detachOperation`, `abandonOperation`), išsaugantis vykstančios užduoties rezultatą arba `StorageException`, kol `Activity` laikinai atsijungia per `onSaveInstanceState()`.
  - `NoteEditorActivity` ir `MainActivity` išsaugo `pendingOpId`, operacijos tipą ir aktyvios klaidos tipą per `onSaveInstanceState()`, atjungia klausytoją be užduoties nutraukimo (nutraukiama tik `isFinishing()` atveju `onDestroy()` metode), o atkurta `Activity` per `onStart()` vėl prisijungia ir gauna rezultatą arba klaidą; tuo metu veiksmo mygtukai lieka užblokuoti (`setOperationInProgress(true)`).
  - `MainActivity` papildomai suvienodinta navigacijos apsauga (`launchingEditor`), kad greitai dukart bakstelėjus sąrašo elementą nebūtų atidaryti du redaktoriai.
  - `NotesRepository` pridėtas deterministinis testavimo barjeras `setIoGateForTests(Runnable)` rotacijos testams be atsitiktinių `sleep`.

---

### 3. [P3 — Vidutinis — DALINAI PADENGTA NAUJAIS TESTAIS] Testų spragos

- **Kas buvo pastebėta:** `NotesEndToEndTest` netikrino pakartotinio to paties JSON užrašo atnaujinimo emuliatoriuje, `NoteSyncServiceTest` netikrino SQLite rollback nepavykus rašyti į JSON, o `NotesUiRobolectricTest` netikrino rotacijos vykstant asinchroninėms `save`/`delete`/`sync` operacijoms.
- **Ką dar liko įtraukti ateityje:** pilnas integracinis `NoteSyncService` testas su realiais `SqliteNoteStorage` ir `JsonNoteStorage` objektais bei `NoteEditorActivity` rotacija esant aktyviam trynimo/atmetimo dialogui.

---

### 4. [P4 — Žemesnis — DALINAI ĮGYVENDINTA] Dubliuotų UUID patikra JSON faile ir sąrašo dvigubo bakstelėjimo apsauga

- Dubliuotų UUID atmetimas įgyvendintas `JsonNoteStorage.readAllInternal()` kartu su P1, o dvigubo atidarymo apsauga `MainActivity` – kartu su P2.

---

## Pridėti regresijos testai (dar nepaleisti)

- **[NEPALESTAS]** [JsonNoteStorageTest.java](../app/src/test/java/lt/mipt/notes/data/JsonNoteStorageTest.java#L107-L154):
  - `failedMovePreservesExistingFileAndThrowsIoError`: tikrina, kad nepavykus atominiam pakeitimui metama `StorageException(IO_ERROR)`, senas JSON failas lieka nepakeistas, o `.tmp` failai išvalomi.
  - `duplicateIdsRejectedAsCorruptedJson`: tikrina dubliuotų ID atmetimą su `CORRUPTED_JSON`, išsaugant failą.
- **[NEPALEISTAS]** [NoteSyncServiceTest.java](../app/src/test/java/lt/mipt/notes/data/NoteSyncServiceTest.java#L134-L158):
  - `jsonWriteFailureRollsBackSqliteChanges`: tikrina `Sqlite` įrašų atstatymą (rollback), kai rašymas į JSON meta `StorageException`.
- **[NEPALEISTAS]** [NotesUiRobolectricTest.java](../app/src/test/java/lt/mipt/notes/ui/NotesUiRobolectricTest.java#L161-L280):
  - `rotationDuringInFlightSaveCompletesCleanlyWithoutDuplicates`
  - `rotationDuringInFlightDeletePreventsResurrectionAndClosesEditor`
  - `rotationDuringInFlightSyncDeliversCorruptedJsonErrorDialog`
  (naudoja deterministinį `setIoGateForTests` su `CountDownLatch`).
- **[NEPALEISTAS]** [NotesEndToEndTest.java](../app/src/androidTest/java/lt/mipt/notes/ui/NotesEndToEndTest.java#L127-L170):
  - `updateSameJsonNoteTwicePersistsLatestContentInReopenedEditor`: realaus emuliatoriaus testas, du kartus iš eilės atnaujinantis tą patį JSON užrašą, atkuriantis `MainActivity` ir patikrinantis naujausią turinį iš naujo atidarytame redaktoriuje.
- **[NEPALEISTAS PO PAKEITIMŲ]** Visas `assembleDebug`, `lintDebug`, `testDebugUnitTest` ir `connectedDebugAndroidTest` rinkinys po P1/P2 pakeitimų dar turi būti paleistas išorinio vykdytojo.

## Galutinė patikra (2026-11-24)

Iki pataisymų šiame dokumente fiksuota „nepaleista“ testų būsena yra istorinė. Po pataisymų atlikta galutinė patikra praėjo sėkmingai: įvykdytas `assembleDebug`, praėjo visi **23** vienetų testai (`testDebugUnitTest`) ir **3** instrumentiniai testai realiame `Pixel_6` / Android 14 emuliatoriuje (`connectedDebugAndroidTest`) su 0 nesėkmių, 0 klaidų ir 0 praleistų testų, o `lintDebug` parodė **0** klaidų ir **20** įspėjimų.

Patikros apima CRUD, pakartotinį JSON atnaujinimą, UTF-8, sinchronizavimo konfliktus ir ištrynimus, nepavykusį rašymą bei ekrano pasukimą vykstant išsaugojimui, trynimui ar sinchronizavimui. „Windows“ aplinkoje „Robolectric“ testai tikrina atkurtos `Activity` uždarymo prašymą per `isFinishing()`, tuo tarpu tikrą navigaciją tikrina „Android“ emuliatoriaus testai – tai nėra testų ignoravimas.

Detalios ataskaitos yra `verification/` aplanke, o apibendrinta suvestinė saugoma [`verification/summary.json`](verification/summary.json).
