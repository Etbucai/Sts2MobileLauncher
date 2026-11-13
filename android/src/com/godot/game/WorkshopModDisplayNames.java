package com.godot.game;

import android.text.TextUtils;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkshopModDisplayNames {
	private WorkshopModDisplayNames() {}

	static Map<ExtraSettingsRepository.ModEntry, Source> resolve(
		List<ExtraSettingsRepository.ModEntry> mods,
		List<SteamWorkshopLibrary.Entry> workshopEntries,
		SteamWorkshopLibrary.Scope currentScope
	) {
		Map<ExtraSettingsRepository.ModEntry, Source> result = new HashMap<>();
		if (mods == null || workshopEntries == null || currentScope == null || currentScope.rootDir == null) {
			return result;
		}
		for (SteamWorkshopLibrary.Entry workshopEntry : workshopEntries) {
			String workshopTitle = workshopEntry == null || workshopEntry.title == null ? "" : workshopEntry.title.trim();
			if (!belongsToScope(workshopEntry, currentScope) || TextUtils.isEmpty(workshopTitle)) {
				continue;
			}
			Set<ExtraSettingsRepository.ModEntry> matchedMods = new LinkedHashSet<>();
			for (File installRoot : installRoots(workshopEntry.installedRootPath)) {
				if (!isSameOrDescendant(installRoot, currentScope.rootDir)) {
					continue;
				}
				for (ExtraSettingsRepository.ModEntry mod : mods) {
					if (mod != null && mod.manifestFile != null && isSameOrDescendant(mod.manifestFile, installRoot)) {
						matchedMods.add(mod);
					}
				}
			}
			Source source = new Source(
				workshopEntry.publishedFileId,
				workshopTitle,
				matchedMods.size(),
				workshopEntry.installedAtMs
			);
			for (ExtraSettingsRepository.ModEntry mod : matchedMods) {
				Source existing = result.get(mod);
				if (existing == null || source.installedAtMs > existing.installedAtMs) {
					result.put(mod, source);
				}
			}
		}
		return result;
	}

	private static boolean belongsToScope(SteamWorkshopLibrary.Entry entry, SteamWorkshopLibrary.Scope scope) {
		return entry != null && scope.key.equals(entry.scopeKey);
	}

	private static Set<File> installRoots(String installedRootPath) {
		Set<File> roots = new LinkedHashSet<>();
		if (TextUtils.isEmpty(installedRootPath)) {
			return roots;
		}
		for (String line : installedRootPath.split("\\n")) {
			String path = line == null ? "" : line.trim();
			if (!TextUtils.isEmpty(path)) {
				roots.add(new File(path));
			}
		}
		return roots;
	}

	private static boolean isSameOrDescendant(File file, File root) {
		if (file == null || root == null) {
			return false;
		}
		try {
			String rootPath = root.getCanonicalPath();
			String filePath = file.getCanonicalPath();
			return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
		} catch (IOException ignored) {
			return false;
		}
	}

	static final class Source {
		final String publishedFileId;
		final String title;
		final int modCount;
		private final long installedAtMs;

		Source(String publishedFileId, String title, int modCount, long installedAtMs) {
			this.publishedFileId = publishedFileId == null ? "" : publishedFileId.trim();
			this.title = title == null ? "" : title.trim();
			this.modCount = Math.max(0, modCount);
			this.installedAtMs = Math.max(0L, installedAtMs);
		}

		String displayName(ExtraSettingsRepository.ModEntry mod) {
			String manifestName = mod == null || mod.displayName == null ? "" : mod.displayName.trim();
			if (modCount <= 1 || TextUtils.isEmpty(manifestName)) {
				return title;
			}
			return title + " · " + manifestName;
		}
	}
}
