package com.nexo.presentation.screen.readium

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.nexo.presentation.viewmodel.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl

private const val FRAGMENT_COMMIT_MS = 200L
private const val TAG = "ReadiumReaderContent"

data class FragmentHostState(
    val navigatorFragment: MutableState<EpubNavigatorFragment?>,
    val containerReady: MutableState<Boolean>,
    val readingOrder: List<Link>,
    val containerId: Int,
)

/**
 * Remembers and manages the Readium fragment host.
 *
 * Owns:
 * - Publication-change stale fragment removal (LaunchedEffect(publication))
 * - Gated commit of [EpubNavigatorFragment] after 200ms (LaunchedEffect(publication, containerReady))
 * - currentLocator → ViewModel forwarding
 * - Cleanup on dispose
 * - Reading order and container id
 *
 * Container readiness is derived ONLY from [Modifier.onGloballyPositioned]
 * in [FragmentHostView]; never from OnAttachStateChangeListener.
 */
@Composable
fun rememberFragmentHost(
    publication: Publication,
    navigatorConfig: EpubNavigatorFactory.Configuration,
    fragmentManager: FragmentManager,
    containerId: Int,
    initialLocator: Locator?,
    viewModel: ReaderViewModel,
    initialPreferences: EpubPreferences = EpubPreferences(),
    onInternalLink: (link: Link, noteHtml: String?) -> Unit = { _, _ -> },
    onExternalLink: (url: String) -> Unit = {},
): FragmentHostState {
    val containerReady = remember { mutableStateOf(false) }
    val navigatorFragment = remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val readingOrder = remember(publication) { publication.readingOrder }
    val navigatorFactory =
        remember(publication, navigatorConfig) {
            EpubNavigatorFactory(publication, navigatorConfig)
        }

    // Links inside the book (footnote markers like [3], cross references) are
    // never followed on the tap itself: a stray touch on a tiny superscript
    // used to throw the reader to the end of the chapter. The tap is handed to
    // the caller, which offers the note and a way to go there instead. Readium
    // passes the note's HTML when the book marks it (epub:type="noteref").
    // Callbacks arrive from the WebView's JS bridge, so they are posted to main.
    val currentOnInternalLink by rememberUpdatedState(onInternalLink)
    val currentOnExternalLink by rememberUpdatedState(onExternalLink)
    val linkListener =
        remember {
            val main = Handler(Looper.getMainLooper())
            object : EpubNavigatorFragment.Listener {
                override fun shouldFollowInternalLink(
                    link: Link,
                    context: HyperlinkNavigator.LinkContext?,
                ): Boolean {
                    val noteHtml = (context as? HyperlinkNavigator.FootnoteContext)?.noteContent
                    main.post { currentOnInternalLink(link, noteHtml) }
                    return false
                }

                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    main.post { currentOnExternalLink(url.toString()) }
                }
            }
        }

    // ── Reset container readiness when the publication changes ──
    LaunchedEffect(publication) {
        val tag = "ReadiumNavigator"
        val existing = fragmentManager.findFragmentByTag(tag)
        if (existing != null) {
            // The publication is published asynchronously by a viewModelScope
            // coroutine, so this effect can observe a new publication while the
            // host activity is already past onSaveInstanceState. Use the same
            // state-loss-tolerant commit as onDispose: the stale fragment is
            // being replaced and its removal state is not worth crashing over.
            fragmentManager.commit(allowStateLoss = true) { remove(existing) }
            fragmentManager.executePendingTransactions()
        }
        containerReady.value = false
    }

    // ── Commit EpubNavigatorFragment ──────────────────────────────
    LaunchedEffect(publication, containerReady.value) {
        if (!containerReady.value) return@LaunchedEffect
        val tag = "ReadiumNavigator"
        val resolvedLocator =
            initialLocator
                ?: publication.readingOrder.firstOrNull()?.let {
                    publication.locatorFromLink(it)
                }
        if (resolvedLocator == null) return@LaunchedEffect
        val factory =
            navigatorFactory.createFragmentFactory(
                initialLocator = resolvedLocator,
                // The reader's saved appearance from the first frame, instead of
                // Readium's defaults until the next settings change.
                initialPreferences = initialPreferences,
                listener = linkListener,
            )
        fragmentManager.fragmentFactory = factory
        fragmentManager.commit {
            add(containerId, EpubNavigatorFragment::class.java, Bundle(), tag)
        }
        delay(FRAGMENT_COMMIT_MS)
        navigatorFragment.value = fragmentManager.findFragmentByTag(tag) as? EpubNavigatorFragment
    }

    // ── currentLocator → ViewModel ────────────────────────────────
    LaunchedEffect(navigatorFragment.value) {
        val frag = navigatorFragment.value ?: return@LaunchedEffect
        try {
            @Suppress("UNCHECKED_CAST")
            val locatorFlow = frag.currentLocator as StateFlow<Locator>
            locatorFlow.collect { locator ->
                viewModel.lifecycleHolder.onReadiumLocatorChanged(locator)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to collect currentLocator from EpubNavigatorFragment", e)
        }
    }

    // ── Clean up ──────────────────────────────────────────────────
    DisposableEffect(Unit) {
        onDispose {
            val frag = navigatorFragment.value
            if (frag != null && frag.isAdded) {
                // onDispose runs while the host activity may already be past
                // onSaveInstanceState (e.g. during a config-change relaunch), where a
                // state-saving commit throws IllegalStateException and crashes
                // onDestroy. The fragment dies with the activity either way, so
                // there is no state worth preserving here.
                fragmentManager.commit(allowStateLoss = true) { remove(frag) }
            }
        }
    }

    return FragmentHostState(
        navigatorFragment = navigatorFragment,
        containerReady = containerReady,
        readingOrder = readingOrder,
        containerId = containerId,
    )
}

/**
 * Renders the [FragmentContainerView] host.
 *
 * Uses [Modifier.onGloballyPositioned] to derive containerReady — never
 * OnAttachStateChangeListener — and forwards viewport size to ViewModel.
 */
@Composable
fun FragmentHostState.FragmentHostView(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
            }
        },
        modifier =
            modifier.onGloballyPositioned { coordinates ->
                if (!containerReady.value) containerReady.value = true
                viewModel.lifecycleHolder.onReadiumViewportChanged(coordinates.size.height, coordinates.size.width)
            },
    )
}
