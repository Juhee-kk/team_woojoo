package com.example.woojoo_1

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * 앱 안 PDF 뷰어. 차량에는 PDF 뷰어 앱이 없어서 PdfRenderer로 직접 그린다.
 * extra "path": 앱 캐시의 PDF 경로
 */
class PdfViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_viewer)
        findViewById<MaterialButton>(R.id.buttonBack).setOnClickListener { finish() }

        val file = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }
        if (file == null || !file.exists()) {
            Toast.makeText(this, R.string.pdf_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.textPdfName).text = file.name
        val pages = findViewById<LinearLayout>(R.id.pdfPages)

        // 화면 너비에 맞춰 렌더링 (최대 1600px로 메모리 제한)
        pages.post {
            val width = (pages.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).coerceAtMost(1600)
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        for (i in 0 until renderer.pageCount) {
                            renderer.openPage(i).use { page ->
                                val height = width * page.height / page.width
                                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                pages.addView(ImageView(this).apply {
                                    setImageBitmap(bmp)
                                    adjustViewBounds = true
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ).apply { bottomMargin = (16 * resources.displayMetrics.density).toInt() }
                                    contentDescription = "증명서 ${i + 1}쪽"
                                })
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("증명서", "PDF 표시 실패", e)
                Toast.makeText(this, R.string.pdf_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
