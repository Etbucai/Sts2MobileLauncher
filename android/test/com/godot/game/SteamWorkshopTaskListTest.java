package com.godot.game;

import android.app.Application;
import android.os.Handler;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SteamWorkshopTaskListTest {
	private SteamWorkshopActivity activity;
	private Object snapshot;

	@Before public void setUp() throws Exception {
		activity = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		activity.setContentView(new FrameLayout(activity));
		snapshot = field(activity, "librarySnapshot");
	}

	@After public void tearDown() throws Exception {
		((Handler) field(activity, "mainHandler")).removeCallbacksAndMessages(null);
		((ExecutorService) field(activity, "libraryExecutor")).shutdownNow();
	}

	@Test public void completedTasksSortByEndTimeAndFailureSupersedesInstalledCard() throws Exception {
		SteamWorkshopLibrary.Entry oldInstalled = entry("90001", 100L);
		SteamWorkshopLibrary.Entry newerInstalled = entry("90002", 300L);
		SteamWorkshopTaskHistory.Failure updateFailure = failure("90001", 400L, true);
		SteamWorkshopTaskHistory.Failure firstFailure = failure("90003", 200L, false);
		setField(snapshot, "entries", Arrays.asList(oldInstalled, newerInstalled));
		setField(snapshot, "failures", Arrays.asList(firstFailure, updateFailure));

		List<?> rows = completedRows();

		assertEquals(3, rows.size());
		assertEquals("90001", failureAt(rows, 0).publishedFileId);
		assertEquals("90002", entryAt(rows, 1).publishedFileId);
		assertEquals("90003", failureAt(rows, 2).publishedFileId);
		assertNotNull(entryAt(rows, 0));
		assertNull(failureAt(rows, 1));
	}

	private List<?> completedRows() throws Exception {
		Method method = SteamWorkshopActivity.class.getDeclaredMethod("completedDownloadRows");
		method.setAccessible(true);
		return (List<?>) method.invoke(activity);
	}

	private static SteamWorkshopTaskHistory.Failure failure(String id, long failedAtMs, boolean updateExisting) {
		return SteamWorkshopTaskHistory.Failure.create(
			item(id),
			new SteamWorkshopDownloader.BranchOption("public", "", "", "", "", "", "", 0L, "", 0L),
			updateExisting,
			"failed " + id,
			failedAtMs,
			"global"
		);
	}

	private static SteamWorkshopLibrary.Entry entry(String id, long installedAtMs) throws Exception {
		JSONObject object = new JSONObject();
		object.put("app_id", "2868840");
		object.put("published_file_id", id);
		object.put("title", "Synthetic " + id);
		object.put("installed_at_ms", installedAtMs);
		object.put("workshop_branch", "public");
		object.put("mods_scope", "global");
		return SteamWorkshopLibrary.Entry.fromJson(object);
	}

	private static SteamWorkshopCatalog.Item item(String id) {
		return new SteamWorkshopCatalog.Item(2868840, id, "Synthetic " + id, "", "", "", 0L, 100L, 0, 0, 1L);
	}

	private static SteamWorkshopTaskHistory.Failure failureAt(List<?> rows, int index) throws Exception {
		return (SteamWorkshopTaskHistory.Failure) field(rows.get(index), "failure");
	}

	private static SteamWorkshopLibrary.Entry entryAt(List<?> rows, int index) throws Exception {
		return (SteamWorkshopLibrary.Entry) field(rows.get(index), "entry");
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
