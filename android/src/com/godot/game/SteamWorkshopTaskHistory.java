package com.godot.game;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SteamWorkshopTaskHistory {
	private static final String FILE_NAME = "task-history.json";
	public static final String STATE_FAILED = "failed";
	public static final String STATE_STOPPED = "stopped";

	private final SteamWorkshopLibrary library;
	private final File rootDir;
	private final File historyFile;

	public SteamWorkshopTaskHistory(Context context) {
		Context appContext = context.getApplicationContext();
		this.library = new SteamWorkshopLibrary(appContext);
		this.rootDir = new File(new File(appContext.getFilesDir(), "workshop"), "library");
		this.historyFile = new File(rootDir, FILE_NAME);
	}

	public synchronized List<Failure> listFailures() {
		List<Failure> failures = readFailures();
		failures.sort(Comparator.comparingLong((Failure failure) -> failure.failedAtMs).reversed());
		return failures;
	}

	public synchronized Failure recordFailure(
		SteamWorkshopCatalog.Item item,
		SteamWorkshopDownloader.BranchOption option,
		boolean updateExisting,
		String error
	) throws Exception {
		return recordTask(item, option, updateExisting, error, "", STATE_FAILED);
	}

	public synchronized Failure recordTask(
		SteamWorkshopCatalog.Item item,
		SteamWorkshopDownloader.BranchOption option,
		boolean updateExisting,
		String error,
		String resumeDirectoryId,
		String state
	) throws Exception {
		SteamWorkshopLibrary.Scope scope = library.getCurrentScope();
		Failure failure = Failure.create(
			item,
			option,
			updateExisting,
			error,
			System.currentTimeMillis(),
			scope.key,
			resumeDirectoryId,
			state
		);
		Map<String, Failure> byKey = new LinkedHashMap<>();
		for (Failure existing : readFailures()) {
			if (!existing.key().equals(failure.key())) {
				byKey.put(existing.key(), existing);
			} else if (TextUtils.isEmpty(failure.resumeDirectoryId)
				&& !TextUtils.isEmpty(existing.resumeDirectoryId)
				&& failure.hasSameContentIdentity(existing)) {
				failure = failure.withResumeDirectoryId(existing.resumeDirectoryId);
			}
		}
		byKey.put(failure.key(), failure);
		writeFailures(new ArrayList<>(byKey.values()));
		return failure;
	}

	public synchronized void removeFailure(Failure target) throws Exception {
		if (target == null) {
			return;
		}
		List<Failure> kept = new ArrayList<>();
		for (Failure failure : readFailures()) {
			if (!target.key().equals(failure.key())) {
				kept.add(failure);
			}
		}
		writeFailures(kept);
	}

	public synchronized void removeForSuccess(String publishedFileId, String workshopBranch, String scopeKey) throws Exception {
		List<Failure> kept = new ArrayList<>();
		for (Failure failure : readFailures()) {
			boolean sameTask = failure.publishedFileId.equals(publishedFileId)
				&& normalizeBranch(failure.workshopBranch).equals(normalizeBranch(workshopBranch))
				&& failure.scopeKey.equals(scopeKey);
			if (!sameTask) {
				kept.add(failure);
			}
		}
		writeFailures(kept);
	}

	public synchronized List<String> listProtectedResumeDirectoryIds() {
		List<String> directoryIds = new ArrayList<>();
		for (Failure failure : readFailures()) {
			if (isSafeResumeDirectoryId(failure.resumeDirectoryId) && !directoryIds.contains(failure.resumeDirectoryId)) {
				directoryIds.add(failure.resumeDirectoryId);
			}
		}
		return directoryIds;
	}

	static boolean isSafeResumeDirectoryId(String value) {
		if (TextUtils.isEmpty(value) || value.length() > 160) {
			return false;
		}
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (!Character.isLetterOrDigit(character) && character != '.' && character != '_' && character != '-') {
				return false;
			}
		}
		return true;
	}

	private List<Failure> readFailures() {
		if (!historyFile.isFile()) {
			return new ArrayList<>();
		}
		try {
			String content = new String(java.nio.file.Files.readAllBytes(historyFile.toPath()), StandardCharsets.UTF_8);
			JSONArray array = new JSONArray(content);
			Map<String, Failure> byKey = new LinkedHashMap<>();
			for (int index = 0; index < array.length(); index++) {
				JSONObject object = array.optJSONObject(index);
				if (object == null) {
					continue;
				}
				Failure failure = Failure.fromJson(object);
				Failure previous = byKey.get(failure.key());
				if (previous == null || failure.failedAtMs >= previous.failedAtMs) {
					byKey.put(failure.key(), failure);
				}
			}
			return new ArrayList<>(byKey.values());
		} catch (Exception ignored) {
			return new ArrayList<>();
		}
	}

	private void writeFailures(List<Failure> failures) throws Exception {
		ensureRoot();
		JSONArray array = new JSONArray();
		for (Failure failure : failures) {
			array.put(failure.toJson());
		}
		File tempFile = new File(rootDir, FILE_NAME + ".tmp");
		File backupFile = new File(rootDir, FILE_NAME + ".bak");
		byte[] bytes = array.toString(2).getBytes(StandardCharsets.UTF_8);
		try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
			outputStream.write(bytes);
			outputStream.getFD().sync();
		}
		if (backupFile.exists() && !backupFile.delete()) {
			throw new IOException("Unable to replace Workshop task history backup: " + backupFile.getAbsolutePath());
		}
		if (historyFile.isFile() && !historyFile.renameTo(backupFile)) {
			tempFile.delete();
			throw new IOException("Unable to stage Workshop task history: " + historyFile.getAbsolutePath());
		}
		if (!tempFile.renameTo(historyFile)) {
			if (backupFile.isFile()) {
				backupFile.renameTo(historyFile);
			}
			tempFile.delete();
			throw new IOException("Unable to publish Workshop task history: " + historyFile.getAbsolutePath());
		}
		if (backupFile.exists()) {
			backupFile.delete();
		}
	}

	private void ensureRoot() throws IOException {
		if (!rootDir.isDirectory() && !rootDir.mkdirs() && !rootDir.isDirectory()) {
			throw new IOException("Unable to create Workshop task history directory: " + rootDir.getAbsolutePath());
		}
	}

	private static String normalizeBranch(String branch) {
		String value = branch == null ? "" : branch.trim();
		return value.isEmpty() ? "public" : value;
	}

	private static String keyPart(String value) {
		String normalized = value == null ? "" : value.trim();
		return normalized.isEmpty() ? "unknown" : normalized.replace('\\', '_').replace('/', '_').replace('@', '_');
	}

	public static final class Failure {
		public final int appId;
		public final String publishedFileId;
		public final String title;
		public final String description;
		public final String previewUrl;
		public final long fileSizeBytes;
		public final long remoteUpdatedAtMs;
		public final long failedAtMs;
		public final String error;
		public final String workshopBranch;
		public final String manifestId;
		public final String depotId;
		public final String resolutionSource;
		public final String fallbackReason;
		public final String matchedBranchMin;
		public final String matchedBranchMax;
		public final long snapshotTimeEpochSeconds;
		public final boolean updateExisting;
		public final String scopeKey;
		public final String resumeDirectoryId;
		public final String state;

		Failure(
			int appId,
			String publishedFileId,
			String title,
			String description,
			String previewUrl,
			long fileSizeBytes,
			long remoteUpdatedAtMs,
			long failedAtMs,
			String error,
			String workshopBranch,
			String manifestId,
			String depotId,
			String resolutionSource,
			String fallbackReason,
			String matchedBranchMin,
			String matchedBranchMax,
			long snapshotTimeEpochSeconds,
			boolean updateExisting,
			String scopeKey,
			String resumeDirectoryId,
			String state
		) {
			this.appId = appId;
			this.publishedFileId = publishedFileId == null ? "" : publishedFileId;
			this.title = title == null ? "" : title;
			this.description = description == null ? "" : description;
			this.previewUrl = previewUrl == null ? "" : previewUrl;
			this.fileSizeBytes = Math.max(0L, fileSizeBytes);
			this.remoteUpdatedAtMs = Math.max(0L, remoteUpdatedAtMs);
			this.failedAtMs = Math.max(0L, failedAtMs);
			this.error = error == null ? "" : error;
			this.workshopBranch = normalizeBranch(workshopBranch);
			this.manifestId = manifestId == null ? "" : manifestId;
			this.depotId = depotId == null ? "" : depotId;
			this.resolutionSource = resolutionSource == null ? "" : resolutionSource;
			this.fallbackReason = fallbackReason == null ? "" : fallbackReason;
			this.matchedBranchMin = matchedBranchMin == null ? "" : matchedBranchMin;
			this.matchedBranchMax = matchedBranchMax == null ? "" : matchedBranchMax;
			this.snapshotTimeEpochSeconds = Math.max(0L, snapshotTimeEpochSeconds);
			this.updateExisting = updateExisting;
			this.scopeKey = TextUtils.isEmpty(scopeKey) ? "legacy" : scopeKey;
			this.resumeDirectoryId = isSafeResumeDirectoryId(resumeDirectoryId) ? resumeDirectoryId : "";
			this.state = STATE_STOPPED.equals(state) ? STATE_STOPPED : STATE_FAILED;
		}

		static Failure create(
			SteamWorkshopCatalog.Item item,
			SteamWorkshopDownloader.BranchOption option,
			boolean updateExisting,
			String error,
			long failedAtMs,
			String scopeKey
		) {
			return create(item, option, updateExisting, error, failedAtMs, scopeKey, "", STATE_FAILED);
		}

		static Failure create(
			SteamWorkshopCatalog.Item item,
			SteamWorkshopDownloader.BranchOption option,
			boolean updateExisting,
			String error,
			long failedAtMs,
			String scopeKey,
			String resumeDirectoryId,
			String state
		) {
			String branch = option == null ? "public" : option.getBranch();
			return new Failure(
				item == null ? SteamWorkshopPreferences.DEFAULT_APP_ID : item.getAppId(),
				item == null ? "" : item.getPublishedFileId(),
				item == null ? "" : item.getTitle(),
				item == null ? "" : item.getDescription(),
				item == null ? "" : item.getPreviewUrl(),
				item == null ? 0L : item.getFileSizeBytes(),
				item == null ? 0L : item.getTimeUpdatedEpochSeconds() * 1000L,
				failedAtMs,
				error,
				branch,
				option == null ? "" : option.getManifestId(),
				option == null ? "" : option.getDepotId(),
				option == null ? "" : option.getSource(),
				option == null ? "" : option.getFallbackReason(),
				option == null ? "" : option.getMatchedBranchMin(),
				option == null ? "" : option.getMatchedBranchMax(),
				option == null ? 0L : option.getTimestampEpochSeconds(),
				updateExisting,
				scopeKey,
				resumeDirectoryId,
				state
			);
		}

		String key() {
			return keyPart(scopeKey) + ":" + keyPart(publishedFileId) + "@" + keyPart(normalizeBranch(workshopBranch));
		}

		SteamWorkshopCatalog.Item toItem() {
			return new SteamWorkshopCatalog.Item(
				appId,
				publishedFileId,
				title,
				"",
				description,
				previewUrl,
				0L,
				fileSizeBytes,
				0,
				0,
				remoteUpdatedAtMs / 1000L
			);
		}

		SteamWorkshopDownloader.BranchOption toBranchOption() {
			return new SteamWorkshopDownloader.BranchOption(
				workshopBranch,
				manifestId,
				depotId,
				resolutionSource,
				fallbackReason,
				matchedBranchMin,
				matchedBranchMax,
				snapshotTimeEpochSeconds,
				title,
				fileSizeBytes
			);
		}

		private boolean hasSameContentIdentity(Failure other) {
			return other != null
				&& normalizeBranch(workshopBranch).equals(normalizeBranch(other.workshopBranch))
				&& manifestId.equals(other.manifestId)
				&& depotId.equals(other.depotId);
		}

		private Failure withResumeDirectoryId(String directoryId) {
			return new Failure(
				appId,
				publishedFileId,
				title,
				description,
				previewUrl,
				fileSizeBytes,
				remoteUpdatedAtMs,
				failedAtMs,
				error,
				workshopBranch,
				manifestId,
				depotId,
				resolutionSource,
				fallbackReason,
				matchedBranchMin,
				matchedBranchMax,
				snapshotTimeEpochSeconds,
				updateExisting,
				scopeKey,
				directoryId,
				state
			);
		}

		JSONObject toJson() throws Exception {
			JSONObject object = new JSONObject();
			object.put("schema_version", 2);
			object.put("app_id", appId);
			object.put("published_file_id", publishedFileId);
			object.put("title", title);
			object.put("description", description);
			object.put("preview_url", previewUrl);
			object.put("file_size_bytes", fileSizeBytes);
			object.put("remote_updated_at_ms", remoteUpdatedAtMs);
			object.put("failed_at_ms", failedAtMs);
			object.put("error", error);
			object.put("workshop_branch", workshopBranch);
			object.put("manifest_id", manifestId);
			object.put("depot_id", depotId);
			object.put("resolution_source", resolutionSource);
			object.put("fallback_reason", fallbackReason);
			object.put("matched_branch_min", matchedBranchMin);
			object.put("matched_branch_max", matchedBranchMax);
			object.put("snapshot_time_epoch_seconds", snapshotTimeEpochSeconds);
			object.put("update_existing", updateExisting);
			object.put("mods_scope", scopeKey);
			object.put("resume_directory_id", resumeDirectoryId);
			object.put("state", state);
			return object;
		}

		static Failure fromJson(JSONObject object) {
			return new Failure(
				object.optInt("app_id", SteamWorkshopPreferences.DEFAULT_APP_ID),
				object.optString("published_file_id", ""),
				object.optString("title", ""),
				object.optString("description", ""),
				object.optString("preview_url", ""),
				object.optLong("file_size_bytes", 0L),
				object.optLong("remote_updated_at_ms", 0L),
				object.optLong("failed_at_ms", 0L),
				object.optString("error", ""),
				object.optString("workshop_branch", "public"),
				object.optString("manifest_id", ""),
				object.optString("depot_id", ""),
				object.optString("resolution_source", ""),
				object.optString("fallback_reason", ""),
				object.optString("matched_branch_min", ""),
				object.optString("matched_branch_max", ""),
				object.optLong("snapshot_time_epoch_seconds", 0L),
				object.optBoolean("update_existing", false),
				object.optString("mods_scope", "legacy"),
				object.optString("resume_directory_id", ""),
				object.optString("state", STATE_FAILED)
			);
		}
	}
}
