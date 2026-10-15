package org.schabi.newpipe.settings.export

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.history.model.StreamHistoryEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.databinding.FragmentGlassDataImportBinding
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.subscription.workers.ImportExportJsonHelper
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ZipHelper
import org.schabi.newpipe.views.glass.LiquidGlassHelper

class GlassDataImportFragment : Fragment() {

    private var _binding: FragmentGlassDataImportBinding? = null
    private val binding get() = _binding!!

    private lateinit var importExportManager: ImportExportManager
    private val exportDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private val selectTakeoutLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleTakeoutSelected(it) }
    }

    private val selectNewPipeBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleNewPipeBackupSelected(it) }
    }

    private val createExportArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { handleFullExportSelected(it) }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        importExportManager = ImportExportManager(BackupFileLocator(context.applicationContext))
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGlassDataImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        LiquidGlassHelper.applyLiquidGlass(binding.syncProgressCard, 18f)

        binding.btnImportTakeout.setOnClickListener {
            selectTakeoutLauncher.launch(arrayOf("application/json", "text/html", "*/*"))
        }

        binding.btnImportNewpipeJson.setOnClickListener {
            selectNewPipeBackupLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
        }

        binding.btnCreateFullExport.setOnClickListener {
            val defaultName = "NewPipeData-${exportDateFormat.format(Date())}.zip"
            createExportArchiveLauncher.launch(defaultName)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Google Takeout Import (JSON + HTML)
    //////////////////////////////////////////////////////////////////////////*/

    private fun handleTakeoutSelected(uri: Uri) {
        showProgress("Importing Google Takeout history...")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext().applicationContext
            val db = NewPipeDatabase.getInstance(context)

            var imported = 0
            var skipped = 0

            try {
                val cr = context.contentResolver
                val stream = cr.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open stream for URI")

                val buffered = BufferedInputStream(stream)
                buffered.mark(1024)
                val headerBytes = ByteArray(512)
                val readLen = buffered.read(headerBytes)
                buffered.reset()

                val headerStr = if (readLen > 0) String(headerBytes, 0, readLen) else ""
                val isHtml = headerStr.trimStart().startsWith("<") || headerStr.contains("<html", ignoreCase = true)

                if (isHtml) {
                    val doc = Jsoup.parse(buffered, "UTF-8", "")
                    val cells = doc.select("div.content-cell")
                    val total = cells.size

                    cells.forEachIndexed { index, cell ->
                        try {
                            val links = cell.select("a")
                            if (links.isEmpty()) {
                                skipped++
                                return@forEachIndexed
                            }

                            val videoLink = links.first() ?: return@forEachIndexed
                            val videoUrl = videoLink.attr("href")
                            if (!videoUrl.contains("watch?v=")) {
                                skipped++
                                return@forEachIndexed
                            }

                            var videoTitle = videoLink.text().trim()
                            if (videoTitle.startsWith("Watched ")) {
                                videoTitle = videoTitle.substring(8)
                            }
                            if (videoTitle.isBlank()) videoTitle = "YouTube Video"

                            var uploader = "YouTube"
                            var uploaderUrl: String? = null
                            if (links.size > 1) {
                                val channelLink = links[1]
                                uploader = channelLink.text().trim()
                                uploaderUrl = channelLink.attr("href")
                            }

                            val cellHtml = cell.html()
                            val parts = cellHtml.split("<br>")
                            val dateStr = if (parts.size >= 3) {
                                Jsoup.parse(parts.last()).text().trim()
                            } else {
                                ""
                            }
                            val accessDate = parseHtmlDate(dateStr)

                            val streamEntity = StreamEntity(
                                serviceId = ServiceList.YouTube.serviceId,
                                url = videoUrl,
                                title = videoTitle,
                                streamType = StreamType.VIDEO_STREAM,
                                duration = 0L,
                                uploader = uploader,
                                uploaderUrl = uploaderUrl
                            )
                            val streamId = db.streamDAO().upsert(streamEntity)

                            try {
                                db.streamHistoryDAO().insert(
                                    StreamHistoryEntity(
                                        streamUid = streamId,
                                        accessDate = accessDate,
                                        repeatCount = 1L
                                    )
                                )
                            } catch (_: Exception) {
                                // Ignore duplicate primary key collisions
                            }
                            imported++
                        } catch (_: Exception) {
                            skipped++
                        }

                        if (index % 50 == 0 || index == total - 1) {
                            val currentImported = imported
                            val progress = ((index.toFloat() / total) * 100).toInt()
                            withContext(Dispatchers.Main) {
                                updateProgress("Imported $currentImported of $total videos ($skipped skipped)", progress)
                            }
                        }
                    }
                } else {
                    // JSON Format
                    val reader = BufferedReader(InputStreamReader(buffered, Charsets.UTF_8))
                    val jsonArray: JsonArray = JsonParser.array().from(reader)
                    val total = jsonArray.size

                    for (i in 0 until total) {
                        try {
                            val obj: JsonObject = jsonArray.getObject(i) ?: continue
                            val titleUrl = obj.getString("titleUrl")
                            if (titleUrl.isNullOrEmpty() || !titleUrl.contains("watch?v=")) {
                                skipped++
                                continue
                            }

                            var title = obj.getString("title") ?: "YouTube Video"
                            if (title.startsWith("Watched ")) {
                                title = title.substring(8)
                            }
                            if (title.isBlank()) title = "YouTube Video"

                            var uploader = "YouTube"
                            var uploaderUrl: String? = null
                            val subtitles = obj.getArray("subtitles")
                            if (subtitles != null && subtitles.size > 0) {
                                val sub = subtitles.getObject(0)
                                if (sub != null) {
                                    uploader = sub.getString("name") ?: "YouTube"
                                    uploaderUrl = sub.getString("url")
                                }
                            }

                            val timeStr = obj.getString("time")
                            val accessDate = try {
                                if (!timeStr.isNullOrEmpty()) {
                                    OffsetDateTime.parse(timeStr)
                                } else {
                                    OffsetDateTime.now()
                                }
                            } catch (_: Exception) {
                                OffsetDateTime.now()
                            }

                            val streamEntity = StreamEntity(
                                serviceId = ServiceList.YouTube.serviceId,
                                url = titleUrl,
                                title = title,
                                streamType = StreamType.VIDEO_STREAM,
                                duration = 0L,
                                uploader = uploader,
                                uploaderUrl = uploaderUrl
                            )
                            val streamId = db.streamDAO().upsert(streamEntity)

                            try {
                                db.streamHistoryDAO().insert(
                                    StreamHistoryEntity(
                                        streamUid = streamId,
                                        accessDate = accessDate,
                                        repeatCount = 1L
                                    )
                                )
                            } catch (_: Exception) {
                                // Ignore duplicate primary key collisions
                            }
                            imported++
                        } catch (_: Exception) {
                            skipped++
                        }

                        if (i % 50 == 0 || i == total - 1) {
                            val currentImported = imported
                            val progress = ((i.toFloat() / total) * 100).toInt()
                            withContext(Dispatchers.Main) {
                                updateProgress("Imported $currentImported of $total videos ($skipped skipped)", progress)
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    hideProgress()
                    val summary = "Successfully imported $imported watch history entries ($skipped skipped)."
                    binding.syncResultLog.text = summary
                    Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    val errorMsg = "Takeout import failed: ${e.localizedMessage}"
                    binding.syncResultLog.text = errorMsg
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun parseHtmlDate(text: String): OffsetDateTime {
        if (text.isBlank()) return OffsetDateTime.now()
        val patterns = arrayOf(
            "MMM d, yyyy, h:mm:ss a z",
            "MMM d, yyyy, h:mm:ss a",
            "d MMM yyyy, HH:mm:ss z",
            "yyyy-MM-dd'T'HH:mm:ss",
            "d MMMM yyyy HH:mm:ss z"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                val date = sdf.parse(text)
                if (date != null) {
                    return date.toInstant().atOffset(ZoneOffset.UTC)
                }
            } catch (_: Exception) { }
        }
        return OffsetDateTime.now()
    }

    /*//////////////////////////////////////////////////////////////////////////
    // NewPipe Backup Restore (.zip or .json)
    //////////////////////////////////////////////////////////////////////////*/

    private fun handleNewPipeBackupSelected(uri: Uri) {
        showProgress("Restoring NewPipe backup...")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext().applicationContext
            val db = NewPipeDatabase.getInstance(context)

            try {
                val cr = context.contentResolver
                val stream = cr.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open input stream")

                val buffered = BufferedInputStream(stream)
                buffered.mark(16)
                val magic = ByteArray(4)
                val read = buffered.read(magic)
                buffered.reset()

                val isZip = read >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()

                if (isZip) {
                    val storedFile = StoredFileHelper(context, uri, "application/zip")
                    if (!ZipHelper.isValidZipFile(storedFile)) {
                        throw IllegalArgumentException("The selected file is not a valid zip archive.")
                    }

                    importExportManager.ensureDbDirectoryExists()
                    val extracted = importExportManager.extractDb(storedFile)
                    if (!extracted) {
                        throw IllegalStateException("Failed to extract database files from zip archive.")
                    }

                    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                    if (importExportManager.exportHasJsonPrefs(storedFile)) {
                        importExportManager.loadJsonPrefs(storedFile, prefs)
                    } else if (importExportManager.exportHasSerializedPrefs(storedFile)) {
                        importExportManager.loadSerializedPrefs(storedFile, prefs)
                    }

                    NewPipeDatabase.checkpoint()

                    withContext(Dispatchers.Main) {
                        hideProgress()
                        val msg = "Full NewPipe backup (.zip) successfully restored."
                        binding.syncResultLog.text = msg
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                } else {
                    // Subscription JSON list
                    val items = ImportExportJsonHelper.readFrom(buffered)
                    val entities = items.map { item ->
                        SubscriptionEntity(
                            serviceId = item.serviceId,
                            url = item.url,
                            name = item.name
                        )
                    }
                    db.subscriptionDAO().upsertAll(entities)

                    withContext(Dispatchers.Main) {
                        hideProgress()
                        val msg = "Successfully restored ${entities.size} subscriptions from JSON."
                        binding.syncResultLog.text = msg
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    val err = "Backup restore failed: ${e.localizedMessage}"
                    binding.syncResultLog.text = err
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Full System Export
    //////////////////////////////////////////////////////////////////////////*/

    private fun handleFullExportSelected(uri: Uri) {
        showProgress("Exporting full backup archive...")
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext().applicationContext
            try {
                NewPipeDatabase.checkpoint()
                val preferences = PreferenceManager.getDefaultSharedPreferences(context)
                val storedFile = StoredFileHelper(context, uri, "application/zip")
                importExportManager.exportDatabase(preferences, storedFile)

                withContext(Dispatchers.Main) {
                    hideProgress()
                    val msg = "Full backup archive successfully created and exported."
                    binding.syncResultLog.text = msg
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideProgress()
                    val err = "Export failed: ${e.localizedMessage}"
                    binding.syncResultLog.text = err
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // UI Helpers
    //////////////////////////////////////////////////////////////////////////*/

    private fun showProgress(title: String) {
        binding.syncProgressCard.visibility = View.VISIBLE
        binding.syncProgressTitle.text = title
        binding.syncProgressBar.isIndeterminate = true
        binding.syncProgressStatus.text = "Initializing operation..."
    }

    private fun updateProgress(status: String, progress: Int) {
        binding.syncProgressBar.isIndeterminate = false
        binding.syncProgressBar.progress = progress
        binding.syncProgressStatus.text = status
    }

    private fun hideProgress() {
        binding.syncProgressCard.visibility = View.GONE
    }
}
