package org.schabi.newpipe.download

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentDownloadGlassBinding
import org.schabi.newpipe.views.glass.GlassFilterAdapter
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.get.FinishedMission
import us.shandian.giga.get.Mission
import us.shandian.giga.service.DownloadManager
import us.shandian.giga.service.DownloadManagerService
import us.shandian.giga.service.DownloadManagerService.DownloadManagerBinder

class GlassDownloadFragment : Fragment() {

    private var _binding: FragmentDownloadGlassBinding? = null
    private val binding get() = _binding!!

    private var binder: DownloadManagerBinder? = null
    private var downloadManager: DownloadManager? = null
    private var activeFilter = "All"

    private val allMissions = mutableListOf<Mission>()
    private val displayedMissions = mutableListOf<Mission>()
    private lateinit var adapter: GlassDownloadAdapter

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service as? DownloadManagerBinder
            binder?.clearDownloadNotifications()
            downloadManager = binder?.downloadManager
            refreshMissions()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            downloadManager = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadGlassBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind DownloadManagerService
        val serviceIntent = Intent(requireContext(), DownloadManagerService::class.java)
        requireContext().bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setupFilters()
        setupRecyclerView()
        updateStorageCapsule()
    }

    private fun setupFilters() {
        val filterOptions = listOf("All", "Video", "Audio", "Downloading", "Completed")
        val filterAdapter = GlassFilterAdapter(filterOptions) { filter, _ ->
            activeFilter = filter
            applyFilter()
        }
        binding.downloadFilterStrip.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.downloadFilterStrip.adapter = filterAdapter
    }

    private fun setupRecyclerView() {
        adapter = GlassDownloadAdapter(
            items = displayedMissions,
            onPauseResumeClick = { mission ->
                if (mission is DownloadMission) {
                    if (mission.running) {
                        mission.pause()
                    } else {
                        mission.start()
                    }
                    refreshMissions()
                }
            },
            onCancelClick = { mission ->
                if (mission is DownloadMission) {
                    mission.pause()
                    mission.delete()
                } else if (mission is FinishedMission) {
                    mission.delete()
                }
                refreshMissions()
            },
            onItemClick = { mission ->
                // Open file if finished
                if (mission is FinishedMission && mission.storage != null) {
                    try {
                        val uri = mission.storage.uri
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, if (mission.kind == 'a') "audio/*" else "video/*")
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        )
        binding.downloadRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadRecycler.adapter = adapter
    }

    private fun refreshMissions() {
        allMissions.clear()
        val dm = downloadManager
        if (dm != null) {
            val iter = dm.iterator
            val total = iter.oldListSize.coerceAtLeast(0)
            for (i in 0 until total) {
                val item = iter.getItem(i)
                if (item != null && item.mission != null) {
                    allMissions.add(item.mission)
                }
            }
        }
        applyFilter()
        updateStorageCapsule()
    }

    private fun applyFilter() {
        displayedMissions.clear()
        val filtered = when (activeFilter) {
            "Video" -> allMissions.filter { it.kind == 'v' || it.kind == 's' }
            "Audio" -> allMissions.filter { it.kind == 'a' }
            "Downloading" -> allMissions.filter { it is DownloadMission && it.running }
            "Completed" -> allMissions.filterIsInstance<FinishedMission>()
            else -> allMissions
        }
        displayedMissions.addAll(filtered)
        adapter.notifyDataSetChanged()

        val isEmpty = displayedMissions.isEmpty()
        binding.downloadEmptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.downloadRecycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun updateStorageCapsule() {
        val context = context ?: return
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBytes = stat.blockCountLong * blockSize
            val freeBytes = stat.availableBlocksLong * blockSize
            val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)

            var downloadsBytes = 0L
            for (m in allMissions) {
                downloadsBytes += m.length
            }

            val freeFormatted = Formatter.formatFileSize(context, freeBytes)
            val downloadsFormatted = Formatter.formatFileSize(context, downloadsBytes)

            binding.storageFreeText.text = "$freeFormatted free"
            binding.storageDownloadsLabel.text = "Downloads ($downloadsFormatted)"

            val downloadsWeight = ((downloadsBytes.toFloat() / totalBytes) * 100f).coerceIn(1f, 90f)
            val freeWeight = ((freeBytes.toFloat() / totalBytes) * 100f).coerceIn(1f, 90f)
            val otherWeight = (100f - downloadsWeight - freeWeight).coerceAtLeast(1f)

            (binding.storageBarDownloads.layoutParams as? LinearLayout.LayoutParams)?.weight = downloadsWeight
            (binding.storageBarOther.layoutParams as? LinearLayout.LayoutParams)?.weight = otherWeight
            (binding.storageBarFree.layoutParams as? LinearLayout.LayoutParams)?.weight = freeWeight
            binding.storageBarDownloads.requestLayout()
        } catch (e: Exception) {
            // Ignore storage stat errors on restricted devices
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            requireContext().unbindService(serviceConnection)
        } catch (e: Exception) {
            // ignore
        }
        _binding = null
    }

    class GlassDownloadAdapter(
        private val items: List<Mission>,
        private val onPauseResumeClick: (Mission) -> Unit,
        private val onCancelClick: (Mission) -> Unit,
        private val onItemClick: (Mission) -> Unit
    ) : RecyclerView.Adapter<GlassDownloadAdapter.MissionViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MissionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download_glass_card, parent, false)
            return MissionViewHolder(view)
        }

        override fun onBindViewHolder(holder: MissionViewHolder, position: Int) {
            holder.bind(items[position], onPauseResumeClick, onCancelClick, onItemClick)
        }

        override fun getItemCount(): Int = items.size

        class MissionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.download_item_title)
            private val status: TextView = itemView.findViewById(R.id.download_item_status)
            private val size: TextView = itemView.findViewById(R.id.download_item_size)
            private val progress: ProgressBar = itemView.findViewById(R.id.download_item_progress)
            private val btnPauseResume: ImageButton = itemView.findViewById(R.id.download_btn_pause_resume)
            private val btnCancel: ImageButton = itemView.findViewById(R.id.download_btn_cancel)
            private val icon: ImageView = itemView.findViewById(R.id.download_item_icon)

            fun bind(
                mission: Mission,
                onPauseResume: (Mission) -> Unit,
                onCancel: (Mission) -> Unit,
                onClick: (Mission) -> Unit
            ) {
                val ctx = itemView.context
                val name = mission.storage?.name ?: "Download"
                title.text = name

                if (mission.kind == 'a') {
                    icon.setImageResource(R.drawable.ic_headset)
                } else {
                    icon.setImageResource(R.drawable.ic_play_arrow)
                }

                if (mission is DownloadMission) {
                    val done = mission.done
                    val length = mission.length
                    val pct = if (length > 0) ((done * 100) / length).toInt() else 0
                    progress.isIndeterminate = length <= 0
                    progress.progress = pct
                    progress.visibility = View.VISIBLE

                    val doneStr = Formatter.formatFileSize(ctx, done)
                    val lenStr = if (length > 0) Formatter.formatFileSize(ctx, length) else "..."
                    size.text = "$doneStr / $lenStr"

                    status.text = if (mission.running) "$pct% • Downloading" else "$pct% • Paused"
                    btnPauseResume.visibility = View.VISIBLE
                    btnPauseResume.setImageResource(if (mission.running) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                } else if (mission is FinishedMission) {
                    progress.visibility = View.GONE
                    val lenStr = Formatter.formatFileSize(ctx, mission.length)
                    size.text = lenStr
                    status.text = "Completed ✓"
                    btnPauseResume.visibility = View.GONE
                }

                btnPauseResume.setOnClickListener { onPauseResume(mission) }
                btnCancel.setOnClickListener { onCancel(mission) }
                itemView.setOnClickListener { onClick(mission) }
            }
        }
    }
}
