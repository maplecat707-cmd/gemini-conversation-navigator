package io.github.geminianchor;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import io.github.geminianchor.core.ConversationIndex.Entry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35})
public class MainActivityTest {
    private Button button(View view, String title) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(title)) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { Button found = button(group.getChildAt(i), title); if (found != null) return found; }
        }
        return null;
    }
    @Test public void memoryCanBeEnabledAndDeletedWhenAccessibilityServiceIsOff() throws Exception {
        NavigatorService.current = null;
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        button(activity.getWindow().getDecorView(), "开启本机会话记忆").performClick();
        assertTrue(activity.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("rememberSessions", false));
        SessionStore store = new SessionStore(activity, true);
        store.update(List.of(new Entry(0, "保存的发言", 0))); assertTrue(store.save().get(5, TimeUnit.SECONDS));
        button(activity.getWindow().getDecorView(), "删除所有本机会话记忆").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertFalse(new SessionStore(activity, true).active().entries().isEmpty());
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(new SessionStore(activity, true).active().entries().isEmpty());
        assertFalse(activity.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("rememberSessions", true));
        controller.pause().stop().destroy();
    }
}
