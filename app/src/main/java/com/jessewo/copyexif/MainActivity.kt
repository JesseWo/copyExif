package com.jessewo.copyexif

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatActivity
import com.blankj.utilcode.util.FileIOUtils
import com.blankj.utilcode.util.ImageUtils
import com.jessewo.libcopyexif.ImageHeaderParser
import com.jessewo.libcopyexif.LOG
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btn_select_img.setOnClickListener {
            //调用系统默认图片选择器
            selectImageFromGallery()
        }
    }

    private fun selectImageFromGallery() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && data != null) {
            val uri = data.data ?: return

            LOG.i(TAG, "ParseUri $uri")
            // 先解析 uri 对应的文件元信息
            val contentResolver = this.contentResolver
            val contentType = contentResolver.getType(uri)
            LOG.i(TAG, "contentType: $contentType")


            // The query, since it only applies to a single document, will only return
            // one row. There's no need to filter, sort, or select fields, since we want
            // all fields for one document.
            contentResolver.query(uri, null, null, null, null, null)?.use {
                // moveToFirst() returns false if the cursor has 0 rows.  Very handy for
                // "if there's anything to look at, look at it" conditionals.
                if (it.moveToFirst()) {

                    // Note it's called "Display Name".  This is
                    // provider-specific, and might not necessarily be the file name.
                    val displayName: String = it.getString(it.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                    val size = it.getString(it.getColumnIndex(OpenableColumns.SIZE))
//                    val picturePath: String = it.getString(it.getColumnIndex(MediaStore.Images.Media.DATA))
                    LOG.i(TAG, "origin >> ${size.toLong() / 1024}kb")
                    it.close()

                    val compressImagePath = compressImage(this, uri, displayName)
                    val file = File(compressImagePath)
                    val bitmap = ImageUtils.getBitmap(file, iv_image.width, iv_image.height)
                    iv_image.setImageBitmap(bitmap)

                    LOG.i(TAG, "compress >> ${file.absolutePath} ${file.length() / 1024}kb")
                    tv_compressed_img_path.text = """
                        uri: $uri, 
                        contentType: $contentType
                        displayName: $displayName, 
                        imgSrcSize: ${size.toLong() / 1024}kb,                         
                        imgDstPath: ${file.absolutePath}
                        imgDstSize: ${file.length() / 1024}kb
                    """.trimIndent()
                }
            }
        }
    }

    private fun compressImage(context: Context, uri: Uri, fileName: String): String {
        val contentResolver = context.contentResolver

        val bmOptions = BitmapFactory.Options()
        bmOptions.inJustDecodeBounds = true
        BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, bmOptions)
        val photoW = bmOptions.outWidth.toFloat()
        val photoH = bmOptions.outHeight.toFloat()
        LOG.d("raw pixel:$photoW*$photoH")

        val targetH = 1920
        val targetW = 1920
        var scaleFactor = 1f
        if (photoW * photoH > targetH * targetW) {
            scaleFactor = min(targetW / photoW, targetH / photoH)
        }
        //原图
        val originBm = ImageUtils.getBitmap(contentResolver.openInputStream(uri))
        //压缩方式1: 尺寸压缩
        val scaledBm = ImageUtils.compressByScale(originBm, scaleFactor, scaleFactor, true)
        //压缩方式2: 质量压缩
        val dstData = ImageUtils.compressByQuality(scaledBm, 85, true)
        //复制EXIF
        val srcData = FileUtil.inputStream2Bytes(contentResolver.openInputStream(uri))
        val dataWithExif = ImageHeaderParser.cloneExif(srcData, dstData)

        val file = File(context.externalCacheDir, fileName)
        FileIOUtils.writeFileFromBytesByStream(file, dataWithExif)

        return file.absolutePath
    }

}