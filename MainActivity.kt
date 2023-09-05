package com.example.objectdetection

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.example.objectdetection.ui.theme.ObjectDetectionTheme
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.channels.FileChannel


const val DISPLAY_SIZE_DP = 300 // ディスプレイサイズ(dp)

class MainActivity : ComponentActivity() {
    private var canvasPixel = 0f    // リサイズ用
    private var resizedW = 0
    private var resizedH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // OpenCV初期化
        if (!OpenCVLoader.initDebug()) {
            println("error")
        }
        super.onCreate(savedInstanceState)

        val context = applicationContext
        val metrics = context.resources.displayMetrics
        canvasPixel = DISPLAY_SIZE_DP * metrics.density     // dp -> pixel

        setContent {
            ObjectDetectionTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background
                ) {
                    SelectedButton(context)
                }
            }
        }
    }

    // Assetsフォルダ内にあるモデル、ラベルの名称
    companion object {
        private const val MODEL_FILE_NAME = "ssd_mobilenet_v1.tflite"
        private const val LABEL_FILE_NAME = "coco_dataset_labels.txt"
    }

    // TF Liteモデルを扱うためのラッパーを含んだinterpreter
    private val interpreter: Interpreter by lazy {
        Interpreter(loadModel())
    }

    // モデルの正解ラベルリスト
    private val labels: List<String> by lazy {
        loadLabels()
    }

    // AssetsフォルダからTF Liteモデルを読み込む
    private fun loadModel(fileName: String = MODEL_FILE_NAME): ByteBuffer {
        lateinit var modelBuffer: ByteBuffer
        var file: AssetFileDescriptor? = null
        try {
            file = assets.openFd(fileName)
            val inputStream = FileInputStream(file.fileDescriptor)
            val fileChannel = inputStream.channel
            modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, file.startOffset, file.declaredLength)
        } catch (e: Exception) {
            Toast.makeText(this, "モデルファイル読み込みエラー", Toast.LENGTH_SHORT).show()
            finish()
        } finally {
            file?.close()
        }
        return modelBuffer
    }

    // Assetsフォルダからラベルデータを取得
    private fun loadLabels(fileName: String = LABEL_FILE_NAME): List<String> {
        var labels = listOf<String>()
        var inputStream: InputStream? = null
        try {
            inputStream = assets.open(fileName)
            val reader = BufferedReader(InputStreamReader(inputStream))
            labels = reader.readLines()
        } catch (e: Exception) {
            Toast.makeText(this, "txtファイル読み込みエラー", Toast.LENGTH_SHORT).show()
            finish()
        } finally {
            inputStream?.close()
        }
        return labels
    }

    // Composableでない関数の場合, contextを渡す必要
    private fun uri2bitmap (uri: Uri, context: Context): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source){ decoder, _, _ ->
            decoder.isMutableRequired = true
        }
    }

    // キャンバスサイズの幅に合わせてリサイズ
    private fun resizedBitmap(bitmap: Bitmap): Bitmap{
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val ratio = canvasPixel / w
        resizedW = (w * ratio).toInt()
        resizedH = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, resizedW, resizedH, true)
    }

    // キャンバスの中心に描画できるように位置を計算
    private fun calcCanvasCenter(canvasH: Float): Offset{
        val top = (canvasH - resizedH) / 2
        return Offset(x = 0f, y = top)
    }

    private fun detect(bitmap: Bitmap) : List<DetectionObject>{
        val od = ObjectDetector(interpreter, labels)
        return od.detect(bitmap)
    }

    private fun draw(detectList: List<DetectionObject>, bitmap: Bitmap): Bitmap{
        // Bitmap -> Mat
        val drawMat = Mat()
        Utils.bitmapToMat(bitmap, drawMat)

        // 色空間の変更(OpenCVの仕様です)
        Imgproc.cvtColor(drawMat, drawMat, Imgproc.COLOR_BGR2RGB)

        for (i in detectList.indices) {
            val element = detectList[i]
            val bboxCoord = element.boundingBox
            val label = element.label
            val score = element.score

            // 矩形の描画
            Imgproc.rectangle(drawMat, bboxCoord, Scalar(100.0, 255.0, 0.0), 4)

            // ラベルの描画
            val labelCoord = Point(bboxCoord.x.toDouble(), bboxCoord.y.toDouble())
            Imgproc.putText(drawMat, label, labelCoord, 3, 1.5, Scalar(255.0,0.0,0.0),2)

            // スコアの描画
            val pointCoord = Point(bboxCoord.x.toDouble(), bboxCoord.y.toDouble() - 40.0)
            Imgproc.putText(drawMat, score.toString(), pointCoord, 3, 0.8, Scalar(255.0,255.0,255.0),2)
        }

        // 色空間を元に戻す
        Imgproc.cvtColor(drawMat, drawMat, Imgproc.COLOR_RGB2BGR)

        // Mat -> Bitmap
        Utils.matToBitmap(drawMat, bitmap)
        return bitmap
    }

    @Composable
    private fun SelectedButton(context: Context) {
        // 選択された画像のURIを保持
        var imageUri by remember { mutableStateOf<Uri?>(null) }

        // ギャラリーから画像を選択するためのアクティビティ結果コントラクトを宣言
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            imageUri = uri
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ){
            Canvas(modifier = Modifier.size(DISPLAY_SIZE_DP.dp), onDraw = {
                if (imageUri == null) {drawRect(color = Color.Gray)}
                imageUri?.let { uri ->
                    // uriをBitmapに変換
                    var bitmap = uri2bitmap(uri, context)

                    // 推論を実行
                    val detectedObjectList = detect(bitmap)

                    // 描画
                    bitmap = draw(detectedObjectList, bitmap)

                    // リサイズ
                    bitmap = resizedBitmap(bitmap)

                    // 描画
                    val imageBitmap = bitmap.asImageBitmap()
                    drawImage(imageBitmap, topLeft = calcCanvasCenter(size.height))
                }
            })
            Button(
                onClick = {
                    // 画像ギャラリーの表示 (選択画像URIはimageUriに格納)
                    launcher.launch("image/*")
                },
                modifier = Modifier.padding(16.dp)
            ){
                Text(text = "SELECT IMAGE")
            }
        }
    }
}