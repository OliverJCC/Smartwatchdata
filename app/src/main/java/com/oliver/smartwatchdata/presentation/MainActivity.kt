package com.oliver.smartwatchdata.presentation

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.Wearable
import com.oliver.smartwatchdata.presentation.theme.SmartwatchdataTheme
import com.oliver.smartwatchdata.service.CaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        setContent {
            SmartwatchdataTheme {
                var selectedClass by remember { mutableStateOf("A") }
                var subjectId by remember { mutableIntStateOf(1) }
                var isRecording by remember { mutableStateOf(false) }
                var elapsed by remember { mutableIntStateOf(0) }

                LaunchedEffect(isRecording) {
                    if (isRecording) {
                        elapsed = 0
                        while (isRecording) {
                            delay(1000)
                            elapsed += 1
                        }
                    }
                }

                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // ===== Selector de clase =====
                    item {
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("A", "B", "C", "D").forEach { cls ->
                                Button(
                                    onClick = { selectedClass = cls },
                                    enabled = !isRecording,
                                    modifier = Modifier.size(36.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = if (selectedClass == cls) Color(0xFF2196F3) else Color(0xFFE0E0E0),
                                        contentColor = if (selectedClass == cls) Color.White else Color.Black
                                    )
                                ) {
                                    Text(cls)
                                }
                            }
                        }
                    }

                    // ===== Selector de sujeto =====
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Sujeto: S${subjectId.toString().padStart(2, '0')}",
                            color = MaterialTheme.colors.primary
                        )
                    }

                    item {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    if (subjectId > 1) subjectId -= 1
                                },
                                enabled = !isRecording,
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF757575),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("-")
                            }

                            Button(
                                onClick = {
                                    subjectId += 1
                                },
                                enabled = !isRecording,
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFF757575),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("+")
                            }
                        }
                    }

                    // ===== Botón iniciar =====
                    item {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                if (!isRecording) {
                                    val i = Intent(this@MainActivity, CaptureService::class.java)
                                    i.putExtra("class_label", selectedClass)
                                    i.putExtra("subject_id", subjectId)
                                    ContextCompat.startForegroundService(this@MainActivity, i)
                                    isRecording = true
                                }
                            },
                            modifier = Modifier.size(64.dp),
                            enabled = !isRecording,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFF2E7D32),
                                contentColor = Color.White
                            )
                        ) {
                            Text("I")
                        }
                    }

                    // ===== Botón detener =====
                    item {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                stopService(Intent(this@MainActivity, CaptureService::class.java))
                                isRecording = false
                            },
                            modifier = Modifier.size(64.dp),
                            enabled = isRecording,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFFC62828),
                                contentColor = Color.White
                            )
                        ) {
                            Text("D")
                        }
                    }

                    // ===== Botón exportar =====
                    item {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { exportCapturesZip() },
                            modifier = Modifier.size(64.dp),
                            enabled = !isRecording,
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = Color(0xFF455A64),
                                contentColor = Color.White
                            )
                        ) {
                            Text("E")
                        }
                    }

                    // ===== Estado =====
                    item {
                        Spacer(Modifier.height(6.dp))
                        val mm = (elapsed / 60).toString().padStart(2, '0')
                        val ss = (elapsed % 60).toString().padStart(2, '0')
                        val status = if (isRecording) "Grabando · $mm:$ss" else "Listo"

                        Text(
                            "Clase: $selectedClass · S${subjectId.toString().padStart(2, '0')} · $status",
                            color = MaterialTheme.colors.primary
                        )
                    }
                }
            }
        }
    }

    private fun exportCapturesZip() {
        try {
            val capturesDir = File(filesDir, "captures")
            val csvs = capturesDir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
                ?.sortedBy { it.name } ?: emptyList()

            if (csvs.isEmpty()) {
                android.widget.Toast.makeText(this, "No hay CSVs para exportar", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val zipName = "captures_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())}.zip"
            val zipFile = File(cacheDir, zipName)

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                val buffer = ByteArray(8 * 1024)
                csvs.forEach { csv ->
                    BufferedInputStream(FileInputStream(csv)).use { ins ->
                        zos.putNextEntry(ZipEntry(csv.name))
                        var read: Int
                        while (ins.read(buffer).also { read = it } != -1) {
                            zos.write(buffer, 0, read)
                        }
                        zos.closeEntry()
                    }
                }
            }

            sendZipToPhone(zipFile)

        } catch (t: Throwable) {
            android.util.Log.e("EXPORT", "Error exportando ZIP", t)
            android.widget.Toast.makeText(this, "Error al exportar: ${t.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendZipToPhone(zipFile: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodeClient = Wearable.getNodeClient(this@MainActivity)
                val nodes = nodeClient.connectedNodes.await()
                val phoneNode = nodes.firstOrNull()

                if (phoneNode == null) {
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "No hay teléfono conectado",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@launch
                }

                val channelClient = Wearable.getChannelClient(this@MainActivity)
                val channel = channelClient.openChannel(phoneNode.id, "/zip_transfer").await()

                val uri = Uri.fromFile(zipFile)
                channelClient.sendFile(channel, uri).await()

                channelClient.close(channel)

                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "ZIP enviado al teléfono",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (t: Throwable) {
                android.util.Log.e("ZIP_TX", "Error enviando ZIP (ChannelClient.sendFile)", t)
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Error enviando: ${t.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}