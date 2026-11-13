package com.godot.game;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ModManifestDisplayNameTest {
	private ExtraSettingsRepository repository;

	@Before public void setup() {
		Context context = RuntimeEnvironment.getApplication();
		repository = new ExtraSettingsRepository(context);
	}

	@Test public void displayNameOverridesNameWhenBothArePresent() throws Exception {
		File modsRoot = repository.getModsRootDir();
		Files.createDirectories(modsRoot.toPath());
		File modDirectory = Files.createTempDirectory(modsRoot.toPath(), "display-name-").toFile();
		JSONObject manifest = new JSONObject()
			.put("id", "DisplayNamePriority")
			.put("name", "Internal Name")
			.put("display_name", "Localized Display Name");
		Files.write(
			new File(modDirectory, "DisplayNamePriority.json").toPath(),
			manifest.toString().getBytes(StandardCharsets.UTF_8)
		);

		List<ExtraSettingsRepository.ModEntry> entries = repository.listInstalledModManifestsUnder(modDirectory);

		assertEquals(1, entries.size());
		assertEquals("Localized Display Name", entries.get(0).displayName);
	}
}
