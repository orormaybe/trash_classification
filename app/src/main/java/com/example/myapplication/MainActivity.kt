package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.android.synthetic.main.activity_main.*
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


class MainActivity : AppCompatActivity() {
    val takePhoto = 1
    val fromAlbum = 2
    lateinit var imageUri:  Uri
    lateinit var outputImage: File
    lateinit var myphoto:Bitmap
    lateinit var tflite:Interpreter
    val ImageMean=0
    lateinit var tv_show: TextView

    private val labelProbArray =
        Array(1) { FloatArray(13) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        load_model()
        takePhotoBtn.setOnClickListener{
            outputImage = File(externalCacheDir, "output_image.jpg")
            if (outputImage.exists()) {
                outputImage.delete()
            }
            outputImage.createNewFile()
            imageUri = if (Build.VERSION.SDK_INT>= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(this,"com.example.myapplication.fileprovider",outputImage)
            } else {
                Uri.fromFile(outputImage)
            }
            val intent = Intent("android.media.action.IMAGE_CAPTURE")
            intent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
            startActivityForResult(intent,takePhoto)

        }
        fromAlbumBtn.setOnClickListener {
            // 打开文件选择器
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            // 指定只显示照片
            intent.type = "image/*"
            startActivityForResult(intent, fromAlbum)
        }
        }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when(requestCode){
            takePhoto->{
                if(resultCode==Activity.RESULT_OK){
                    val bitmap=BitmapFactory.decodeStream(contentResolver.openInputStream(imageUri))
                    myphoto=bitmap
                    predict_image()
                    imageView.layoutParams.width=550
                    imageView.layoutParams.height=550
                    imageView.setImageBitmap(rotateIfRequired(bitmap))
                }
            }
            fromAlbum->{
                if(resultCode==Activity.RESULT_OK && data!=null){
                    data.data?.let { uri ->
                        // 将选择的照片显示
                        val bitmap = getBitmapFromUri(uri)
                        myphoto= bitmap!!
                        predict_image()
                        imageView.layoutParams.width=550
                        imageView.layoutParams.height=550
                        imageView.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }
    private fun getBitmapFromUri(uri: Uri) = contentResolver.openFileDescriptor(uri, "r")?.use {
        BitmapFactory.decodeFileDescriptor(it.fileDescriptor)
    }
    private fun rotateIfRequired(bitmap: Bitmap): Bitmap {
        val exif=ExifInterface(outputImage.path)
        val orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
        return when(orientation){
            ExifInterface.ORIENTATION_ROTATE_90->rotateBitmap(bitmap,90)
            ExifInterface.ORIENTATION_ROTATE_180->rotateBitmap(bitmap,180)
            ExifInterface.ORIENTATION_ROTATE_270->rotateBitmap(bitmap,270)
            else->bitmap
        }
    }
    private fun rotateBitmap(bitmap: Bitmap, degree: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degree.toFloat())
        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()
        return rotatedBitmap
    }


    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, 192, 192, true)
    }
    private fun addImageValue(imgData: ByteBuffer, value: Int, k: Int) {
        if (k == 2) {
            imgData.putFloat((((value and 0xff0000 )shr 16)-ImageMean )/ 192.toFloat())
        } else if (k == 1) {
            imgData.putFloat((((value and 0xff00) shr 8)-ImageMean) / 192.toFloat())
        } else {
            imgData.putFloat((((value and 0xff) )-ImageMean)/ 192.toFloat())
        }
    }
    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer? {
        val intValues = IntArray(192 * 192)
        scaleBitmap(bitmap)!!.getPixels(intValues, 0, 192, 0, 0, 192, 192)
        val imgData = ByteBuffer.allocateDirect(4 * 3 * 192 * 192 * 1)
        imgData.order(ByteOrder.nativeOrder())
        imgData.rewind()
        for (k in 2 downTo 0) {
            var pixel = 0
            for (i in 0..191) {
                for (j in 0..191) {
                    val `val` = intValues[pixel++]
                    addImageValue(imgData, `val`, k)
                }
            }
        }
        return imgData
    }


    @Throws(IOException::class)
    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor =
            applicationContext.assets.openFd("trash_cnn.tflite")
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel: FileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    private fun load_model() {
        try {
            tflite = Interpreter(loadModelFile())
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    private fun getmax(): Int {
        var relute = 0
        var max: Float = labelProbArray.get(0).get(0)
        for (i in 0..12) {
            if (max < labelProbArray.get(0).get(i)) {
                relute = i
                max = labelProbArray.get(0).get(i)
            }
        }
        return relute
    }
    private fun predict_image() {
        val bmp = scaleBitmap(myphoto)
        val inputData = convertBitmapToByteBuffer(bmp!!)
        tflite.run(inputData, labelProbArray)
        changeLabel(getmax())
    }
    private fun changeLabel(label: Int) {
        text.setTextSize(1, 45.0F)
        if (label == 0) {
            text.text = "有害垃圾"
            Toast.makeText(this,"废电池属于有害垃圾,一颗普通电池弃入大自然后，可以污染60万升水，相当于一个人一生的用水量。",Toast.LENGTH_LONG).show()
        } else if (label == 1) {
            text.text = "干垃圾"
            Toast.makeText(this,"硬纸板是干垃圾，可用来旧物利用手工制作成漂亮家具用品。",Toast.LENGTH_LONG).show()
        } else if (label == 2) {
            text.text = "干垃圾"
            Toast.makeText(this,"筷子是干垃圾，可作为制作其他木质材料或填充材料。",Toast.LENGTH_LONG).show()
        } else if (label == 3) {
            text.text = "干垃圾"
            Toast.makeText(this,"塑料叉子是干垃圾",Toast.LENGTH_LONG).show()
        } else if (label == 4) {
            text.text = "可回收垃圾"
            Toast.makeText(this,"玻璃是可回收垃圾",Toast.LENGTH_LONG).show()
        } else if (label == 5) {
            text.text = "口罩"
            Toast.makeText(this,"Null",Toast.LENGTH_LONG).show()
        } else if (label == 6) {
            text.text = "金属"
            Toast.makeText(this,"Null",Toast.LENGTH_LONG).show()
        } else if (label == 7) {
            text.text = "可回收垃圾"
            Toast.makeText(this,"废纸是可回收垃圾，根据纤维成分的不同，按纸种进行对应循环利用才能最大程度发挥废纸资源价值。",Toast.LENGTH_LONG).show()
        } else if (label == 8) {
            text.text = "可回收垃圾"
            Toast.makeText(this,"纸箱一般为可回收垃圾，纸盒广泛用做食品、医药、电子等各种产品的销售包装。",Toast.LENGTH_LONG).show()
        } else if (label == 9) {
            text.text = "湿垃圾"
            Toast.makeText(this,"果皮是湿垃圾",Toast.LENGTH_LONG).show()
        } else if (label == 10) {
            text.text = "干垃圾"
            Toast.makeText(this,"塑料袋是干垃圾，其用以塑料为主要原料制成的袋子",Toast.LENGTH_LONG).show()
        } else if (label == 11) {
            text.text = "可回收垃圾"
            Toast.makeText(this,"塑料瓶子是可回收垃圾",Toast.LENGTH_LONG).show()
        } else {
            text.text = "干垃圾"
            Toast.makeText(this,"塑料勺子是干垃圾",Toast.LENGTH_LONG).show()
        }
    }


}


