package com.godot.game;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SteamWorkshopDeepLinkTest {
	@Test public void itemDetailIntentTargetsWorkshopAndCarriesTrimmedId() {
		Context context = RuntimeEnvironment.getApplication();

		Intent intent = SteamWorkshopActivity.createItemDetailIntent(context, " 3827534573 ");

		assertEquals(SteamWorkshopActivity.class.getName(), intent.getComponent().getClassName());
		assertEquals("3827534573", SteamWorkshopActivity.requestedItemId(intent));
	}
}
