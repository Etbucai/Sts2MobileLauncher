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
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

	@Test public void downloadRowsPrioritizeActiveThenUpdatableThenDownloaded() throws Exception {
		SteamWorkshopLibrary.Entry activeUpdate = entry("90001", 100L, "available");
		SteamWorkshopLibrary.Entry failedUpdate = entry("90002", 200L, "available");
		SteamWorkshopLibrary.Entry downloaded = entry("90003", 300L, "current");
		setField(snapshot, "entries", Arrays.asList(activeUpdate, failedUpdate, downloaded));
		setField(snapshot, "failures", Arrays.asList(failure("90002", 400L, true)));
		markInstalled(activeUpdate, failedUpdate, downloaded);
		addActiveTask("90001");
		setField(activity, "libraryReady", true);

		List<?> rows = downloadRows();

		assertEquals(1, countRows(rows, "90001"));
		assertEquals(1, countRows(rows, "90002"));
		assertEquals(1, countRows(rows, "90003"));
		int updateHeader = rowIndex(rows, "update-header");
		int downloadedHeader = rowIndex(rows, "downloaded-header");
		assertTrue(rowIndexForItem(rows, "90001") < updateHeader);
		assertTrue(updateHeader < rowIndexForItem(rows, "90002"));
		assertTrue(rowIndexForItem(rows, "90002") < downloadedHeader);
		assertTrue(downloadedHeader < rowIndexForItem(rows, "90003"));
		assertNotNull(failureAt(rows, rowIndexForItem(rows, "90002")));
		assertEquals(Arrays.asList("90002"), updatableIds());
	}

	private List<?> completedRows() throws Exception {
		Method method = SteamWorkshopActivity.class.getDeclaredMethod("completedDownloadRows");
		method.setAccessible(true);
		return (List<?>) method.invoke(activity);
	}

	private List<?> downloadRows() throws Exception {
		Method method = SteamWorkshopActivity.class.getDeclaredMethod("buildDownloadRows");
		method.setAccessible(true);
		return (List<?>) method.invoke(activity);
	}

	@SuppressWarnings("unchecked")
	private List<String> updatableIds() throws Exception {
		Method method = SteamWorkshopActivity.class.getDeclaredMethod("updatableEntries", List.class);
		method.setAccessible(true);
		List<SteamWorkshopLibrary.Entry> entries =
			(List<SteamWorkshopLibrary.Entry>) method.invoke(activity, ((List<?>) field(snapshot, "entries")));
		java.util.ArrayList<String> ids = new java.util.ArrayList<>();
		for (SteamWorkshopLibrary.Entry entry : entries) ids.add(entry.publishedFileId);
		return ids;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void markInstalled(SteamWorkshopLibrary.Entry... entries) throws Exception {
		Map<String, Object> states = (Map<String, Object>) field(snapshot, "installStates");
		Class enumType = Class.forName("com.godot.game.SteamWorkshopActivity$WorkshopInstallState");
		Object installed = Enum.valueOf(enumType, "INSTALLED");
		for (SteamWorkshopLibrary.Entry entry : entries) {
			states.put(entry.key(), installed);
		}
	}

	@SuppressWarnings("unchecked")
	private void addActiveTask(String id) throws Exception {
		Class<?> type = Class.forName("com.godot.game.SteamWorkshopActivity$DownloadTask");
		java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(SteamWorkshopCatalog.Item.class);
		constructor.setAccessible(true);
		Object task = constructor.newInstance(item(id));
		((Map<String, Object>) field(activity, "downloadTasks")).put(id, task);
	}

	private static int countRows(List<?> rows, String publishedFileId) throws Exception {
		int count = 0;
		for (Object row : rows) {
			if (publishedFileId.equals(rowPublishedFileId(row))) count++;
		}
		return count;
	}

	private static int rowIndexForItem(List<?> rows, String publishedFileId) throws Exception {
		for (int index = 0; index < rows.size(); index++) {
			if (publishedFileId.equals(rowPublishedFileId(rows.get(index)))) return index;
		}
		return -1;
	}

	private static int rowIndex(List<?> rows, String key) throws Exception {
		for (int index = 0; index < rows.size(); index++) {
			if (key.equals(field(rows.get(index), "key"))) return index;
		}
		return -1;
	}

	private static String rowPublishedFileId(Object row) throws Exception {
		Method method = row.getClass().getDeclaredMethod("publishedFileId");
		method.setAccessible(true);
		return (String) method.invoke(row);
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
		return entry(id, installedAtMs, "");
	}

	private static SteamWorkshopLibrary.Entry entry(String id, long installedAtMs, String updateStatus) throws Exception {
		JSONObject object = new JSONObject();
		object.put("app_id", "2868840");
		object.put("published_file_id", id);
		object.put("title", "Synthetic " + id);
		object.put("installed_at_ms", installedAtMs);
		object.put("workshop_branch", "public");
		object.put("mods_scope", "global");
		object.put("update_status", updateStatus);
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
