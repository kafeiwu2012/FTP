package com.lanftp;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.util.*;

/** AOSP emulator-only smoke driver: real system picker grants, never fabricated URI permissions. */
public final class SmokeInstrumentation extends Instrumentation {
  private MainActivity activity;
  private File stop;

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      if (!Build.FINGERPRINT.contains("generic") && !Build.FINGERPRINT.contains("emulator"))
        throw new IllegalStateException("Disposable AOSP emulator required");
      stop = new File(getTargetContext().getFilesDir(), "smoke-stop");
      stop.delete();
      new File(getTargetContext().getFilesDir(), "smoke-ready").delete();
      Intent launch =
          new Intent(getTargetContext(), MainActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      activity = (MainActivity) startActivitySync(launch);
      runOnMainSync(
          () ->
              activity.chooseFolder(
                  android.net.Uri.parse(
                      "content://com.android.externalstorage.documents/document/primary%3ADocuments%2FLanFTPTest")));
      // Navigate the actual Android directory picker, including its final permission dialog.
      // A standard initial-directory hint avoids slow TCG grid navigation; the user grant is still
      // real.
      clickSystem("USE THIS FOLDER");
      clickSystem("ALLOW");
      long deadline = SystemClock.elapsedRealtime() + 180000;
      while (!getTargetContext().getContentResolver().getPersistedUriPermissions().stream()
          .anyMatch(p -> p.isWritePermission() && p.getUri().toString().contains("LanFTPTest"))) {
        if (SystemClock.elapsedRealtime() > deadline)
          throw new IOException("No persisted fixture write grant");
        SystemClock.sleep(250);
      }
      runOnMainSync(
          () -> {
            List<EditText> fields = new ArrayList<>();
            List<Switch> flags = new ArrayList<>();
            collect(activity.getWindow().getDecorView(), fields, flags);
            if (fields.size() != 3 || flags.size() != 3)
              throw new IllegalStateException("Unexpected test UI");
            fields.get(0).setText("tester");
            fields.get(1).setText("test-password");
            fields.get(2).setText("2121");
            flags.get(0).setChecked(true);
            flags.get(1).setChecked(true);
            flags.get(2).setChecked(false);
            button("启动文件共享").performClick();
          });
      deadline = SystemClock.elapsedRealtime() + 180000;
      while (!FtpService.running) {
        if (FtpService.status.startsWith("启动失败") || SystemClock.elapsedRealtime() > deadline)
          throw new IOException(FtpService.status);
        SystemClock.sleep(250);
      }
      String ready =
          "uid="
              + android.os.Process.myUid()
              + "\nftp="
              + FtpService.activePort
              + "\nweb="
              + FtpService.activeWebPort
              + "\n"
              + FtpService.status
              + "\n";
      try (FileWriter writer =
          new FileWriter(new File(getTargetContext().getFilesDir(), "smoke-ready"))) {
        writer.write(ready);
      }
      // Host clients exercise TCP protocols while the ordinary app UID owns the foreground service.
      deadline = SystemClock.elapsedRealtime() + 1800000;
      while (!stop.exists() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(250);
      getTargetContext().stopService(new Intent(getTargetContext(), FtpService.class));
      result.putString(
          "result",
          "System SAF grant and non-root sharing service started; host protocol checks recorded"
              + " separately.");
      finish(Activity.RESULT_OK, result);
    } catch (Throwable e) {
      result.putString("error", e.getClass().getSimpleName() + ": " + e.getMessage());
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private void clickSystem(String text) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 180000;
    while (SystemClock.elapsedRealtime() < deadline) {
      AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
      if (root != null) {
        // Software-only emulation can show a system-server ANR during cold boot.
        // Explicitly choose Wait; never dismiss or hide an application failure.
        if (!root.findAccessibilityNodeInfosByText("Process system isn't responding").isEmpty()
            || !root.findAccessibilityNodeInfosByText("System UI isn't responding").isEmpty()) {
          for (AccessibilityNodeInfo wait : root.findAccessibilityNodeInfosByText("Wait"))
            if ("Wait".contentEquals(wait.getText()))
              wait.performAction(AccessibilityNodeInfo.ACTION_CLICK);
          SystemClock.sleep(500);
          continue;
        }
      }
      if (root != null)
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text))
          if (text.contentEquals(node.getText() == null ? "" : node.getText())) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return;
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            long t = SystemClock.uptimeMillis();
            MotionEvent
                down =
                    MotionEvent.obtain(
                        t, t, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0),
                up =
                    MotionEvent.obtain(
                        t, t + 10, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            // Both events are queued without waiting for each frame: TCG can otherwise turn a tap
            // into a long press.
            getUiAutomation().injectInputEvent(down, false);
            getUiAutomation().injectInputEvent(up, false);
            down.recycle();
            up.recycle();
            SystemClock.sleep(500);
            return;
          }
      SystemClock.sleep(250);
    }
    throw new IOException("Picker target missing: " + text);
  }

  private Button button(String text) {
    List<Button> buttons = new ArrayList<>();
    findButtons(activity.getWindow().getDecorView(), buttons);
    for (Button b : buttons) if (text.contentEquals(b.getText())) return b;
    throw new IllegalStateException("Button missing");
  }

  private void findButtons(View v, List<Button> out) {
    if (v instanceof Button) out.add((Button) v);
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) findButtons(g.getChildAt(i), out);
    }
  }

  private void collect(View v, List<EditText> fields, List<Switch> flags) {
    if (v instanceof EditText) fields.add((EditText) v);
    if (v instanceof Switch) flags.add((Switch) v);
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), fields, flags);
    }
  }
}
