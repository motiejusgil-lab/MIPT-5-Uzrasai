package lt.mipt.notes.validation;

/**
 * Validates and normalizes note inputs, rejecting empty strings and Unicode whitespace-only text.
 */
public final class NoteValidator {

    private NoteValidator() {
    }

    public static ValidationResult validate(String rawTitle, String rawContent) {
        String normalizedTitle = stripUnicode(rawTitle);
        String normalizedContent = stripUnicode(rawContent);
        boolean titleValid = !normalizedTitle.isEmpty();
        boolean contentValid = !normalizedContent.isEmpty();
        return new ValidationResult(titleValid, contentValid, normalizedTitle, normalizedContent);
    }

    public static boolean isBlankUnicode(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        int length = value.length();
        for (int offset = 0; offset < length; ) {
            int codePoint = value.codePointAt(offset);
            if (!isUnicodeWhitespace(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    public static String stripUnicode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int length = value.length();
        int start = 0;
        while (start < length) {
            int cp = value.codePointAt(start);
            if (!isUnicodeWhitespace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        if (start >= length) {
            return "";
        }
        int end = length;
        while (end > start) {
            int cp = value.codePointBefore(end);
            if (!isUnicodeWhitespace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        // Character.isWhitespace excludes NBSP/figure/narrow-nbsp, so check isSpaceChar and format spaces too.
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || codePoint == '\u200B'
                || codePoint == '\u200C'
                || codePoint == '\u200D'
                || codePoint == '\u2060'
                || codePoint == '\uFEFF';
    }

    public static final class ValidationResult {
        private final boolean titleValid;
        private final boolean contentValid;
        private final String normalizedTitle;
        private final String normalizedContent;

        public ValidationResult(
                boolean titleValid,
                boolean contentValid,
                String normalizedTitle,
                String normalizedContent) {
            this.titleValid = titleValid;
            this.contentValid = contentValid;
            this.normalizedTitle = normalizedTitle;
            this.normalizedContent = normalizedContent;
        }

        public boolean isTitleValid() {
            return titleValid;
        }

        public boolean isContentValid() {
            return contentValid;
        }

        public boolean isValid() {
            return titleValid && contentValid;
        }

        public String getNormalizedTitle() {
            return normalizedTitle;
        }

        public String getNormalizedContent() {
            return normalizedContent;
        }
    }
}
