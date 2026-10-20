package org.schabi.newpipe.views.glass

import android.content.res.Configuration
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.schabi.newpipe.R
import org.schabi.newpipe.fragments.MainFragment
import org.schabi.newpipe.frost.LibraryHubFragment
import org.schabi.newpipe.local.bookmark.BookmarkFragment
import org.schabi.newpipe.local.feed.FeedFragment
import org.schabi.newpipe.local.history.StatisticsPlaylistFragment
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment
import org.schabi.newpipe.local.subscription.SubscriptionFragment
import org.schabi.newpipe.download.GlassDownloadFragment

/**
 * Adaptive navigation controller that seamlessly toggles between:
 * - Portrait: Grounded YouTube Liquid Glass Bottom Dock Bar with horizontal spring sliding pill
 * - Landscape: iPhone Duo Floating Liquid Glass Vertical Pill with vertical spring sliding pill
 *
 * Guarantees zero fragment stacking/overlap by managing tab fragments in a single transaction
 * with explicit show/hide lifecycle semantics.
 */
class AdaptiveGlassNavController(
    private val activity: FragmentActivity,
    private val portraitDockContainer: View,
    private val portraitDock: BottomNavigationView,
    private val portraitIndicator: View,
    private val landscapeDuoPill: View,
    private val landscapeIndicator: View,
    private val duoTabHome: View,
    private val duoTabSubs: View,
    private val duoTabDownloads: View,
    private val duoTabLibrary: View,
    private val duoIconHome: ImageView,
    private val duoIconSubs: ImageView,
    private val duoIconDownloads: ImageView,
    private val duoIconLibrary: ImageView,
    private val duoTextHome: TextView,
    private val duoTextSubs: TextView,
    private val duoTextDownloads: TextView,
    private val duoTextLibrary: TextView
) {

    companion object {
        const val TAB_HOME = 0
        const val TAB_SUBS = 1
        const val TAB_DOWNLOADS = 2
        const val TAB_LIBRARY = 3

        private const val TAG_HOME = "TAB_HOME"
        private const val TAG_SUBS = "TAB_SUBS"
        private const val TAG_DOWNLOADS = "TAB_DOWNLOADS"
        private const val TAG_LIBRARY = "TAB_LIBRARY"
    }

    private val fm: FragmentManager = activity.supportFragmentManager
    private var currentTab: Int = TAB_HOME
    private var isNavigating = false

    private val activeColor = ContextCompat.getColor(activity, R.color.liquid_glass_text_primary)
    private val inactiveColor = 0xA1A1AA.toInt() or 0xFF000000.toInt()

    init {
        setupPortraitDock()
        setupLandscapeDuoPill()
        updateOrientationMode(activity.resources.configuration.orientation)
    }

    private fun setupPortraitDock() {
        portraitDock.setOnItemSelectedListener { item ->
            if (isNavigating) return@setOnItemSelectedListener true
            val tab = when (item.itemId) {
                R.id.nav_home -> TAB_HOME
                R.id.nav_subscriptions -> TAB_SUBS
                R.id.nav_downloads -> TAB_DOWNLOADS
                R.id.nav_library -> TAB_LIBRARY
                else -> TAB_HOME
            }
            selectTab(tab, updateDock = false)
            true
        }
    }

    private fun setupLandscapeDuoPill() {
        duoTabHome.setOnClickListener { selectTab(TAB_HOME) }
        duoTabSubs.setOnClickListener { selectTab(TAB_SUBS) }
        duoTabDownloads.setOnClickListener { selectTab(TAB_DOWNLOADS) }
        duoTabLibrary.setOnClickListener { selectTab(TAB_LIBRARY) }
    }

    private var isPlayerActive: Boolean = false

    fun setPlayerActive(active: Boolean) {
        if (isPlayerActive != active) {
            isPlayerActive = active
            updateOrientationMode(activity.resources.configuration.orientation)
        }
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        updateOrientationMode(newConfig.orientation)
    }

    private fun updateOrientationMode(orientation: Int) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            portraitDockContainer.visibility = View.GONE
            if (isPlayerActive) {
                landscapeDuoPill.visibility = View.GONE
            } else {
                landscapeDuoPill.visibility = View.VISIBLE
                landscapeDuoPill.post { animateLandscapeIndicator(currentTab) }
            }
        } else {
            landscapeDuoPill.visibility = View.GONE
            portraitDockContainer.visibility = if (isPlayerActive) View.GONE else View.VISIBLE
            portraitDock.post { animatePortraitIndicator(currentTab) }
        }
    }

    fun selectTab(tabIndex: Int, updateDock: Boolean = true) {
        if (currentTab == tabIndex && isCurrentTabDisplayed(tabIndex)) {
            if (tabIndex == TAB_HOME) {
                val currentFrag = fm.findFragmentById(R.id.fragment_holder)
                if (currentFrag is MainFragment) {
                    currentFrag.refreshCurrentTab()
                }
            }
            return
        }
        currentTab = tabIndex

        // 1. Update Portrait Dock selection
        if (updateDock) {
            val menuItemId = when (tabIndex) {
                TAB_HOME -> R.id.nav_home
                TAB_SUBS -> R.id.nav_subscriptions
                TAB_DOWNLOADS -> R.id.nav_downloads
                TAB_LIBRARY -> R.id.nav_library
                else -> R.id.nav_home
            }
            isNavigating = true
            portraitDock.selectedItemId = menuItemId
            isNavigating = false
        }
        animatePortraitIndicator(tabIndex)

        // 2. Update Landscape Duo Pill presentation
        updateLandscapeDuoColors(tabIndex)
        animateLandscapeIndicator(tabIndex)

        // 3. Switch Fragment with clean show/hide semantics to prevent fragment stacking
        switchFragmentClean(tabIndex)
    }

    private fun isCurrentTabDisplayed(tabIndex: Int): Boolean {
        val currentFrag = fm.findFragmentById(R.id.fragment_holder) ?: return false
        return when (tabIndex) {
            TAB_HOME -> currentFrag is MainFragment
            TAB_SUBS -> currentFrag is SubscriptionFragment || currentFrag is FeedFragment
            TAB_DOWNLOADS -> currentFrag is GlassDownloadFragment
            TAB_LIBRARY -> currentFrag is LibraryHubFragment
            else -> false
        }
    }

    private fun switchFragmentClean(tabIndex: Int) {
        val tag = when (tabIndex) {
            TAB_HOME -> TAG_HOME
            TAB_SUBS -> TAG_SUBS
            TAB_DOWNLOADS -> TAG_DOWNLOADS
            TAB_LIBRARY -> TAG_LIBRARY
            else -> TAG_HOME
        }

        val allTags = listOf(TAG_HOME, TAG_SUBS, TAG_DOWNLOADS, TAG_LIBRARY)
        val transaction = fm.beginTransaction()

        // 1. Explicitly hide all attached tab fragments
        for (existingTag in allTags) {
            val existing = fm.findFragmentByTag(existingTag)
            if (existing != null && existing.isAdded && !existing.isHidden) {
                transaction.hide(existing)
            }
        }

        // 2. Show target fragment if already added, or add it with root background #090A0F
        val target = fm.findFragmentByTag(tag)
        if (target != null && target.isAdded) {
            transaction.show(target)
        } else {
            val newFragment: Fragment = when (tabIndex) {
                TAB_HOME -> MainFragment()
                TAB_SUBS -> SubscriptionFragment()
                TAB_DOWNLOADS -> GlassDownloadFragment()
                TAB_LIBRARY -> LibraryHubFragment()
                else -> MainFragment()
            }
            transaction.add(R.id.fragment_holder, newFragment, tag)
        }

        try {
            transaction.commitNowAllowingStateLoss()
        } catch (e: Exception) {
            transaction.commitAllowingStateLoss()
        }

        // Apply dark root background to guarantee zero bleed-through
        val current = fm.findFragmentByTag(tag)
        current?.view?.setBackgroundColor(0xFF090A0F.toInt())
    }

    private fun animatePortraitIndicator(tabIndex: Int) {
        val itemId = when (tabIndex) {
            TAB_HOME -> R.id.nav_home
            TAB_SUBS -> R.id.nav_subscriptions
            TAB_DOWNLOADS -> R.id.nav_downloads
            TAB_LIBRARY -> R.id.nav_library
            else -> R.id.nav_home
        }
        portraitDock.post {
            val itemView = portraitDock.findViewById<View>(itemId) ?: return@post
            val targetX = itemView.x + (itemView.width - portraitIndicator.width) / 2f
            if (portraitIndicator.visibility != View.VISIBLE) {
                portraitIndicator.x = targetX
                portraitIndicator.visibility = View.VISIBLE
                return@post
            }
            val spring = SpringAnimation(portraitIndicator, DynamicAnimation.TRANSLATION_X)
            val force = SpringForce(targetX).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
            spring.spring = force
            spring.start()
        }
    }

    private fun animateLandscapeIndicator(tabIndex: Int) {
        val targetView = when (tabIndex) {
            TAB_HOME -> duoTabHome
            TAB_SUBS -> duoTabSubs
            TAB_DOWNLOADS -> duoTabDownloads
            TAB_LIBRARY -> duoTabLibrary
            else -> duoTabHome
        }
        landscapeDuoPill.post {
            val targetY = targetView.top.toFloat()
            if (landscapeIndicator.visibility != View.VISIBLE) {
                landscapeIndicator.translationY = targetY
                landscapeIndicator.visibility = View.VISIBLE
                return@post
            }
            val spring = SpringAnimation(landscapeIndicator, DynamicAnimation.TRANSLATION_Y)
            val force = SpringForce(targetY).apply {
                stiffness = SpringForce.STIFFNESS_LOW
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
            spring.spring = force
            spring.start()
        }
    }

    private fun updateLandscapeDuoColors(tabIndex: Int) {
        duoIconHome.setColorFilter(if (tabIndex == TAB_HOME) activeColor else inactiveColor)
        duoTextHome.setTextColor(if (tabIndex == TAB_HOME) activeColor else inactiveColor)

        duoIconSubs.setColorFilter(if (tabIndex == TAB_SUBS) activeColor else inactiveColor)
        duoTextSubs.setTextColor(if (tabIndex == TAB_SUBS) activeColor else inactiveColor)

        duoIconDownloads.setColorFilter(if (tabIndex == TAB_DOWNLOADS) activeColor else inactiveColor)
        duoTextDownloads.setTextColor(if (tabIndex == TAB_DOWNLOADS) activeColor else inactiveColor)

        duoIconLibrary.setColorFilter(if (tabIndex == TAB_LIBRARY) activeColor else inactiveColor)
        duoTextLibrary.setTextColor(if (tabIndex == TAB_LIBRARY) activeColor else inactiveColor)
    }

    /**
     * Synchronizes bottom dock / duo pill when active fragment changes externally.
     */
    fun syncWithActiveFragment() {
        val fragment = fm.findFragmentById(R.id.fragment_holder) ?: return
        val targetTab = when (fragment) {
            is MainFragment -> TAB_HOME
            is SubscriptionFragment, is FeedFragment -> TAB_SUBS
            is GlassDownloadFragment -> TAB_DOWNLOADS
            is LibraryHubFragment, is BookmarkFragment, is LocalPlaylistFragment, is StatisticsPlaylistFragment -> TAB_LIBRARY
            else -> return
        }
        if (targetTab != currentTab) {
            currentTab = targetTab
            portraitDock.post { animatePortraitIndicator(targetTab) }
            landscapeDuoPill.post { animateLandscapeIndicator(targetTab) }
            updateLandscapeDuoColors(targetTab)
        }
    }
}
