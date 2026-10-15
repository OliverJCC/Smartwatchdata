package com.oliver.smartwatchdata.wear

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.oliver.smartwatchdata.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

class ZipReceiverService : WearableListenerService() {

    companion object {
        private const val TAG = "ZIP_RX"
        private const val ZIP_PATH = "/zip_transfer"

        private const val CH_ID = "zip_rx_channel"
        private const val CH_NAME = "Recepción de ZIP"
        private const val NOTIF_ID = 2001
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CH_ID, CH_NAME, NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != ZIP_PATH) {
            Log.d(TAG, "Canal ignorado path=${channel.path}")
            return
        }

        Thread {
            val cc = Wearable.getChannelClient(this)
            try {
                Log.i(TAG, "Canal abierto: node=${channel.nodeId} path=${channel.path}")
                val input = Tasks.await(cc.getInputStream(channel))

                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
                val fileName = "phone_received_${ts}.zip"

                // 1) Copia interna (respaldo asegurado)
                val internalDir = File(getExternalFilesDir(null), "SmartwatchData").apply { mkdirs() }
                val internalFile = File(internalDir, fileName)
                input.use { ins ->
                    FileOutputStream(internalFile).use { fos ->
                        val buf = ByteArray(8 * 1024)
                        var n: Int
                        while (ins.read(buf).also { n = it } != -1) fos.write(buf, 0, n)
                        fos.flush()
                    }
                }
                Log.i(TAG, "Copia interna: ${internalFile.absolutePath}")

                // 2) Intento copiar a Descargas/SmartwatchData (visible)
                var publicOk = false
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                            put(MediaStore.Downloads.RELATIVE_PATH, "Download/SmartwatchData")
                        }
                        val uri = contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                        )
                        if (uri != null) {
                            contentResolver.openOutputStream(uri)?.use { out ->
                                internalFile.inputStream().use { inp ->
                                    val buf = ByteArray(8 * 1024)
                                    var n: Int
                                    while (inp.read(buf).also { n = it } != -1) out.write(buf, 0, n)
                                    out.flush()
                                }
                            }
                            publicOk = true
                            Log.i(TAG, "Guardado en Descargas/SmartwatchData: $fileName (uri=$uri)")
                        } else {
                            Log.e(TAG, "resolver.insert() devolvió null")
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Fallo guardando en Descargas", t)
                    }
                } else {
                    // API 24–28: carpeta pública tradicional
                    try {
                        val downloads = Environment
                            .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val outDir = File(downloads, "SmartwatchData").apply { mkdirs() }
                        val outFile = File(outDir, fileName)
                        internalFile.inputStream().use { inp ->
                            FileOutputStream(outFile).use { fos ->
                                val buf = ByteArray(8 * 1024)
                                var n: Int
                                while (inp.read(buf).also { n = it } != -1) fos.write(buf, 0, n)
                                fos.flush()
                            }
                        }
                        publicOk = true
                        Log.i(TAG, "Guardado en ${outDir.absolutePath}/$fileName")
                    } catch (t: Throwable) {
                        Log.e(TAG, "Fallo guardando en carpeta pública", t)
                    }
                }

                // 3) Notificación con acción
                showDoneNotification(fileName, publicOk, internalFile)

            } catch (t: Throwable) {
                Log.e(TAG, "Error recibiendo ZIP", t)
                showError("Error al recibir ZIP: ${t.message ?: "desconocido"}")
            } finally {
                try { cc.close(channel) } catch (_: Throwable) {}
            }
        }.start()
    }

    private fun showDoneNotification(fileName: String, publicOk: Boolean, internalFile: File) {
        val nm = getSystemService(NotificationManager::class.java)

        val builder = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Smartwatchdata")
            .setAutoCancel(true)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (publicOk)
                        "ZIP guardado en Descargas/SmartwatchData: $fileName"
                    else
                        "ZIP guardado sólo en la carpeta interna de la app.\nPulsa 'Abrir ZIP' para compartirlo."
                )
            )

        if (publicOk) {
            // Abrir Descargas del sistema
            val openDownloads = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            val pi = PendingIntent.getActivity(
                this, 0, openDownloads,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            builder.addAction(0, "Abrir Descargas", pi)
        } else {
            // Abrir ZIP interno vía FileProvider
            val uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider_mobile", internalFile
            )
            val openZip = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/zip")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val pi = PendingIntent.getActivity(
                this, 1, openZip,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            builder.addAction(0, "Abrir ZIP", pi)
        }

        nm.notify(NOTIF_ID, builder.build())
    }

    private fun showError(msg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notif: Notification = NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Smartwatchdata: error")
            .setContentText(msg)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .build()
        nm.notify(NOTIF_ID, notif)
    }
}
