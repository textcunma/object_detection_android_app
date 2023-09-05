package com.example.objectdetection

import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.opencv.core.Rect

// 検出結果クラス
data class DetectionObject(
    val score: Float,
    val label: String,
    val boundingBox: Rect
)

class ObjectDetector(
    private val interpreter: Interpreter,
    private val labels: List<String>
) {

    companion object {
        // モデルのinputとoutputサイズ
        private const val IMG_SIZE_X = 300
        private const val IMG_SIZE_Y = 300
        private const val MAX_DETECTION_NUM = 10    // TFLite変換時に設定されているので変更不可

        // 利用するTF Liteモデルは量子化済み | normalize関連は127.5fではなく以下の通り
        private const val NORMALIZE_MEAN = 0f
        private const val NORMALIZE_STD = 1f

        // 検出結果のスコアしきい値
        private const val SCORE_THRESHOLD = 0.5f
    }

    // バウンディングボックス [1:10:4], 10は物体検出数, 4は4隅の座標
    private val outputBoundingBoxes: Array<Array<FloatArray>> = arrayOf(
        Array(MAX_DETECTION_NUM) {
            FloatArray(4)   // 4隅: [top, left, bottom, right]
        }
    )

    // クラスラベルインデックス [1:10], 10は物体検出数
    private val outputLabels: Array<FloatArray> = arrayOf(
        FloatArray(MAX_DETECTION_NUM)
    )

    // 各スコア [1:10], 10は物体検出数
    private val outputScores: Array<FloatArray> = arrayOf(
        FloatArray(MAX_DETECTION_NUM)
    )

    // 物体検出数 = 10
    private val outputDetectionNum: FloatArray = FloatArray(1)

    // 検出結果をmapでまとめる
    private val outputMap = mapOf(
        0 to outputBoundingBoxes,       // バウンディングボックス
        1 to outputLabels,              // ラベル
        2 to outputScores,              // スコア
        3 to outputDetectionNum         // 検出数
    )

    private val tfImageBuffer = TensorImage(DataType.UINT8)

    private val tfImageProcessor by lazy {
        ImageProcessor.Builder()
            .add(ResizeOp(IMG_SIZE_X, IMG_SIZE_Y, ResizeOp.ResizeMethod.BILINEAR)) // 画像サイズの変更
            .add(NormalizeOp(NORMALIZE_MEAN, NORMALIZE_STD)) //　正規化
            .build()
    }

    // メイン関数
    fun detect(targetBitmap: Bitmap): List<DetectionObject> {
        val w = targetBitmap.width
        val h = targetBitmap.height

        // Bitmap -> TensorFlowBuffer
        tfImageBuffer.load(targetBitmap)

        // 前処理(画像サイズの変更、正規化)
        val tensorImage = tfImageProcessor.process(tfImageBuffer)
        println(tensorImage)

        // 推論実行, outputMap内に格納
        interpreter.runForMultipleInputsOutputs(arrayOf(tensorImage.buffer), outputMap)

        // 推論結果(outputMap)内にある4つの情報を整形, リストにして返す
        val detectedObjectList = arrayListOf<DetectionObject>()
        loop@ for (i in 0 until outputDetectionNum[0].toInt()) {
            val score = outputScores[0][i]
            val label = labels[outputLabels[0][i].toInt()]
            val boundingBox = Rect(
                (outputBoundingBoxes[0][i][1] * w).toInt(),
                (outputBoundingBoxes[0][i][0] * h).toInt(),
                (outputBoundingBoxes[0][i][3] * w).toInt(),
                (outputBoundingBoxes[0][i][2] * h).toInt()
            )

            // 閾値より大きければリストに追加, 検出結果はソートされているため閾値以下なら処理終了
            if (score >= SCORE_THRESHOLD) {
                detectedObjectList.add(
                    DetectionObject(
                        score = score,
                        label = label,
                        boundingBox = boundingBox
                    )
                )
            } else {
                break@loop
            }
        }
        return detectedObjectList.take(4)       // 先頭4つ, つまり上位4つの要素を返す
    }
}