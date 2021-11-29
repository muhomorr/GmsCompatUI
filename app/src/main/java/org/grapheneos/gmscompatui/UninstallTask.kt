package org.grapheneos.gmscompatui

import android.annotation.SuppressLint

class UninstallTask : Runnable {
    @SuppressLint("MissingPermission")
    override fun run() {
        val installer = appCtx.packageManager.packageInstaller
        Packages.NAMES.reversedArray().forEach { pkgName ->
            if (!Packages.isInstalled(pkgName)) {
                return@forEach
            }
            InstallerResultReceiver().use {
                if (BuildConfig.hasSystemPrivileges) {
                    installer.uninstallExistingPackage(pkgName, it.getIntentSender())
                } else {
                    installer.uninstall(pkgName, it.getIntentSender())
                }
            }
        }
    }
}
