package com.nextpage.data.update

fun isUpdateAvailable(
    feedVersionCode: Int,
    installedVersionCode: Int,
): Boolean = feedVersionCode > installedVersionCode
