package org.schabi.newpipe.fragments

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapterMenuWorkaround
import androidx.preference.PreferenceManager
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentMainBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment
import org.schabi.newpipe.settings.tabs.Tab
import org.schabi.newpipe.settings.tabs.TabsManager
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.ServiceHelper
import java.util.ArrayList

/**
 * Main frontpage fragment hosting the home feeds without the legacy top tab bar.
 */
class MainFragment : BaseFragment() {

    private var binding: FragmentMainBinding? = null
    private var pagerAdapter: SelectedTabsPagerAdapter? = null

    private val tabsList = ArrayList<Tab>()
    private var tabsManager: TabsManager? = null

    private var hasTabsChanged = false

    private var prefs: SharedPreferences? = null
    private var youtubeRestrictedModeEnabled = false
    private var youtubeRestrictedModeEnabledKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        tabsManager = TabsManager.getManager(activity)
        tabsManager?.setSavedTabsListener {
            if (DEBUG) {
                Log.d(TAG, "TabsManager.SavedTabsChangeListener: onTabsChanged, isResumed = $isResumed")
            }
            if (isResumed) {
                setupTabs()
            } else {
                hasTabsChanged = true
            }
        }

        val context = requireContext()
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        youtubeRestrictedModeEnabledKey = getString(R.string.youtube_restricted_mode_enabled)
        youtubeRestrictedModeEnabled = prefs?.getBoolean(youtubeRestrictedModeEnabledKey, false) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        val bound = FragmentMainBinding.bind(rootView)
        binding = bound

        setupTabs()
        setupFilterStrip()
    }

    private fun setupFilterStrip() {
        val b = binding ?: return
        val categories = listOf("All", "Live", "Chemistry", "Gaming", "News", "Music", "Podcasts")
        val adapter = org.schabi.newpipe.views.glass.GlassFilterAdapter(categories) { category, index ->
            if (index == 0) {
                b.pager.currentItem = 0
            } else {
                try {
                    NavigationHelper.openSearchFragment(
                        parentFragmentManager,
                        ServiceHelper.getSelectedServiceId(requireContext()),
                        category
                    )
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        b.homeFilterStrip.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            requireContext(),
            androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
            false
        )
        b.homeFilterStrip.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val newYoutubeRestrictedModeEnabled =
            prefs?.getBoolean(youtubeRestrictedModeEnabledKey, false) ?: false
        if (youtubeRestrictedModeEnabled != newYoutubeRestrictedModeEnabled || hasTabsChanged) {
            youtubeRestrictedModeEnabled = newYoutubeRestrictedModeEnabled
            setupTabs()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tabsManager?.unsetSavedTabsListener()
        binding?.pager?.adapter = null
        binding = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_main_fragment, menu)
        activity?.supportActionBar?.setDisplayHomeAsUpEnabled(false)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_search) {
            try {
                activity?.let {
                    NavigationHelper.openSearchFragment(
                        getFM(),
                        ServiceHelper.getSelectedServiceId(it),
                        ""
                    )
                }
            } catch (e: Exception) {
                ErrorUtil.showUiErrorSnackbar(this, "Opening search fragment", e)
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupTabs() {
        val manager = tabsManager ?: return
        val currentBinding = binding ?: return

        tabsList.clear()
        tabsList.addAll(manager.tabs)

        if (pagerAdapter == null || pagerAdapter?.sameTabs(tabsList) != true) {
            pagerAdapter = SelectedTabsPagerAdapter(
                requireContext(),
                childFragmentManager,
                tabsList
            )
        }

        currentBinding.pager.adapter = null
        currentBinding.pager.adapter = pagerAdapter
        hasTabsChanged = false
    }

    fun commitPlaylistTabs() {
        pagerAdapter?.localPlaylistFragments?.forEach { it.saveImmediate() }
    }

    class SelectedTabsPagerAdapter(
        private val context: Context,
        fragmentManager: FragmentManager,
        tabsList: List<Tab>
    ) : FragmentStatePagerAdapterMenuWorkaround(
        fragmentManager,
        BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT
    ) {
        private val internalTabsList: List<Tab> = ArrayList(tabsList)
        val localPlaylistFragments: MutableList<LocalPlaylistFragment> = ArrayList()

        override fun getItem(position: Int): Fragment {
            val tab = internalTabsList[position]
            val fragment: Fragment = try {
                tab.getFragment(context)
            } catch (t: Throwable) {
                BlankFragment(
                    ErrorInfo(
                        t,
                        UserAction.GETTING_MAIN_SCREEN_TAB,
                        "Tab ${tab.javaClass.simpleName}:${tab.getTabName(context)}"
                    )
                )
            }

            if (fragment is BaseFragment) {
                fragment.useAsFrontPage(true)
            }

            if (fragment is LocalPlaylistFragment) {
                localPlaylistFragments.add(fragment)
            }

            return fragment
        }

        override fun getItemPosition(obj: Any): Int {
            return POSITION_NONE
        }

        override fun getCount(): Int {
            return internalTabsList.size
        }

        fun sameTabs(tabsToCompare: List<Tab>): Boolean {
            return internalTabsList == tabsToCompare
        }
    }

    companion object {
        private const val TAG = "MainFragment"
        private val DEBUG = org.schabi.newpipe.MainActivity.DEBUG
    }
}
