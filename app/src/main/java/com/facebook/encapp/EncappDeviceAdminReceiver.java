package com.facebook.encapp;

import android.app.admin.DeviceAdminReceiver;

/**
 * Empty DeviceAdminReceiver — used solely so the app can call
 * {@link android.app.admin.DevicePolicyManager#lockNow()} to blank the
 * display panel during battery tests when test_setup.screen_off is set.
 *
 * Only the {@code force-lock} policy is requested (see res/xml/device_admin.xml),
 * which is the minimum needed for {@code lockNow()}.
 */
public class EncappDeviceAdminReceiver extends DeviceAdminReceiver {
}
