package com.godot.game;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import top.apricityx.workshop.workshop.SpireSupplyStationClient;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class WorkshopModDisplayNamesTest {
	private ExtraSettingsRepository repository;
	private SteamWorkshopLibrary library;

	@Before public void setup() throws Exception {
		Context context = RuntimeEnvironment.getApplication();
		repository = new ExtraSettingsRepository(context);
		library = new SteamWorkshopLibrary(context);
		library.clearEntries();
	}

	@Test public void singleModUsesWorkshopTitle() throws Exception {
		File installRoot = createWorkshopInstall("single", manifest("SingleTarget", "Single Target"));
		List<ExtraSettingsRepository.ModEntry> mods = repository.listInstalledModManifestsUnder(installRoot);
		SteamWorkshopCatalog.Item item = item("Click Anywhere | 单敌自动瞄准");
		library.recordInstall(item, installRoot, mods);

		Map<ExtraSettingsRepository.ModEntry, WorkshopModDisplayNames.Source> sources =
			WorkshopModDisplayNames.resolve(mods, library.listEntries(), library.getCurrentScope());

		assertEquals(1, sources.size());
		assertEquals(item.getPublishedFileId(), sources.get(mods.get(0)).publishedFileId);
		assertEquals("Click Anywhere | 单敌自动瞄准", sources.get(mods.get(0)).displayName(mods.get(0)));
	}

	@Test public void multiModCombinesWorkshopAndManifestNames() throws Exception {
		File installRoot = createWorkshopInstall(
			"bundle",
			manifest("BundleCore", "Core Library"),
			manifest("BundleContent", "Playable Content")
		);
		List<ExtraSettingsRepository.ModEntry> mods = repository.listInstalledModManifestsUnder(installRoot);
		library.recordInstall(item("Workshop Bundle"), installRoot, mods);

		Map<ExtraSettingsRepository.ModEntry, WorkshopModDisplayNames.Source> sources =
			WorkshopModDisplayNames.resolve(mods, library.listEntries(), library.getCurrentScope());

		assertEquals(2, sources.size());
		assertEquals("Workshop Bundle · Core Library", resolvedName(mods, sources, "BundleCore"));
		assertEquals("Workshop Bundle · Playable Content", resolvedName(mods, sources, "BundleContent"));
	}

	@Test public void sameModIdOutsideWorkshopDirectoryIsNotMatched() throws Exception {
		File workshopRoot = createWorkshopInstall("scoped", manifest("SharedId", "Workshop Copy"));
		List<ExtraSettingsRepository.ModEntry> workshopMods = repository.listInstalledModManifestsUnder(workshopRoot);
		library.recordInstall(item("Workshop Name"), workshopRoot, workshopMods);
		File localRoot = createWorkshopInstall("local", manifest("SharedId", "Local Copy"));
		ExtraSettingsRepository.ModEntry localMod = repository.listInstalledModManifestsUnder(localRoot).get(0);

		Map<ExtraSettingsRepository.ModEntry, WorkshopModDisplayNames.Source> sources =
			WorkshopModDisplayNames.resolve(List.of(localMod), library.listEntries(), library.getCurrentScope());

		assertFalse(sources.containsKey(localMod));
	}

	@Test public void replacingSupplyStationRecordLeavesOnlySteamBranch() throws Exception {
		String publishedFileId = "99881";
		SteamWorkshopCatalog.Item item = item(publishedFileId, "Migrated Workshop Item");
		File supplyRoot = createWorkshopInstall("supply-record", manifest("MigratedMod", "Supply Copy"));
		List<ExtraSettingsRepository.ModEntry> supplyMods = repository.listInstalledModManifestsUnder(supplyRoot);
		SteamWorkshopLibrary.Entry supplyEntry = library.recordInstall(
			item,
			supplyRoot,
			supplyMods,
			new SteamWorkshopLibrary.InstallContext(
				SpireSupplyStationClient.BRANCH, "manual", "", "", "", "",
				SpireSupplyStationClient.SOURCE, "", "", ""
			)
		);
		File steamRoot = createWorkshopInstall("steam-record", manifest("MigratedMod", "Steam Copy"));
		List<ExtraSettingsRepository.ModEntry> steamMods = repository.listInstalledModManifestsUnder(steamRoot);

		library.recordInstallReplacing(
			item,
			steamRoot,
			steamMods,
			new SteamWorkshopLibrary.InstallContext(
				"public-beta", "manual", "", "", "", "12345",
				"cm_get_item_info_author_snapshot", "", "", ""
			),
			supplyEntry
		);

		List<SteamWorkshopLibrary.Entry> entries = library.listEntries();
		assertEquals(1, entries.size());
		assertEquals("public-beta", entries.get(0).workshopBranch);
		assertEquals("cm_get_item_info_author_snapshot", entries.get(0).resolutionSource);
	}

	private File createWorkshopInstall(String name, JSONObject... manifests) throws Exception {
		File root = new File(repository.getModsRootDir(), name);
		Files.createDirectories(root.toPath());
		for (JSONObject manifest : manifests) {
			String id = manifest.getString("id");
			Files.write(
				new File(root, id + ".json").toPath(),
				manifest.toString().getBytes(StandardCharsets.UTF_8)
			);
		}
		return root;
	}

	private JSONObject manifest(String id, String displayName) throws Exception {
		return new JSONObject().put("id", id).put("display_name", displayName);
	}

	private String resolvedName(
		List<ExtraSettingsRepository.ModEntry> mods,
		Map<ExtraSettingsRepository.ModEntry, WorkshopModDisplayNames.Source> sources,
		String modId
	) {
		for (ExtraSettingsRepository.ModEntry mod : mods) {
			if (modId.equals(mod.modId)) {
				return sources.get(mod).displayName(mod);
			}
		}
		throw new AssertionError("Missing MOD " + modId);
	}

	private SteamWorkshopCatalog.Item item(String title) {
		return item(Long.toString(System.nanoTime()), title);
	}

	private SteamWorkshopCatalog.Item item(String publishedFileId, String title) {
		return new SteamWorkshopCatalog.Item(
			SteamWorkshopPreferences.DEFAULT_APP_ID,
			publishedFileId,
			title,
			"",
			"",
			"",
			0L,
			0L,
			0,
			0,
			1L
		);
	}
}
