package lt.mipt.notes.validation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NoteValidatorTest {

    @Test
    public void rejectsNullAndEmptyFields() {
        NoteValidator.ValidationResult result = NoteValidator.validate(null, "");
        assertFalse(result.isValid());
        assertFalse(result.isTitleValid());
        assertFalse(result.isContentValid());
    }

    @Test
    public void rejectsAsciiWhitespaceOnly() {
        NoteValidator.ValidationResult result = NoteValidator.validate("   \t\n  ", " \r\n ");
        assertFalse(result.isValid());
        assertFalse(result.isTitleValid());
        assertFalse(result.isContentValid());
    }

    @Test
    public void rejectsUnicodeSpacesAndZeroWidthCharacters() {
        String unicodeSpaces = "\u00A0\u2003\u2009\u3000\u200B\uFEFF";
        NoteValidator.ValidationResult result = NoteValidator.validate(unicodeSpaces, "Valid content");
        assertFalse(result.isValid());
        assertFalse(result.isTitleValid());
        assertTrue(result.isContentValid());

        NoteValidator.ValidationResult contentOnlySpaces = NoteValidator.validate("Valid title", unicodeSpaces);
        assertFalse(contentOnlySpaces.isValid());
        assertTrue(contentOnlySpaces.isTitleValid());
        assertFalse(contentOnlySpaces.isContentValid());
    }

    @Test
    public void trimsSurroundingUnicodeWhitespaceAndPreservesLithuanianText() {
        NoteValidator.ValidationResult result = NoteValidator.validate(
                "\u00A0 Pirkiniai ąčęėįšųūž \u2003",
                "\n\u3000Pienas, duona ir kava.\u00A0\n");

        assertTrue(result.isValid());
        assertEquals("Pirkiniai ąčęėįšųūž", result.getNormalizedTitle());
        assertEquals("Pienas, duona ir kava.", result.getNormalizedContent());
    }
}
