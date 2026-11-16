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
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ModImportSafetyTest {
	private Context context;
	private ExtraSettingsRepository repository;

	@Before public void setup() {
		context = RuntimeEnvironment.getApplication();
		repository = new ExtraSettingsRepository(context);
	}

	@Test public void manifestCannotCreateAliasesOutsideStaging() throws Exception {
		for (String field : List.of("id", "pck_name")) {
			File zip = archive(new JSONObject().put("id", "SafeMod").put("pck_name", "payload").put(field, "../escaped"));
			assertThrows(IOException.class, () -> repository.prepareDownloadedModImport(zip, "mod.zip"));
		}
	}

	@Test public void rootedAndWindowsPathsAreRejectedBeforeImport() throws Exception {
		File outside = new File(context.getFilesDir(), "sentinel");
		Files.write(outside.toPath(), ("keep").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		for (String id : List.of(outside.getAbsolutePath(), "..\\escaped", "C:escaped", ".", "..")) {
			File zip = archive(new JSONObject().put("id", id).put("pck_name", "payload"));
			assertThrows(IOException.class, () -> repository.prepareDownloadedModImport(zip, "mod.zip"));
			assertEquals("keep", new String(Files.readAllBytes(outside.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		}
	}

	@Test public void validUnicodeAndDottedNamesRetainRuntimePayloadAliases() throws Exception {
		String id = "模组.Test-1";
		File zip = archive(new JSONObject().put("id", id).put("pck_name", "payload"));
		ExtraSettingsRepository.PreparedModImport prepared = repository.prepareDownloadedModImport(zip, "mod.zip");
		try {
			assertEquals(1, prepared.incomingEntries.size());
			assertEquals(id, prepared.incomingEntries.get(0).modId);
			assertTrue(prepared.incomingEntries.get(0).hasDll);
			assertEquals("fixture-dll", new String(Files.readAllBytes(new File(prepared.stagingRoot, id + ".dll").toPath()), java.nio.charset.StandardCharsets.UTF_8));
			assertEquals(id, new JSONObject(new String(Files.readAllBytes(new File(prepared.stagingRoot, id + ".json").toPath()), java.nio.charset.StandardCharsets.UTF_8)).getString("id"));
			assertFalse(new File(prepared.stagingRoot, "mod_manifest.json").exists());
		} finally {
			repository.discardPreparedModImport(prepared);
		}
	}

	@Test public void existingSymlinkCannotSupplyPayloadFromOutsideModDirectory() throws Exception {
		File mods = repository.getModsRootDir();
		Files.createDirectories(mods.toPath());
		File outside = new File(context.getFilesDir(), "outside.dll");
		Files.write(outside.toPath(), ("private-bytes").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Files.write(new File(mods, "mod_manifest.json").toPath(), (new JSONObject().put("id", "SafeMod").put("pck_name", "payload").toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		Files.createSymbolicLink(new File(mods, "payload.dll").toPath(), outside.toPath());
		repository.ensureAppDirectories();
		assertFalse(new File(mods, "SafeMod.dll").exists());
		assertEquals("private-bytes", new String(Files.readAllBytes(outside.toPath()), java.nio.charset.StandardCharsets.UTF_8));
		assertTrue(new File(mods, "mod_manifest.json").isFile());
	}

	@Test public void workshopSourceMigrationRollsBackToSupplyStationInstall() throws Exception {
		String publishedFileId = "99001";
		String modId = "MigrationRollbackMod";
		File source = supplyStationInstall(publishedFileId, modId, "old-dll");
		ExtraSettingsRepository.PreparedModImport prepared = repository.prepareDownloadedModImport(
			archive(new JSONObject().put("id", modId).put("pck_name", "payload")),
			"mod.zip"
		);

		ExtraSettingsRepository.WorkshopModImportResult result = repository.commitPreparedWorkshopModMigration(
			prepared, "workshop", publishedFileId, "public-beta", source
		);
		File target = new File(repository.getModsRootDir(), "workshop/public-beta/" + publishedFileId);
		assertFalse(source.exists());
		assertTrue(new File(target, modId + ".dll").isFile());

		repository.rollbackWorkshopModImport(result);

		assertFalse(target.exists());
		assertEquals("old-dll", new String(Files.readAllBytes(new File(source, modId + ".dll").toPath()), StandardCharsets.UTF_8));
	}

	@Test public void finalizedWorkshopSourceMigrationRemovesSupplyStationInstall() throws Exception {
		String publishedFileId = "99002";
		String modId = "MigrationFinalizeMod";
		File source = supplyStationInstall(publishedFileId, modId, "old-dll");
		ExtraSettingsRepository.PreparedModImport prepared = repository.prepareDownloadedModImport(
			archive(new JSONObject().put("id", modId).put("pck_name", "payload")),
			"mod.zip"
		);

		ExtraSettingsRepository.WorkshopModImportResult result = repository.commitPreparedWorkshopModMigration(
			prepared, "workshop", publishedFileId, "public-beta", source
		);
		repository.finalizeWorkshopModImport(result);

		File target = new File(repository.getModsRootDir(), "workshop/public-beta/" + publishedFileId);
		assertFalse(source.exists());
		assertEquals("fixture-dll", new String(Files.readAllBytes(new File(target, modId + ".dll").toPath()), StandardCharsets.UTF_8));
		assertFalse(result.replacedBackupRoot.exists());
	}

	@Test public void recoveryDoesNotConsumeBackupWhileWorkshopUpdateIsPending() throws Exception {
		String publishedFileId = "99003";
		String modId = "PendingUpdateMod";
		File target = workshopInstall(publishedFileId, modId, "old-dll");
		ExtraSettingsRepository.PreparedModImport prepared = repository.prepareDownloadedModImport(
			archive(new JSONObject().put("id", modId).put("pck_name", "payload")),
			"mod.zip"
		);

		ExtraSettingsRepository.WorkshopModImportResult result = repository.commitPreparedWorkshopModImport(
			prepared, "workshop", publishedFileId, "public-beta", true
		);
		assertEquals("fixture-dll", read(new File(target, modId + ".dll")));
		assertTrue(result.backupRoot.isDirectory());

		repository.ensureAppDirectories();
		assertTrue(result.backupRoot.isDirectory());

		repository.rollbackWorkshopModImport(result);
		assertEquals("old-dll", read(new File(target, modId + ".dll")));
	}

	@Test public void workshopRecoveryWaitsForInstallTransactionLock() throws Exception {
		String publishedFileId = "99004";
		File branch = new File(repository.getModsRootDir(), "workshop/public-beta");
		Files.createDirectories(branch.toPath());
		File backup = new File(branch, "." + publishedFileId + ".backup-test");
		File incoming = new File(branch, "." + publishedFileId + ".incoming-test");
		Files.createDirectories(backup.toPath());
		Files.createDirectories(incoming.toPath());
		Files.write(new File(backup, "sentinel").toPath(), "old".getBytes(StandardCharsets.UTF_8));

		Field lockField = ExtraSettingsRepository.class.getDeclaredField("WORKSHOP_INSTALL_TRANSACTION_LOCK");
		lockField.setAccessible(true);
		Object lock = lockField.get(null);
		CountDownLatch started = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread recovery = new Thread(() -> {
			started.countDown();
			try {
				repository.ensureAppDirectories();
			} catch (Throwable throwable) {
				failure.set(throwable);
			} finally {
				finished.countDown();
			}
		});

		synchronized (lock) {
			recovery.start();
			assertTrue(started.await(2, TimeUnit.SECONDS));
			assertFalse(finished.await(200, TimeUnit.MILLISECONDS));
			assertTrue(backup.isDirectory());
			assertTrue(incoming.isDirectory());
		}
		assertTrue(finished.await(5, TimeUnit.SECONDS));
		assertNull(failure.get());
		assertTrue(new File(branch, publishedFileId + "/sentinel").isFile());
		assertFalse(backup.exists());
		assertFalse(incoming.exists());
	}

	@Test public void workshopMoveFailureIncludesFilesystemCauseAndPaths() {
		File source = new File(context.getFilesDir(), "missing-workshop-source");
		File target = new File(context.getFilesDir(), "missing-workshop-target");

		IOException failure = assertThrows(
			IOException.class,
			() -> ExtraSettingsRepository.moveWorkshopPath(source, target, "Unable to publish Workshop install")
		);

		assertTrue(failure.getMessage().contains("Unable to publish Workshop install"));
		assertTrue(failure.getMessage().contains(source.getAbsolutePath()));
		assertTrue(failure.getMessage().contains(target.getAbsolutePath()));
		assertNotNull(failure.getCause());
	}

	private File supplyStationInstall(String publishedFileId, String modId, String dllContent) throws Exception {
		File source = new File(repository.getModsRootDir(), "workshop/supply-station-default/" + publishedFileId);
		writeInstalledMod(source, modId, dllContent);
		return source;
	}

	private File workshopInstall(String publishedFileId, String modId, String dllContent) throws Exception {
		File target = repository.getWorkshopItemInstallDir("workshop", publishedFileId, "public-beta");
		writeInstalledMod(target, modId, dllContent);
		return target;
	}

	private void writeInstalledMod(File directory, String modId, String dllContent) throws Exception {
		Files.createDirectories(directory.toPath());
		Files.write(
			new File(directory, modId + ".json").toPath(),
			new JSONObject().put("id", modId).put("pck_name", modId).toString().getBytes(StandardCharsets.UTF_8)
		);
		Files.write(new File(directory, modId + ".dll").toPath(), dllContent.getBytes(StandardCharsets.UTF_8));
	}

	private String read(File file) throws Exception {
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private File archive(JSONObject manifest) throws Exception {
		File zip = File.createTempFile("mod-safety-", ".zip", context.getCacheDir());
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
			out.putNextEntry(new ZipEntry("mod_manifest.json"));
			out.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
			out.putNextEntry(new ZipEntry("payload.dll"));
			out.write("fixture-dll".getBytes(StandardCharsets.UTF_8));
			out.closeEntry();
		}
		return zip;
	}
}
