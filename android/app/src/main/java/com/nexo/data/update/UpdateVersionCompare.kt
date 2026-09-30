package com.nexo.data.update

fun isUpdateAvailable(
    feedVersionCode: Int,
    installedVersionCode: Int,
): Boolean = feedVersionCode > installedVersionCode
