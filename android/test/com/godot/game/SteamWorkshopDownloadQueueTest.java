package com.godot.game;

import android.app.Application;
import android.app.Dialog;
import android.os.Handler;
import android.widget.Button;
import android.widget.FrameLayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SteamWorkshopDownloadQueueTest {
	private SteamWorkshopActivity activity;
	private List<Object> queue;
	private Map<String, Object> tasks;

	@SuppressWarnings("unchecked")
	@Before public void setUp() throws Exception {
		activity = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
		activity.setTheme(R.style.Theme_Sts2ExtraSettings);
		activity.setContentView(new FrameLayout(activity));
		SteamWorkshopPreferences.setDownloadBranchMode(activity, SteamWorkshopPreferences.BRANCH_MODE_ASK);
		SteamWorkshopPreferences.setSupplyStationEnabled(activity, false);
		queue = (List<Object>) field(activity, "pendingDownloadQueue");
		tasks = (Map<String, Object>) field(activity, "downloadTasks");
	}

	@After public void tearDown() throws Exception {
		((Handler) field(activity, "mainHandler")).removeCallbacksAndMessages(null);
		((ExecutorService) field(activity, "libraryExecutor")).shutdownNow();
	}

	@Test public void prerequisitesMergeWithFiveActiveAndTwoPendingRequests() throws Exception {
		for (int index = 1; index <= 5; index++) addActive("9000" + index);
		SteamWorkshopDownloader.BranchOption installedBranch = option("public-beta", "18446744073709551614");
		Object oldUpdate = pending("90006", installedBranch, true);
		Object oldDownload = pending("90007", null, false);
		queue.add(oldUpdate);
		queue.add(oldDownload);
		List<SteamWorkshopCatalog.RequiredItem> required = Arrays.asList(required("90001"), required("90006"), required("90007"), required("90008"), required("90008"));

		merge(required, "90009");
		merge(required, "90009");

		assertEquals(Arrays.asList("90006", "90007", "90008", "90009"), queuedIds());
		assertSame(oldUpdate, queue.get(0));
		assertSame(installedBranch, field(queue.get(0), "selectedOption"));
		assertEquals(true, field(queue.get(0), "updateExisting"));
		assertSame(oldDownload, queue.get(1));
		assertEquals(5, tasks.size());
	}

	@Test public void newPrerequisitesPrecedeAnExistingDependentWithoutReplacingItsInstallIdentity() throws Exception {
		for (int index = 1; index <= 5; index++) addActive("9000" + index);
		SteamWorkshopPreferences.setDownloadBranchMode(activity, SteamWorkshopPreferences.BRANCH_MODE_PUBLIC);
		Object current = pending("90006", option("public", "41"), true);
		Object otherBranch = pending("90006", option("public-beta", ""), true);
		queue.add(current);
		queue.add(otherBranch);

		merge(Arrays.asList(required("90008"), required("90008")), "90006");

		assertEquals(Arrays.asList("90008", "90006", "90006"), queuedIds());
		assertSame(current, queue.get(1));
		assertSame(otherBranch, queue.get(2));
		assertEquals(true, field(queue.get(1), "updateExisting"));
		assertEquals("41", ((SteamWorkshopDownloader.BranchOption) field(queue.get(1), "selectedOption")).getManifestId());
		assertEquals("public-beta", ((SteamWorkshopDownloader.BranchOption) field(queue.get(2), "selectedOption")).getBranch());
	}

	@Test public void activeItemDoesNotDiscardAQueuedDifferentBranch() throws Exception {
		addActive("90001");
		Object otherBranch = pending("90001", option("public-beta", ""), true);
		queue.add(otherBranch);

		invoke("pumpDownloadQueue", new Class<?>[0]);

		assertEquals(Arrays.asList("90001"), queuedIds());
		assertSame(otherBranch, queue.get(0));
		assertEquals(1, tasks.size());
	}

	@Test public void supplyStationPromptCanQueueForcedSteamReplacement() throws Exception {
		SteamWorkshopPreferences.setDownloadBranchMode(activity, SteamWorkshopPreferences.BRANCH_MODE_PUBLIC_BETA);
		Object request = pending("90020", option("supply-station-default", ""), true);

		invoke("showSupplyStationReplacementDialog", new Class<?>[] { request.getClass() }, request);

		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		Button cancel = dialog.findViewById(android.R.id.button2);
		Button replace = dialog.findViewById(android.R.id.button1);
		assertEquals(activity.getString(android.R.string.cancel), cancel.getText().toString());
		assertEquals(activity.getString(R.string.workshop_replace_with_steam), replace.getText().toString());
		for (int index = 1; index <= 5; index++) addActive("9010" + index);
		replace.performClick();
		ShadowLooper.idleMainLooper();

		assertEquals(1, queue.size());
		Object replacement = queue.get(0);
		assertEquals(true, field(replacement, "updateExisting"));
		assertEquals(true, field(replacement, "forceSteam"));
		assertEquals("public-beta", ((SteamWorkshopDownloader.BranchOption) field(replacement, "selectedOption")).getBranch());
	}

	@Test public void cancellingSupplyStationPromptDoesNotQueueReplacement() throws Exception {
		Object request = pending("90021", option("supply-station-default", ""), true);
		invoke("showSupplyStationReplacementDialog", new Class<?>[] { request.getClass() }, request);
		for (int index = 1; index <= 5; index++) addActive("9020" + index);

		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		((Button) dialog.findViewById(android.R.id.button2)).performClick();
		ShadowLooper.idleMainLooper();

		assertTrue(queue.isEmpty());
	}

	private void merge(List<SteamWorkshopCatalog.RequiredItem> required, String currentId) throws Exception {
		invoke("queueRequiredDownloads", new Class<?>[] { List.class, SteamWorkshopCatalog.Item.class }, required, item(currentId));
	}

	private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
		Method method = SteamWorkshopActivity.class.getDeclaredMethod(name, types);
		method.setAccessible(true);
		method.invoke(activity, args);
	}

	private List<String> queuedIds() throws Exception {
		java.util.ArrayList<String> ids = new java.util.ArrayList<>();
		for (Object pending : queue) ids.add(((SteamWorkshopCatalog.Item) field(pending, "item")).getPublishedFileId());
		return ids;
	}

	private void addActive(String id) throws Exception {
		Class<?> type = Class.forName("com.godot.game.SteamWorkshopActivity$DownloadTask");
		Constructor<?> constructor = type.getDeclaredConstructor(SteamWorkshopCatalog.Item.class);
		constructor.setAccessible(true);
		Object task = constructor.newInstance(item(id));
		Field started = type.getDeclaredField("downloadStarted");
		started.setAccessible(true);
		started.setBoolean(task, true);
		tasks.put(id, task);
	}

	private static Object pending(String id, SteamWorkshopDownloader.BranchOption option, boolean update) throws Exception {
		Class<?> type = Class.forName("com.godot.game.SteamWorkshopActivity$PendingDownload");
		Constructor<?> constructor = type.getDeclaredConstructor(SteamWorkshopCatalog.Item.class, SteamWorkshopDownloader.BranchOption.class, boolean.class);
		constructor.setAccessible(true);
		return constructor.newInstance(item(id), option, update);
	}

	private static Object field(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static SteamWorkshopDownloader.BranchOption option(String branch, String manifest) {
		return new SteamWorkshopDownloader.BranchOption(branch, manifest, "", "", "", "", "", 0L, "", 0L);
	}

	private static SteamWorkshopCatalog.RequiredItem required(String id) {
		return new SteamWorkshopCatalog.RequiredItem(2868840, id, "Synthetic " + id, "", "", 0L, 0L, 1L, "");
	}

	private static SteamWorkshopCatalog.Item item(String id) {
		return new SteamWorkshopCatalog.Item(2868840, id, "Synthetic " + id, "", "", "", 0L, 0L, 0, 0, 1L);
	}
}
