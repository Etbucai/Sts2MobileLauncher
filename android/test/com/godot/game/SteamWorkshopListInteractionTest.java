package com.godot.game;

import android.app.Application;
import android.app.Dialog;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
public class SteamWorkshopListInteractionTest {
	private SteamWorkshopActivity activity;

	@Before public void setUp() throws Exception {
		activity = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		field("libraryReady").setBoolean(activity, true);
	}

	@After public void tearDown() throws Exception {
		Dialog dialog = ShadowDialog.getLatestDialog();
		if (dialog != null) dialog.dismiss();
		((Handler) field("mainHandler").get(activity)).removeCallbacksAndMessages(null);
		((ExecutorService) field("libraryExecutor").get(activity)).shutdownNow();
	}

	@Test public void listActionsPlaceDetailsBeforeDownload() throws Exception {
		SteamWorkshopCatalog.Item item = item();
		LinearLayout actions = new LinearLayout(activity);
		Method populate = SteamWorkshopActivity.class.getDeclaredMethod(
			"populateWorkshopListActions",
			LinearLayout.class,
			SteamWorkshopCatalog.Item.class
		);
		populate.setAccessible(true);
		populate.invoke(activity, actions, item);

		assertEquals(2, actions.getChildCount());
		assertEquals(activity.getString(R.string.workshop_view_details), actions.getChildAt(0).getContentDescription());
		assertEquals(activity.getString(R.string.workshop_download_and_import), actions.getChildAt(1).getContentDescription());
	}

	@Test public void detailsControlKeepsTheExistingFullDetailNavigation() throws Exception {
		View details = invokeView("buildWorkshopListDetailsControl", new Class<?>[] {
			SteamWorkshopCatalog.Item.class
		}, item());
		assertTrue(details.hasOnClickListeners());
	}

	@Test public void itemDescriptionUsesFetchedTextInsideScrollableDialog() throws Exception {
		Method show = SteamWorkshopActivity.class.getDeclaredMethod("showItemDescription", SteamWorkshopCatalog.Item.class);
		show.setAccessible(true);
		show.invoke(activity, item());

		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		ScrollView scroll = find(dialog.getWindow().getDecorView(), ScrollView.class);
		assertNotNull(scroll);
		TextView description = find(scroll, TextView.class);
		assertNotNull(description);
		assertEquals("Fetched Workshop description", description.getText().toString());
		assertTrue(description.isTextSelectable());

		WindowManager.LayoutParams attributes = dialog.getWindow().getAttributes();
		assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, attributes.height);
		assertEquals(
			(int) (activity.getResources().getDisplayMetrics().widthPixels * 0.84f),
			attributes.width
		);

		int maxDialogHeight = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.78f);
		int maxDescriptionHeight = Math.max(
			ExtraSettingsUi.dp(activity, 120),
			maxDialogHeight - ExtraSettingsUi.dp(activity, 144)
		);
		scroll.measure(
			View.MeasureSpec.makeMeasureSpec(ExtraSettingsUi.dp(activity, 600), View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(maxDescriptionHeight * 2, View.MeasureSpec.EXACTLY)
		);
		assertTrue(scroll.getMeasuredHeight() < maxDescriptionHeight);
	}

	private View invokeView(String name, Class<?>[] types, Object... arguments) throws Exception {
		Method method = SteamWorkshopActivity.class.getDeclaredMethod(name, types);
		method.setAccessible(true);
		return (View) method.invoke(activity, arguments);
	}

	private static <T extends View> T find(View view, Class<T> type) {
		if (type.isInstance(view)) return type.cast(view);
		if (!(view instanceof ViewGroup)) return null;
		ViewGroup group = (ViewGroup) view;
		for (int i = 0; i < group.getChildCount(); i++) {
			T found = find(group.getChildAt(i), type);
			if (found != null) return found;
		}
		return null;
	}

	private static Field field(String name) throws Exception {
		Field field = SteamWorkshopActivity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static SteamWorkshopCatalog.Item item() {
		return new SteamWorkshopCatalog.Item(
			2868840,
			"90001",
			"Synthetic Workshop item",
			"Tester",
			"Fetched Workshop description",
			"",
			0L,
			10L,
			20,
			30,
			1L
		);
	}
}
