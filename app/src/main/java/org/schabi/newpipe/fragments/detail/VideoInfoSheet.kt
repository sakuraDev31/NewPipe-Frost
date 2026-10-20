package org.schabi.newpipe.fragments.detail

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.fragments.list.comments.CommentsFragment
import org.schabi.newpipe.fragments.list.videos.RelatedItemsFragment
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.InfoCache

/** On-demand detail content; never owns, recreates, or pauses the playback service. */
class VideoInfoSheet : BottomSheetDialogFragment() {
    private var request: Disposable? = null
    private var navigationManager: FragmentManager? = null
    private val navigationListener = FragmentManager.OnBackStackChangedListener { dismissAllowingStateLoss() }
    private var pendingComment: CommentsInfoItem? = null
    private var observedAdapter: RecyclerView.Adapter<*>? = null
    private val commentObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = tryScrollToComment()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = tryScrollToComment()
    }
    private val childCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, fragment: Fragment, view: View, state: Bundle?) {
            if (fragment is CommentsFragment) watchComments(fragment)
        }
    }

    fun scrollToComment(comment: CommentsInfoItem) {
        pendingComment = comment
        arguments?.putSerializable("comment", comment)
        if (isAdded) {
            (childFragmentManager.findFragmentById(R.id.detail_panel_content) as? CommentsFragment)
                ?.let(::watchComments)
        }
    }

    private fun watchComments(fragment: CommentsFragment) {
        val adapter = fragment.view?.findViewById<RecyclerView>(R.id.items_list)?.adapter
        if (adapter !== observedAdapter) {
            observedAdapter?.unregisterAdapterDataObserver(commentObserver)
            observedAdapter = adapter
            adapter?.registerAdapterDataObserver(commentObserver)
        }
        tryScrollToComment()
    }

    private fun tryScrollToComment() {
        val comment = pendingComment ?: return
        val fragment = childFragmentManager.findFragmentById(R.id.detail_panel_content)
        if (fragment is CommentsFragment && fragment.scrollToComment(comment)) {
            pendingComment = null
            arguments?.remove("comment")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val row = (48 * density).toInt()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF10121A.toInt())
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    setText(when (requireArguments().getString("panel")) {
                        COMMENTS -> R.string.comments_tab_description
                        RELATED -> R.string.related_items_tab_description
                        else -> R.string.description_tab_description
                    })
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    setPadding((16 * density).toInt(), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, row, 1f))
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(Color.WHITE)
                    setBackgroundColor(Color.TRANSPARENT)
                    contentDescription = getString(R.string.close)
                    setOnClickListener { dismiss() }
                }, LinearLayout.LayoutParams(row, row))
            })
            addView(FrameLayout(context).apply { id = R.id.detail_panel_content },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.registerFragmentLifecycleCallbacks(childCallbacks, false)
        val args = requireArguments()
        @Suppress("DEPRECATION")
        val targetComment = args.getSerializable("comment") as? CommentsInfoItem
        pendingComment = targetComment
        val existing = childFragmentManager.findFragmentById(R.id.detail_panel_content)
        if (existing != null) {
            if (existing is CommentsFragment) watchComments(existing)
            return
        }
        val service = args.getInt("service")
        val url = args.getString("url") ?: return
        if (args.getString("panel") == COMMENTS) {
            attachContent(CommentsFragment.getInstance(service, url, args.getString("title", "")))
            return
        }
        val cached = InfoCache.getInstance().getFromKey(service, url, InfoCache.Type.STREAM)
        if (cached is StreamInfo) {
            attachInfo(cached)
        } else {
            request = ExtractorHelper.getStreamInfo(service, url, false)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(::attachInfo) {
                    val content = view.findViewById<FrameLayout>(R.id.detail_panel_content)
                    content.addView(TextView(requireContext()).apply {
                        setText(R.string.hub_loading_error)
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }, FrameLayout.LayoutParams(-1, -1))
                }
        }
    }

    private fun attachInfo(info: StreamInfo) {
        attachContent(if (requireArguments().getString("panel") == RELATED) {
            RelatedItemsFragment.getInstance(info)
        } else {
            DescriptionFragment(info)
        })
    }

    private fun attachContent(fragment: Fragment) {
        if (view == null || childFragmentManager.isStateSaved) return
        childFragmentManager.beginTransaction().replace(R.id.detail_panel_content, fragment).commit()
    }

    override fun onStart() {
        super.onStart()
        val sheetDialog = dialog as? BottomSheetDialog ?: return
        val sheet = sheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?: return
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * 0.65f).toInt()
        }
        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
        sheetDialog.window?.setDimAmount(0.18f)
        navigationManager = (activity as? FragmentActivity)?.supportFragmentManager
        navigationManager?.addOnBackStackChangedListener(navigationListener)
    }

    override fun onStop() {
        navigationManager?.removeOnBackStackChangedListener(navigationListener)
        navigationManager = null
        super.onStop()
    }

    override fun onDestroyView() {
        request?.dispose()
        request = null
        observedAdapter?.unregisterAdapterDataObserver(commentObserver)
        observedAdapter = null
        childFragmentManager.unregisterFragmentLifecycleCallbacks(childCallbacks)
        super.onDestroyView()
    }

    companion object {
        const val COMMENTS = "comments"
        const val DESCRIPTION = "description"
        const val RELATED = "related"
        const val TAG = "video_info_sheet"

        @JvmStatic
        fun newInstance(panel: String, service: Int, url: String, title: String) = VideoInfoSheet().apply {
            arguments = Bundle().apply {
                putString("panel", panel)
                putInt("service", service)
                putString("url", url)
                putString("title", title)
            }
        }
    }
}
