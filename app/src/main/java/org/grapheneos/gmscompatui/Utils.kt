package org.grapheneos.gmscompatui

import java.lang.IllegalStateException

fun assume(v: Boolean, msg: String = "failed assumption") {
    if (!v) {
        throw IllegalStateException(msg)
    }
}
