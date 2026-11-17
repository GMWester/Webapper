package com.webapper.installer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Installs generated APKs with the PackageInstaller session API and reports the outcome. */
public class InstallReceiver extends BroadcastReceiver {
    static final String EXTRA_PACKAGE = "com.webapper.installer.PACKAGE";

    interface Listener {
        void onInstallFinished(boolean success, String packageName, String message);
    }

    static Listener listener;

    static void install(Context ctx, File apk, String packageName) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(ctx, InstallReceiver.class).putExtra(EXTRA_PACKAGE, packageName);
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? android.app.PendingIntent.FLAG_MUTABLE : 0);
            android.app.PendingIntent pending = android.app.PendingIntent.getBroadcast(ctx, id, i, flags);
            s.commit(pending.getIntentSender());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String pkg = intent.getStringExtra(EXTRA_PACKAGE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }
        boolean ok = status == PackageInstaller.STATUS_SUCCESS;
        String msg = ok ? "Installed" : intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
            msg = "An app with this name exists but was signed differently. Uninstall it first.";
        }
        if (listener != null) {
            listener.onInstallFinished(ok, pkg, msg);
        } else {
            Toast.makeText(context, ok ? "App installed" : "Install failed: " + msg, Toast.LENGTH_LONG).show();
        }
    }
}
