package com.oliver.smartwatchdata.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.android.gms.wearable.Wearable
import com.oliver.smartwatchdata.R
import com.oliver.smartwatchdata.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

private const val TAG = "CAPTURE"
private const val CHANNEL_ID = "capture_channel"
private const val NOTIF_ID = 1001
private const val DEBUG_LOGS = false // <- habilita/inhabilita el Log.d detallado

class CaptureService : Service(), SensorEventListener {

    // ====== Sensores ======
    private lateinit var sm: SensorManager
    private var sAcc: Sensor? = null
    private var sGyro: Sensor? = null
    private var sGrav: Sensor? = null
    private var sLin: Sensor? = null

    // caches
    private val acc = FloatArray(3) { Float.NaN }
    private val gyro = FloatArray(3) { Float.NaN }
    private val grav = FloatArray(3) { Float.NaN }
    private val lin  = FloatArray(3) { Float.NaN }

    private var rowsThisSecond = 0
    private var lastSecondNs = 0L
    private var started = false   // evita doble registro de listeners

    // ====== CSV / ventanas ======
    private var writer: BufferedWriter? = null
    private var windowStartNs = 0L
    private lateinit var capturesDir: File
    private lateinit var exportsDir: File
    private val dateFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private var linesSinceFlush = 0

    // Clase A/B/C/D (por defecto A)
    private var classLabel: String = "A"
    private var subjectId: Int = 1

    // ====== Coroutines para I/O y Data Layer ======
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val ACTION_EXPORT_AND_SEND = "com.oliver.smartwatchdata.EXPORT_AND_SEND"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Servicio creado")
        createChannelIfNeeded()
        startForeground(NOTIF_ID, buildNotification("Listo para capturar"))

        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sAcc  = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sGyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sGrav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        sLin  = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        Log.i(TAG, "Sensores -> acc=${sAcc!=null}, gyro=${sGyro!=null}, grav=${sGrav!=null}, lin=${sLin!=null}")

        capturesDir = File(filesDir, "captures").apply { mkdirs() }
        exportsDir  = File(filesDir, "exports").apply { mkdirs() }
        Log.i(TAG, "Capturas en: ${capturesDir.absolutePath}")
        Log.i(TAG, "Exportaciones en: ${exportsDir.absolutePath}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Acciones especiales (exportar y enviar ZIP)
        if (intent?.action == ACTION_EXPORT_AND_SEND) {
            serviceScope.launch {
                try {
                    val zip = createCapturesZip()
                    if (zip == null) {
                        Log.w(TAG, "No hay CSVs para comprimir")
                        updateNotification("No hay capturas para exportar")
                    } else {
                        updateNotification("Enviando ZIP al teléfono…")
                        sendZipToPhone(zip)
                        updateNotification("ZIP enviado ✅")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Error exportando/enviando", t)
                    updateNotification("Error al enviar ZIP")
                }
            }
            return START_NOT_STICKY
        }

        // Leer la clase enviada por la Activity (A/B/C/D)
        classLabel = intent?.getStringExtra("class_label")?.takeIf { it in listOf("A","B","C","D") } ?: "A"
        subjectId = intent?.getIntExtra("subject_id", 1) ?: 1

        if (!started) {
            // ~25 Hz (40_000 μs)
            sm.registerListener(this, sAcc,  40_000)
            sm.registerListener(this, sGyro, 40_000)
            sm.registerListener(this, sGrav, 40_000)
            sm.registerListener(this, sLin,  40_000)
            lastSecondNs = System.nanoTime()
            rowsThisSecond = 0
            started = true
            Log.i(TAG, "Captura iniciada (FGS). Clase=$classLabel")
            updateNotification("Capturando datos (~25 Hz) — Clase $classLabel")
        } else {
            Log.i(TAG, "Captura ya estaba iniciada (Clase actual=$classLabel)")
            updateNotification("Capturando datos (~25 Hz) — Clase $classLabel")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sm.unregisterListener(this)
        started = false
        closeCsv()
        serviceScope.cancel()
        Log.i(TAG, "Servicio destruido")
        super.onDestroy()
    }

    // ===== SensorEventListener =====
    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                acc[0]=e.values[0]; acc[1]=e.values[1]; acc[2]=e.values[2]
                val ax=acc[0]; val ay=acc[1]; val az=acc[2]
                val gx=gyro[0]; val gy=gyro[1]; val gz=gyro[2]
                val gravx=grav[0]; val gravy=grav[1]; val gravz=grav[2]
                val lax=lin[0];  val lay=lin[1];  val laz=lin[2]

                val accMag = if (ax.isFinite()&&ay.isFinite()&&az.isFinite()) sqrt(ax*ax+ay*ay+az*az) else Float.NaN
                val gyroMag = if (gx.isFinite()&&gy.isFinite()&&gz.isFinite()) sqrt(gx*gx+gy*gy+gz*gz) else Float.NaN

                // Rotación / escritura CSV (ventanas de 60 s)
                val nowWallMs = System.currentTimeMillis()
                if (windowStartNs == 0L || (e.timestamp - windowStartNs) >= 60_000_000_000L) {
                    openNewCsv(nowWallMs)  // usa classLabel en el nombre
                    windowStartNs = e.timestamp
                }

                writer?.let { w ->
                    // Agregamos la etiqueta al final
                    w.write("${e.timestamp},$ax,$ay,$az,$gx,$gy,$gz,$gravx,$gravy,$gravz,$lax,$lay,$laz,$accMag,$gyroMag,$classLabel\n")
                    if (++linesSinceFlush >= 250) {
                        try { w.flush() } catch (_: Throwable) {}
                        linesSinceFlush = 0
                    }
                }

                // Logs de depuración (controlados por bandera)
                if (DEBUG_LOGS) {
                    Log.d(
                        TAG,
                        "ts_ns=${e.timestamp},ax=$ax,ay=$ay,az=$az,gx=$gx,gy=$gy,gz=$gz," +
                                "gravx=$gravx,gravy=$gravy,gravz=$gravz,lax=$lax,lay=$lay,laz=$laz," +
                                "acc_mag=$accMag,gyro_mag=$gyroMag,label=$classLabel"
                    )
                }

                // Hz reales a la notificación cada 1 s
                rowsThisSecond++
                if (e.timestamp - lastSecondNs >= 1_000_000_000) {
                    val hz = rowsThisSecond
                    Log.i(TAG, "Paquetes servicio ~ $hz/s (Clase $classLabel)")
                    updateNotification("Clase $classLabel · ~${hz}/s")
                    rowsThisSecond = 0
                    lastSecondNs = e.timestamp
                }
            }
            Sensor.TYPE_GYROSCOPE -> { gyro[0]=e.values[0]; gyro[1]=e.values[1]; gyro[2]=e.values[2] }
            Sensor.TYPE_GRAVITY ->   { grav[0]=e.values[0]; grav[1]=e.values[1]; grav[2]=e.values[2] }
            Sensor.TYPE_LINEAR_ACCELERATION -> { lin[0]=e.values[0]; lin[1]=e.values[1]; lin[2]=e.values[2] }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ===== Notificación =====
    private fun createChannelIfNeeded() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Captura de Sensores", NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            (PendingIntent.FLAG_UPDATE_CURRENT
                    or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Smartwatchdata")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    // ===== CSV =====
    private fun openNewCsv(nowWallMs: Long) {
        closeCsv()

        val subjectStr = "S${subjectId.toString().padStart(2, '0')}"
        val name = "capture_${dateFmt.format(java.util.Date(nowWallMs))}_${classLabel}_${subjectStr}.csv"

        val file = File(capturesDir, name)
        writer = BufferedWriter(FileWriter(file, /*append=*/false)).also {
            it.write("ts_ns,ax,ay,az,gx,gy,gz,gravx,gravy,gravz,lax,lay,laz,acc_mag,gyro_mag,label\n")
            it.flush()
        }
        linesSinceFlush = 0
        Log.i(TAG, "CSV abierto: ${file.absolutePath}")
    }

    private fun closeCsv() {
        try { writer?.flush() } catch (_: Throwable) {}
        try { writer?.close() } catch (_: Throwable) {}
        writer = null
    }

    // ===== Exportar ZIP y enviar por Canal =====

    /** Comprime todos los .csv en files/captures a un ZIP en files/exports.
     *  Retorna el File del ZIP o null si no había CSVs. */
    private fun createCapturesZip(): File? {
        val csvs = capturesDir.listFiles { f -> f.isFile && f.extension.equals("csv", ignoreCase = true) }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

        if (csvs.isEmpty()) return null

        val stamp = dateFmt.format(java.util.Date(System.currentTimeMillis()))
        val outZip = File(exportsDir, "captures_${stamp}.zip")

        ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            csvs.forEach { csv ->
                FileInputStream(csv).use { fis ->
                    val entry = ZipEntry(csv.name)
                    zos.putNextEntry(entry)
                    fis.copyTo(zos, 8 * 1024)
                    zos.closeEntry()
                }
            }
        }
        Log.i(TAG, "ZIP creado: ${outZip.absolutePath}  (${outZip.length()} bytes)")
        return outZip
    }

    /** Obtiene el primer nodo conectado que no sea el propio reloj (normalmente el teléfono). */
    private suspend fun getPhoneNodeIdOrNull(): String? = withContext(Dispatchers.IO) {
        try {
            val nodes = Wearable.getNodeClient(this@CaptureService).connectedNodes.awaitTask()
            val nearby = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            nearby?.id
        } catch (t: Throwable) {
            Log.e(TAG, "No pude obtener nodos conectados", t)
            null
        }
    }

    /** Envía el ZIP al teléfono usando ChannelClient.sendFile(Uri) con FileProvider. */
    private suspend fun sendZipToPhone(zipFile: File) = withContext(Dispatchers.IO) {
        val nodeId = getPhoneNodeIdOrNull()
            ?: throw IllegalStateException("No hay teléfono conectado (NodeClient vacío)")

        val path = "/captures_export" // ruta lógica del canal
        val channelClient = Wearable.getChannelClient(this@CaptureService)

        // Usa la autoridad basada en packageName para que coincida con el Manifest:
        val uri = FileProvider.getUriForFile(
            this@CaptureService,
            "${applicationContext.packageName}.fileprovider",
            zipFile
        )

        // Concede permiso de lectura a Google Play Services (gestiona el canal)
        try {
            grantUriPermission("com.google.android.gms", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) {}

        val channel = channelClient.openChannel(nodeId, path).awaitTask()
        try {
            channelClient.sendFile(channel, uri).awaitTask()
            Log.i(TAG, "ZIP enviado por canal $path a nodo $nodeId")
        } finally {
            try { channelClient.close(channel) } catch (_: Throwable) {}
        }
    }

    // ===== Helpers: Task<T> → suspend =====
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitTask(): T =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { res -> if (!cont.isCompleted) cont.resume(res) }
            addOnFailureListener { e -> if (!cont.isCompleted) cont.resumeWithException(e) }
            addOnCanceledListener { if (!cont.isCompleted) cont.cancel() }
        }
}


