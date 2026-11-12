package com.godot.game;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
public class SteamWorkshopDownloadCleanerTest {
	private SteamWorkshopActivity activity;
	private SteamWorkshopTaskHistory history;
	private File downloadsRoot;

	@Before public void setUp() throws Exception {
		activity = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
		history = new SteamWorkshopTaskHistory(activity);
		for (SteamWorkshopTaskHistory.Failure failure : history.listFailures()) {
			history.removeFailure(failure);
		}
		downloadsRoot = new File(new File(activity.getFilesDir(), "workshop"), "downloads");
		assertTrue(downloadsRoot.isDirectory() || downloadsRoot.mkdirs());
	}

	@Test public void dailyCleanupKeepsReferencedResumeDirectory() throws Exception {
		String directoryId = "90001-" + UUID.randomUUID();
		File referenced = createDirectory(directoryId);
		history.recordTask(
			item("90001"),
			option(),
			false,
			"failed",
			directoryId,
			SteamWorkshopTaskHistory.STATE_FAILED
		);

		runCleanup();

		assertTrue(referenced.isDirectory());
	}

	@Test public void dailyCleanupDeletesUnreferencedDirectory() throws Exception {
		File unreferenced = createDirectory("90002-" + UUID.randomUUID());

		runCleanup();

		assertFalse(unreferenced.exists());
	}

	@Test public void dailyCleanupKeepsDirectoryWithCurrentActiveLease() throws Exception {
		File active = createDirectory("90003-" + UUID.randomUUID());
		File marker = new File(active, SteamWorkshopDownloader.ACTIVE_DOWNLOAD_MARKER);
		assertTrue(marker.createNewFile());

		runCleanup();

		assertTrue(active.isDirectory());
	}

	@Test public void explicitDeleteRejectsPathTraversal() throws Exception {
		File outside = new File(downloadsRoot.getParentFile(), "outside-" + UUID.randomUUID());
		assertTrue(outside.mkdir());

		SteamWorkshopDownloadCleaner.deleteTaskDownloadDirectory(activity, "../" + outside.getName());

		assertTrue(outside.isDirectory());
	}

	private File createDirectory(String name) {
		File directory = new File(downloadsRoot, name);
		assertTrue(directory.mkdir());
		return directory;
	}

	private void runCleanup() throws Exception {
		Method method = SteamWorkshopDownloadCleaner.class.getDeclaredMethod("cleanupDownloadsDirectory", android.content.Context.class);
		method.setAccessible(true);
		method.invoke(null, activity);
	}

	private static SteamWorkshopDownloader.BranchOption option() {
		return new SteamWorkshopDownloader.BranchOption(
			"public", "11", "2868841", "test", "", "", "", 0L, "", 0L
		);
	}

	private static SteamWorkshopCatalog.Item item(String id) {
		return new SteamWorkshopCatalog.Item(2868840, id, "Synthetic " + id, "", "", "", 0L, 100L, 0, 0, 1L);
	}
}
