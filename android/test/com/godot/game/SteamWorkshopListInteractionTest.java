package com.godot.game;

import android.app.Application;
import android.os.Handler;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
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
		((Handler) field("mainHandler").get(activity)).removeCallbacksAndMessages(null);
		((ExecutorService) field("libraryExecutor").get(activity)).shutdownNow();
	}

	@Test public void listItemIsClickableAndOnlyShowsDownloadAction() throws Exception {
		SteamWorkshopCatalog.Item item = item();
		Class<?> holderType = Class.forName("com.godot.game.SteamWorkshopActivity$WorkshopItemHolder");
		Constructor<?> constructor = holderType.getDeclaredConstructor(SteamWorkshopActivity.class);
		constructor.setAccessible(true);
		Object holder = constructor.newInstance(activity);
		Field itemField = holderType.getDeclaredField("item");
		itemField.setAccessible(true);
		itemField.set(holder, item);
		Method refresh = holderType.getDeclaredMethod("refreshControl");
		refresh.setAccessible(true);
		refresh.invoke(holder);

		Field downloadField = holderType.getDeclaredField("download");
		downloadField.setAccessible(true);
		LinearLayout download = (LinearLayout) downloadField.get(holder);
		androidx.recyclerview.widget.RecyclerView.ViewHolder row =
			(androidx.recyclerview.widget.RecyclerView.ViewHolder) holder;

		assertTrue(row.itemView.hasOnClickListeners());
		assertEquals(1, download.getChildCount());
		assertEquals(
			activity.getString(R.string.workshop_download_and_import),
			download.getChildAt(0).getContentDescription()
		);
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
