package lt.mipt.notes.ui;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/**
 * Applies system bar and IME window insets cleanly across API 26-35 (including API 35 edge-to-edge).
 */
public final class WindowInsetsHelper {

    private WindowInsetsHelper() {
    }

    public static void applyEdgeToEdgePadding(View rootView, View topHeader, View bottomBar) {
        final int headerStart = topHeader.getPaddingStart();
        final int headerTop = topHeader.getPaddingTop();
        final int headerEnd = topHeader.getPaddingEnd();
        final int headerBottom = topHeader.getPaddingBottom();

        final int bottomStart = bottomBar.getPaddingStart();
        final int bottomTop = bottomBar.getPaddingTop();
        final int bottomEnd = bottomBar.getPaddingEnd();
        final int bottomPad = bottomBar.getPaddingBottom();

        rootView.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int insetTop;
            int insetBottom;
            int insetLeft;
            int insetRight;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets sysBars = windowInsets.getInsets(WindowInsets.Type.systemBars());
                Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
                insetTop = sysBars.top;
                insetBottom = Math.max(sysBars.bottom, ime.bottom);
                insetLeft = sysBars.left;
                insetRight = sysBars.right;
            } else {
                insetTop = windowInsets.getSystemWindowInsetTop();
                insetBottom = windowInsets.getSystemWindowInsetBottom();
                insetLeft = windowInsets.getSystemWindowInsetLeft();
                insetRight = windowInsets.getSystemWindowInsetRight();
            }

            topHeader.setPaddingRelative(
                    headerStart + insetLeft,
                    headerTop + insetTop,
                    headerEnd + insetRight,
                    headerBottom);

            bottomBar.setPaddingRelative(
                    bottomStart + insetLeft,
                    bottomTop,
                    bottomEnd + insetRight,
                    bottomPad + insetBottom);

            return windowInsets;
        });
        rootView.requestApplyInsets();
    }
}
