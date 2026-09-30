package com.nexo.presentation.screen.readium

import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import com.nexo.presentation.viewmodel.ReaderViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

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
): FragmentHostState {
    val containerReady = remember { mutableStateOf(false) }
    val navigatorFragment = remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val readingOrder = remember(publication) { publication.readingOrder }
    val navigatorFactory =
        remember(publication, navigatorConfig) {
            EpubNavigatorFactory(publication, navigatorConfig)
        }

    // ── Reset container readiness when the publication changes ──
    LaunchedEffect(publication) {
        val tag = "ReadiumNavigator"
        val existing = fragmentManager.findFragmentByTag(tag)
        if (existing != null) {
            fragmentManager.commit { remove(existing) }
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
                fragmentManager.commit { remove(frag) }
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
