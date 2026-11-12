package com.godot.game;

import android.app.Application;
import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = Application.class, shadows = AndroidLinuxTestShadow.class)
public class SteamWorkshopDownloaderResumeTest {
	private SteamWorkshopActivity activity;
	private SteamWorkshopDownloader downloader;
	private File downloadsRoot;

	@Before public void setUp() throws Exception {
		activity = Robolectric.buildActivity(SteamWorkshopActivity.class).get();
		downloader = new SteamWorkshopDownloader(activity);
		downloadsRoot = new File(new File(activity.getFilesDir(), "workshop"), "downloads");
		assertTrue(downloadsRoot.isDirectory() || downloadsRoot.mkdirs());
	}

	@Test public void newDirectChildDoesNotFailCanonicalPathSafetyCheck() throws Exception {
		File result = prepare(item("90001"), option("11"), "");

		assertTrue(result.isDirectory());
		assertEquals(downloadsRoot.getCanonicalFile(), result.getParentFile().getCanonicalFile());
	}

	@Test public void legacyTaskWithoutDirectoryIdReusesMatchingChunkCache() throws Exception {
		File legacy = legacyDirectory("90001", "11");

		File result = prepare(item("90001"), option("11"), "");

		assertEquals(legacy.getCanonicalFile(), result.getCanonicalFile());
	}

	@Test public void changedManifestDoesNotReuseLegacyChunkCache() throws Exception {
		File legacy = legacyDirectory("90001", "11");

		File result = prepare(item("90001"), option("12"), "");

		assertNotEquals(legacy.getCanonicalFile(), result.getCanonicalFile());
	}

	private File prepare(
		SteamWorkshopCatalog.Item item,
		SteamWorkshopDownloader.BranchOption option,
		String resumeDirectoryId
	) throws Exception {
		Method method = SteamWorkshopDownloader.class.getDeclaredMethod(
			"prepareOutputDir",
			Context.class,
			SteamWorkshopCatalog.Item.class,
			SteamWorkshopDownloader.BranchOption.class,
			String.class
		);
		method.setAccessible(true);
		return (File) method.invoke(downloader, activity, item, option, resumeDirectoryId);
	}

	private File legacyDirectory(String publishedFileId, String manifestId) throws Exception {
		File directory = new File(downloadsRoot, publishedFileId + "-" + UUID.randomUUID());
		assertTrue(directory.mkdir());
		assertTrue(new File(directory, ".chunks").mkdir());
		JSONObject metadata = new JSONObject();
		metadata.put("published_file_id", publishedFileId);
		metadata.put("requested_branch", "public-beta");
		metadata.put("resolved_manifest_id", manifestId);
		metadata.put("resolved_depot_id", "2868840");
		Files.write(
			new File(directory, "metadata.json").toPath(),
			metadata.toString().getBytes(StandardCharsets.UTF_8)
		);
		return directory;
	}

	private static SteamWorkshopDownloader.BranchOption option(String manifestId) {
		return new SteamWorkshopDownloader.BranchOption(
			"public-beta",
			manifestId,
			"2868840",
			"requested_branch_default_manifest",
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
