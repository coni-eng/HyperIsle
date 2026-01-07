package com.coni.hyperisle.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.coni.hyperisle.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsFileHelper {

    fun saveDiagnosticsToFile(
        context: Context,
        content: String,
        diagnosticType: String
    ): File? {
        if (!BuildConfig.DEBUG) return null
        
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
            val filename = "hyperisle_${diagnosticType}_diagnostics_$timestamp.txt"
            
            val cacheDir = context.cacheDir
            val file = File(cacheDir, filename)
            
            file.writeText(content)
            
            return file
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun shareFile(context: Context, file: File, subject: String) {
        if (!BuildConfig.DEBUG) return
        
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(shareIntent, null))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
