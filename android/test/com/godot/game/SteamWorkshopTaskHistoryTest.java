package com.godot.game;

import android.app.Application;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
public class SteamWorkshopTaskHistoryTest {
	private SteamWorkshopActivity activity;
	private SteamWorkshopTaskHistory history;

	@Before public void setUp() throws Exception {
		activity = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
		history = new SteamWorkshopTaskHistory(activity);
		for (SteamWorkshopTaskHistory.Failure failure : history.listFailures()) {
			history.removeFailure(failure);
		}
	}

	@Test public void latestFailureReplacesSameTaskAndPersistsRetryContext() throws Exception {
		SteamWorkshopDownloader.BranchOption option = option("public-beta", "123");
		history.recordFailure(item("90001"), option, true, "first error");
		history.recordFailure(item("90001"), option, true, "latest error");

		List<SteamWorkshopTaskHistory.Failure> failures = new SteamWorkshopTaskHistory(activity).listFailures();

		assertEquals(1, failures.size());
		assertEquals("latest error", failures.get(0).error);
		assertEquals("public-beta", failures.get(0).workshopBranch);
		assertEquals("123", failures.get(0).manifestId);
		assertTrue(failures.get(0).updateExisting);
	}

	@Test public void successfulBranchOnlyClearsItsMatchingFailure() throws Exception {
		history.recordFailure(item("90001"), option("public", "11"), false, "public failed");
		history.recordFailure(item("90001"), option("public-beta", "22"), true, "beta failed");
		String scopeKey = new SteamWorkshopLibrary(activity).getCurrentScope().key;

		history.removeForSuccess("90001", "public-beta", scopeKey);

		List<SteamWorkshopTaskHistory.Failure> failures = history.listFailures();
		assertEquals(1, failures.size());
		assertEquals("public", failures.get(0).workshopBranch);
	}

	@Test public void resumableDirectoryAndStoppedStatePersistAcrossReload() throws Exception {
		history.recordTask(
			item("90001"),
			option("public", "11"),
			false,
			"stopped",
			"90001-resume-id",
			SteamWorkshopTaskHistory.STATE_STOPPED
		);

		SteamWorkshopTaskHistory.Failure task = new SteamWorkshopTaskHistory(activity).listFailures().get(0);

		assertEquals("90001-resume-id", task.resumeDirectoryId);
		assertEquals(SteamWorkshopTaskHistory.STATE_STOPPED, task.state);
		assertEquals(List.of("90001-resume-id"), history.listProtectedResumeDirectoryIds());
	}

	@Test public void failureBeforeDirectoryAllocationKeepsMatchingResumeDirectory() throws Exception {
		history.recordTask(
			item("90001"),
			option("public", "11"),
			false,
			"stopped",
			"90001-resume-id",
			SteamWorkshopTaskHistory.STATE_STOPPED
		);

		history.recordTask(
			item("90001"),
			option("public", "11"),
			false,
			"failed before allocation",
			"",
			SteamWorkshopTaskHistory.STATE_FAILED
		);

		assertEquals("90001-resume-id", history.listFailures().get(0).resumeDirectoryId);
	}

	@Test public void changedManifestDoesNotReuseOldDirectory() throws Exception {
		history.recordTask(
			item("90001"),
			option("public", "11"),
			false,
			"first",
			"90001-resume-id",
			SteamWorkshopTaskHistory.STATE_FAILED
		);

		history.recordTask(
			item("90001"),
			option("public", "12"),
			false,
			"new manifest",
			"",
			SteamWorkshopTaskHistory.STATE_FAILED
		);

		assertEquals("", history.listFailures().get(0).resumeDirectoryId);
		assertFalse(SteamWorkshopTaskHistory.isSafeResumeDirectoryId("../outside"));
	}

	private static SteamWorkshopDownloader.BranchOption option(String branch, String manifest) {
		return new SteamWorkshopDownloader.BranchOption(
			branch,
			manifest,
			"2868841",
			"test",
			"",
			"",
			"",
			0L,
			"",
			0L
		);
	}

	private static SteamWorkshopCatalog.Item item(String id) {
		return new SteamWorkshopCatalog.Item(2868840, id, "Synthetic " + id, "", "", "", 0L, 100L, 0, 0, 1L);
	}
}
