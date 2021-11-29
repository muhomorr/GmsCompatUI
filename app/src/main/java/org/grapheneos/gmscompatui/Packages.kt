package org.grapheneos.gmscompatui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager

const val GSF_PACKAGE_NAME = "com.google.android.gsf"
const val GMS_PACKAGE_NAME = "com.google.android.gms"
const val PLAY_STORE_PACKAGE_NAME = "com.android.vending"

object Packages {
    val NAMES = arrayOf(GSF_PACKAGE_NAME, GMS_PACKAGE_NAME, PLAY_STORE_PACKAGE_NAME)

    fun isInstalled(pkgName: String, matchAnyUser: Boolean = false): Boolean {
        return versionCode(pkgName) != null
    }

    @SuppressLint("WrongConstant")
    fun info(pkgName: String, matchAnyUser: Boolean = false): PackageInfo? {
        try {
            var flags = 0
            if (matchAnyUser) {
                assume(BuildConfig.hasSystemPrivileges)
                flags = 0x00400000 // PackageManager.MATCH_ANY_USER
            }
            return appCtx.packageManager.getPackageInfo(pkgName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
    }

    fun versionCode(pkgName: String, matchAnyUser: Boolean = false): Long? {
        return info(pkgName, matchAnyUser)?.longVersionCode
    }

    fun isVersionCodeAtLeast(pkgName: String, ver: Long, matchAnyUser: Boolean = false): Boolean {
        val cur = versionCode(pkgName, matchAnyUser);
        return cur != null && cur >= ver
    }

    fun areInstalled(pkgNames: Array<String>): Boolean {
        pkgNames.forEach {
            if (!isInstalled(it)) {
                return false
            }
        }
        return true
    }
}
