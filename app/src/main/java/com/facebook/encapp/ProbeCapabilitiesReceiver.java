package com.facebook.encapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Exposes Encapp's probe contract to host tooling without starting the UI. */
public final class ProbeCapabilitiesReceiver extends BroadcastReceiver {
    public static final String ACTION =
            "com.facebook.encapp.action.QUERY_PROBE_CAPABILITIES";
    public static final String CONTRACT_KEY =
            "com.facebook.encapp.encoder_rc_probe_contract";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            setResultCode(Activity.RESULT_CANCELED);
            return;
        }
        int contract = 0;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle metadata = info.metaData;
            if (metadata != null) {
                contract = metadata.getInt(CONTRACT_KEY, 0);
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // The current package must exist; report contract zero if it cannot be read.
        }
        setResultCode(Activity.RESULT_OK);
        setResultData(CONTRACT_KEY + "=" + contract);
    }
}
