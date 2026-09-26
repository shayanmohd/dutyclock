package com.mohdshayan.dutyclock.ui.records

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.play.core.review.ReviewManagerFactory
import com.mohdshayan.dutyclock.core.backup.Backup
import com.mohdshayan.dutyclock.core.backup.BackupFile
import com.mohdshayan.dutyclock.core.backup.Csv
import com.mohdshayan.dutyclock.core.backup.NotABackup
import com.mohdshayan.dutyclock.core.engine.DayTotals
import com.mohdshayan.dutyclock.core.engine.Fmt
import com.mohdshayan.dutyclock.core.engine.RuleEngine
import com.mohdshayan.dutyclock.core.pdf.RecordPdf
import com.mohdshayan.dutyclock.data.prefs.AppPrefs
import com.mohdshayan.dutyclock.di.ServiceLocator
import com.mohdshayan.dutyclock.ui.components.EmptyState
import com.mohdshayan.dutyclock.ui.components.InkTextButton
import com.mohdshayan.dutyclock.ui.components.PrimaryButton
import com.mohdshayan.dutyclock.ui.components.RowDivider
import com.mohdshayan.dutyclock.ui.components.SecondaryButton
import com.mohdshayan.dutyclock.ui.components.SectionTitle
import com.mohdshayan.dutyclock.ui.components.SkeletonBlock
import com.mohdshayan.dutyclock.ui.nav.LocalSnackbar
import com.mohdshayan.dutyclock.ui.theme.LightSurface
import com.mohdshayan.dutyclock.ui.theme.RadiusMd
import java.io.File
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface RecordsState {
    data object Loading : RecordsState
    data object Error : RecordsState
    data object Empty : RecordsState
    data class Ready(val pdf: File, val preview: Bitmap?, val range: String, val completedDays: Int, val reviewPrompted: Boolean) : RecordsState
}

private const val MAX_IMPORT_BYTES = 16 * 1024 * 1024

private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

class RecordsViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.logRepository
    private val prefs = ServiceLocator.appPrefs
    val state = MutableStateFlow<RecordsState>(RecordsState.Loading)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pendingImport = MutableStateFlow<BackupFile?>(null)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            state.value = try {
                withContext(Dispatchers.Default) { build() }
            } catch (e: Exception) {
                RecordsState.Error
            }
        }
    }

    private suspend fun build(): RecordsState {
        val (input, settings) = repo.snapshot()
        if (input.entries.isEmpty()) return RecordsState.Empty
        val now = System.currentTimeMillis()
        val zone = input.zone
        val ev = RuleEngine.evaluate(input, now)
        val last = DayTotals.localDate(now, zone)
        val bytes = RecordPdf.build(
            RecordPdf.Input(settings.driverName, settings.vehicleReg, input.entries, last, 28, now, zone, ev.replay.findings),
        )
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "dutyclock-record-$last.pdf")
        file.writeBytes(bytes)
        val fmt = DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)
        return RecordsState.Ready(file, render(file), "${fmt.format(last.minusDays(27))} to ${fmt.format(last)}", ev.replay.days.count { it.drivingMs > 0 }, settings.reviewPrompted)
    }

    private fun render(file: File): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(0).use { page ->
                    val w = 1000
                    val h = (w * page.height.toFloat() / page.width).toInt()
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(LightSurface.toArgb())
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

    private fun write(uri: Uri, bytes: ByteArray, done: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }.isSuccess
            }
            if (ok) prefs.bump(AppPrefs.Count.EXPORTS)
            messages.emit(if (ok) done else "That file could not be written. Pick another place to save it.")
        }
    }

    fun savePdf(uri: Uri) {
        val s = state.value as? RecordsState.Ready ?: return
        write(uri, s.pdf.readBytes(), "PDF exported")
    }

    fun saveCsv(uri: Uri) {
        viewModelScope.launch {
            val (input, _) = repo.snapshot()
            write(uri, Csv.build(input.entries, System.currentTimeMillis(), input.zone).toByteArray(Charsets.UTF_8), "CSV exported")
        }
    }

    fun saveBackup(uri: Uri) {
        viewModelScope.launch { write(uri, repo.backup().toByteArray(Charsets.UTF_8), "Backup exported") }
    }

    fun readImport(uri: Uri) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    // A backup of years of taps is well under a megabyte; refuse anything huge before reading it all.
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { readAtMost(it, MAX_IMPORT_BYTES + 1) }
                    if (bytes.size > MAX_IMPORT_BYTES) throw NotABackup("too large")
                    Backup.decode(bytes.toString(Charsets.UTF_8))
                }
            }
            file.onSuccess { pendingImport.value = it }
                .onFailure { messages.emit(if (it is NotABackup) "That file is not a Dutyclock backup." else "That file could not be read. Pick the backup again.") }
        }
    }

    fun confirmImport() {
        val f = pendingImport.value ?: return
        pendingImport.value = null
        viewModelScope.launch {
            val n = runCatching { repo.restore(f) }.getOrNull()
            messages.emit(if (n != null) "Backup imported: $n entries" else "That backup could not be imported. Your log is unchanged.")
            refresh()
        }
    }

    fun markReviewPrompted() {
        viewModelScope.launch { prefs.set(AppPrefs.Keys.REVIEW_PROMPTED, true) }
    }
}

@Composable
fun RecordsScreen(onLog: () -> Unit, vm: RecordsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val today = java.time.LocalDate.now()

    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            vm.savePdf(uri)
            val s = vm.state.value as? RecordsState.Ready
            val activity = context as? Activity
            if (s != null && activity != null && s.completedDays >= 3 && !s.reviewPrompted) {
                vm.markReviewPrompted()
                val manager = ReviewManagerFactory.create(activity)
                manager.requestReviewFlow().addOnCompleteListener { t -> if (t.isSuccessful) manager.launchReviewFlow(activity, t.result) }
            }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::saveCsv) }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::saveBackup) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::readImport) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Column(Modifier.widthIn(max = 640.dp)) {
            when (val s = state) {
                RecordsState.Loading -> {
                    Spacer(Modifier.height(12.dp))
                    SkeletonBlock(300.dp, 424.dp)
                }
                RecordsState.Error -> EmptyState(
                    "The record could not be built",
                    "Your log could not be read. Restore a backup below.",
                    Modifier.fillMaxWidth(),
                )
                RecordsState.Empty -> EmptyState(
                    title = "Log a day to export a record",
                    body = "Once a day is logged, a 28-day PDF, a CSV and a backup are ready here. On a new phone, import your backup below.",
                    actionLabel = "Open Log",
                    onAction = onLog,
                    modifier = Modifier.fillMaxWidth(),
                )
                is RecordsState.Ready -> {
                    SectionTitle("28-day personal record")
                    Text(
                        "${s.range}. A strip and totals for each day, weekly and fortnightly sums, working time, edited entries marked. Headed as a personal record, not a tachograph record.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    s.preview?.let { bmp ->
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = "First page of the 28-day record",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(bmp.width.toFloat() / bmp.height)
                                .border(1.dp, cs.outlineVariant, RoundedCornerShape(RadiusMd)),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Save PDF", { pdfLauncher.launch(s.pdf.name) }, Modifier.weight(1f))
                        SecondaryButton("Share PDF", {
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", s.pdf)
                            val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "Share the record"))
                        }, Modifier.weight(1f))
                    }
                    SectionTitle("Spreadsheet")
                    Text("Every entry with its start, mode, minutes and rule set.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Export CSV", { csvLauncher.launch("dutyclock-log-$today.csv") })
                }
            }
            SectionTitle("Backup")
            Text(
                "One file with your whole log, leave days, catch-up and settings. Keep it somewhere safe, or restore it on a new phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Export backup", { backupLauncher.launch(Backup.fileName(System.currentTimeMillis(), java.time.ZoneId.systemDefault())) }, Modifier.weight(1f))
                SecondaryButton("Import backup", { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(24.dp))
            RowDivider()
            Text(
                "Files are written only where you choose. Nothing leaves your phone unless you share it.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }

    pending?.let { f ->
        AlertDialog(
            onDismissRequest = { vm.pendingImport.value = null },
            title = { Text("Replace your log with this backup?") },
            text = {
                Text(
                    "It holds ${f.entries.size} entries, exported ${Fmt.shortDay(f.exportedAt, java.time.ZoneId.systemDefault())}. Everything logged on this phone now is replaced.",
                )
            },
            confirmButton = { InkTextButton("Import backup", vm::confirmImport) },
            dismissButton = { InkTextButton("Cancel", { vm.pendingImport.value = null }) },
            containerColor = cs.surfaceVariant,
        )
    }
}
