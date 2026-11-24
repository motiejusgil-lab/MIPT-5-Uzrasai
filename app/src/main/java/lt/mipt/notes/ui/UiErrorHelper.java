package lt.mipt.notes.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

import lt.mipt.notes.R;
import lt.mipt.notes.data.StorageException;
import lt.mipt.notes.model.StorageType;

/**
 * Shared UI formatting and error presentation helpers.
 */
public final class UiErrorHelper {

    private UiErrorHelper() {
    }

    public static String getStorageDisplayName(Context context, StorageType storageType) {
        return storageType == StorageType.JSON
                ? context.getString(R.string.storage_json)
                : context.getString(R.string.storage_sqlite);
    }

    public static String formatTimestamp(long epochMillis) {
        DateFormat formatter = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM,
                DateFormat.SHORT,
                Locale.getDefault());
        return formatter.format(new Date(epochMillis));
    }

    public static String getErrorMessage(Context context, StorageException error) {
        switch (error.getErrorType()) {
            case CORRUPTED_JSON:
                return context.getString(R.string.error_corrupted_json);
            case DATABASE_ERROR:
                return context.getString(R.string.error_database);
            case NOT_FOUND:
                return context.getString(R.string.error_note_not_found);
            case IO_ERROR:
            default:
                return context.getString(R.string.error_io);
        }
    }

    public static AlertDialog showErrorDialog(
            Activity activity,
            StorageException error,
            Runnable onDismiss) {
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.error_dialog_title)
                .setMessage(getErrorMessage(activity, error))
                .setPositiveButton(R.string.action_ok, (d, which) -> d.dismiss())
                .setOnDismissListener(d -> {
                    if (onDismiss != null) {
                        onDismiss.run();
                    }
                })
                .create();
        dialog.show();
        return dialog;
    }
}
