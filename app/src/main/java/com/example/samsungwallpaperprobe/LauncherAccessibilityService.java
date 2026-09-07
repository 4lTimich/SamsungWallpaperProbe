package com.example.samsungwallpaperprobe;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * Opt-in probe for One UI Home accessibility events.
 * The XML configuration restricts event delivery to com.sec.android.app.launcher.
 * We intentionally do not request window-content retrieval.
 */
public class LauncherAccessibilityService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        LauncherScrollBus.serviceConnected = true;
        LauncherScrollBus.sequence++;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || !"com.sec.android.app.launcher".contentEquals(pkg)) return;

        LauncherScrollBus.eventUptimeMs = SystemClock.uptimeMillis();
        LauncherScrollBus.eventType = event.getEventType();
        LauncherScrollBus.scrollX = event.getScrollX();
        LauncherScrollBus.scrollY = event.getScrollY();
        LauncherScrollBus.maxScrollX = event.getMaxScrollX();
        LauncherScrollBus.maxScrollY = event.getMaxScrollY();
        if (Build.VERSION.SDK_INT >= 28) {
            LauncherScrollBus.deltaX = event.getScrollDeltaX();
            LauncherScrollBus.deltaY = event.getScrollDeltaY();
        } else {
            LauncherScrollBus.deltaX = 0;
            LauncherScrollBus.deltaY = 0;
        }
        LauncherScrollBus.fromIndex = event.getFromIndex();
        LauncherScrollBus.toIndex = event.getToIndex();
        LauncherScrollBus.itemCount = event.getItemCount();
        LauncherScrollBus.className = event.getClassName() == null ? "" : event.getClassName().toString();

        StringBuilder text = new StringBuilder();
        CharSequence description = event.getContentDescription();
        if (description != null && description.length() > 0) {
            text.append(description);
        }
        List<CharSequence> items = event.getText();
        if (items != null) {
            for (CharSequence item : items) {
                if (item == null || item.length() == 0) continue;
                if (text.length() > 0) text.append(" | ");
                text.append(item);
                if (text.length() > 120) break;
            }
        }
        LauncherScrollBus.summary = text.length() > 120 ? text.substring(0, 120) : text.toString();
        LauncherScrollBus.sequence++;
    }

    @Override
    public void onInterrupt() {
        // No spoken/audio feedback to interrupt.
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        LauncherScrollBus.serviceConnected = false;
        LauncherScrollBus.sequence++;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        LauncherScrollBus.serviceConnected = false;
        LauncherScrollBus.sequence++;
        super.onDestroy();
    }
}
