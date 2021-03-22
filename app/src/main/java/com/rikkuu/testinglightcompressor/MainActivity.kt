package com.rikkuu.testinglightcompressor

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var btnPickImage: AppCompatButton
    private lateinit var btnCompress: AppCompatButton
    private lateinit var tvResponse: TextView

    private var uriCompress: Uri? = null
    private var compressedUri: Uri? = null

    private var progressDialog: AlertDialog? = null

    private val cv by lazy { ContentValues() }

    companion object {
        const val PICK_IMAGE_REQ_CODE = 1991
        const val STORAGE_PERMISSION_REQ_CODE = 12321
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initDialog(this)
        initComponent()
    }

    private fun initComponent() {
        btnPickImage = findViewById(R.id.btnPickImage)
        btnCompress = findViewById(R.id.btnCompress)
        tvResponse = findViewById(R.id.tvResponse)
        btnCompress.isEnabled = false
        btnPickImage.setOnClickListener(this)
        btnCompress.setOnClickListener(this)
    }

    private fun compressVideo(uri: Uri?, filename: String) {
        log("Compress Video is started")
        if (uri == null) {
            log("uri is null")
            return
        }
        val path = File(
            Environment.getExternalStorageDirectory(),
            Environment.DIRECTORY_MOVIES + "/TestingLightCompressor"
        )
        try {
            if (!path.exists()) {
                path.mkdir()
            }
        } catch (securityException: Exception) {
            securityException.printStackTrace()
            log("${securityException.message}")
        }

        val date = System.currentTimeMillis()
        cv.apply {
            put(MediaStore.Video.Media.TITLE, "$filename.mp4")
            put(MediaStore.Video.Media.DISPLAY_NAME, "$filename.mp4")
            put(MediaStore.Video.Media.DATA, path.path + "/$filename.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, date)
            put(MediaStore.Video.Media.DATE_MODIFIED, date)
        }
        if (isHigherSdk29()) {
            cv.put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        compressedUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)

        getRealPathByUri(this, uri)?.let { pathOrigin ->

            VideoCompressor.start(
                pathOrigin,
                "${path.path}/$filename.mp4",
                object : CompressionListener {
                    override fun onProgress(percent: Float) {
                        runOnUiThread {
                            updateDialog(percent)
                        }
                    }

                    override fun onStart() {
                        showDialog()
                        toast("Start Video Compress")
                    }

                    override fun onSuccess() {
                        onEncodeDone()
                        hideDialog()
                        toast("Video Compress Completed")
                    }

                    override fun onFailure(failureMessage: String) {
                        onEncodeDone()
                        hideDialog()
                        toast(failureMessage)
                    }

                    override fun onCancelled() {
                        onEncodeDone()
                        hideDialog()
                    }

                }, VideoQuality.LOW, isMinBitRateEnabled = true, keepOriginalResolution = true
            )
        }
    }

    private fun onEncodeDone(){
        if (isHigherSdk29()) {
            cv.apply {
                clear()
                put(MediaStore.Video.Media.SIZE, 0)
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            compressedUri?.let { dest ->
                contentResolver.update(dest, cv, null, null)
                val sizeFile = contentResolver.openFileDescriptor(dest, "r")?.use {
                    it.statSize
                } ?: throw IOException("Could not get file size")
                log("Size compressed video is $sizeFile")
            }
        } else {
            MediaScannerConnection.scanFile(
                this@MainActivity,
                arrayOf(Environment.getExternalStorageDirectory().path + "/" + Environment.DIRECTORY_MOVIES + "/TestingLightCompressor"),
                arrayOf("video/*")
            ) { path, uri ->
                Log.d("OnEncodeDone", "Path: $path and uri: $uri")
            }
        }
    }


    private fun updateDialog(value: Float) {
        val tv = progressDialog?.findViewById<TextView>(R.id.progressDialogText)
        tv?.let {
            tv.text = "Progress ${value.toInt()}%"
        }
    }

    private fun checkPermission(onGranted: () -> Unit) {
        val readStorage =
            ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        val writeStorage =
            ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (readStorage == PackageManager.PERMISSION_GRANTED && writeStorage == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_REQ_CODE
            )
        }
    }

    private fun showDialog() {
        if (progressDialog == null) {
            initDialog(this)
        }
        progressDialog?.show()
    }

    private fun hideDialog() {
        progressDialog?.dismiss()
        initDialog(this@MainActivity)
    }

    private fun initDialog(context: Context) {
        progressDialog = DialogUtil.createProgressDialog(context, "Encoding video")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            STORAGE_PERMISSION_REQ_CODE -> {
                checkPermission {
                    pickVideo()
                }
            }
            else -> {
                toast("Request Code not found onRequestPermissionResult")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            PICK_IMAGE_REQ_CODE -> {
                log("requestCode $requestCode, resultCode : $resultCode, data : ${data.toString()}")
                if (resultCode == RESULT_OK && data != null) {
                    btnCompress.isEnabled = true
                    uriCompress = data.data
                }
            }
            else -> {
                log("Request Code Not Found")
            }
        }
    }

    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.btnCompress -> {
                log("Btn compress clicked")
                val date = System.currentTimeMillis()
                compressVideo(uriCompress, "compress_$date")
            }
            R.id.btnPickImage -> {
                checkPermission {
                    pickVideo()
                }
            }
            else -> {

            }
        }
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).apply {
            type = "video/mp4"
        }
        startActivityForResult(intent, PICK_IMAGE_REQ_CODE)
    }

    private fun getRealPathByUri(context: Context, uri: Uri): String? {
        var result: String? = null
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.let { resultCursor ->
                if (resultCursor.moveToFirst()) {
                    val columnId = resultCursor.getColumnIndex(MediaStore.Video.Media.DATA)
                    result = cursor.getString(columnId)
                }
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            log("${exception.message}")
        } finally {
            cursor?.close()
        }
        log("Path : $result")
        return result
    }

    private fun isHigherSdk29(): Boolean {
        return isSdkHigherThan(Build.VERSION_CODES.Q)
    }

    private fun isSdkHigherThan(sdk: Int): Boolean {
        return Build.VERSION.SDK_INT >= sdk
    }

    private fun log(msg: String) {
        Log.d("IniLogDoang", "Msg: $msg")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

}