package com.nexo.presentation.screen.settings

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Where the About screen's "Contact" row sends you.
 *
 * NEXO ships outside any store, so there is no listing page and no support
 * desk: the project's issue tracker is the one channel that exists for every
 * user of a released APK. Building the intent here (instead of a hardcoded
 * `mailto:`) keeps the destination in one place and testable.
 */
object AboutContact {
    const val ISSUE_TRACKER_URL = "https://github.com/JuanCamacho198/NEXO/issues"

    /**
     * An intent that opens [ISSUE_TRACKER_URL] in a browser, or null when the
     * device has nothing that can handle it, which is the signal the About row
     * uses to stay inert instead of offering a dead tap.
     */
    fun openIssueTrackerIntent(context: Context): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(ISSUE_TRACKER_URL))
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }
}
