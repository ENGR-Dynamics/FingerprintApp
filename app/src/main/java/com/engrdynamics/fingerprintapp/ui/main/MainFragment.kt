package com.engrdynamics.fingerprintapp.ui.main

import android.Manifest
import android.animation.ObjectAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.util.Log
import android.util.Size
import android.view.*
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

import android.hardware.camera2.CameraManager
import android.media.Image
import android.text.InputType
import android.widget.Toast

import com.engrdynamics.fingerprintapp.R
import com.engrdynamics.fingerprintapp.models.CameraIdInfo
import com.engrdynamics.fingerprintapp.models.State
import com.engrdynamics.fingerprintapp.services.Camera
import com.engrdynamics.fingerprintapp.listeners.SurfaceTextureWaiter
import com.engrdynamics.fingerprintapp.services.ImageHandler

import kotlinx.android.synthetic.main.fragment_main.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.roundToInt

import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType.*

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2;

import org.opencv.imgproc.Imgproc;
import org.opencv.imgproc.Imgproc.INTER_AREA
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.graphics.Bitmap
import android.os.*
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.engrdynamics.fingerprintapp.views.CaptureFragment


class ImageHandlerImpl(private val fragment: MainFragment, private val camera : Camera?, private val rectangle : Rect) : ImageHandler {
    override fun handleImage(image: Image, id : Int) : Runnable
    {
        return Runnable(
            {

                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)

                val cropping_rectangle = Rect((rectangle.y * image.width) / 1000,
                    (rectangle.x * image.height) / 1000,
                    (rectangle.height * image.width) / 1000,
                    (rectangle.width * image.height) / 1000)

                val bitmap = BitmapFactory.decodeByteArray(bytes,0, bytes.size)

                val focal_length_inches = (1.0 / camera!!.lastFocus) / .3048 * 12
                val plane_width = focal_length_inches * 0.9 * 2
                val raw_dpi = image.width / plane_width
                val scale_factor = minOf(.64 * 500 / raw_dpi, 2.0)
                Log.d("DEBUG", scale_factor.toString())

                val mat: Mat = Mat(image.width, image.height, CV_8UC1)
                Utils.bitmapToMat(bitmap, mat)
                val mat_cropped = Mat(mat, cropping_rectangle)
                var mat_scaled: Mat = Mat(mat_cropped.rows(), mat_cropped.cols(), CV_8UC1)
                Imgproc.resize(mat_cropped, mat_scaled,
                    org.opencv.core.Size(mat_cropped.width() * scale_factor, mat_cropped.height() * scale_factor),
                    0.0, 0.0, INTER_AREA)
                var finalNormalMat: Mat = Mat(mat_scaled.rows(), mat_scaled.cols(), CV_8UC1)
                Imgproc.cvtColor(mat_scaled, finalNormalMat, Imgproc.COLOR_BGR2GRAY)
                var clahe = Imgproc.createCLAHE(5.0)
                var claheMat: Mat = Mat(finalNormalMat.rows(), finalNormalMat.cols(), CV_8UC1)
                clahe.apply(finalNormalMat, claheMat)
                var rotatedMat: Mat = Mat(claheMat.rows(), claheMat.cols(), CV_8UC1)
                Core.rotate(claheMat, rotatedMat, Core.ROTATE_90_COUNTERCLOCKWISE)
                var finalMat: Mat = Mat(rotatedMat.rows(), rotatedMat.cols(), CV_8UC1)
                Core.flip(rotatedMat, finalMat, 1);


                val rectifiedNormalBitmap: Bitmap = Bitmap.createBitmap(finalMat.cols(), finalMat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(finalMat, rectifiedNormalBitmap)

                image.close()

                if(id == 1) {
                    val handler = Handler(Looper.getMainLooper())
                    handler.post(Runnable {
                        fragment.handleCapturedImage(rectifiedNormalBitmap)
                    })
                }
            }
        )
    }
}

class MainFragment : Fragment() {

    companion object {
        private val TAG = MainFragment::class.java.toString()
        private const val FRAGMENT_TAG_DIALOG = "tag_dialog"
        private const val REQUEST_CAMERA_PERMISSION = 1000
        fun newInstance(filesDir : String) : MainFragment {
            val fragment = MainFragment()
            fragment.filesDir = filesDir
            return fragment
        }
    }

    private var filesDir = ""
    private var rect = Rect()
    private var camera: Camera? = null
    private lateinit var previewSize: Size
    private var capture_toast: Toast? = null

    private var mOpenCvCameraView: CameraBridgeViewBase? = null

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.options_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId)
        {
            R.id.export -> {
                var toast = Toast.makeText(activity, "Exporting...", Toast.LENGTH_LONG)
                toast.show()
                Thread {
                    try {
                        val out = ZipOutputStream(BufferedOutputStream(FileOutputStream(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath + "/Camera/ENGRDynamics_Fingerprints.zip")))
                        var fileList = File(filesDir).listFiles()
                        for (file in fileList) {
                            val entry = ZipEntry(file.name)
                            out.putNextEntry(entry);
                            file.inputStream().copyTo(out)
                        }
                        out.close()

                        val handler = Handler(Looper.getMainLooper())

                        handler.post(Runnable {
                            toast.cancel()
                            toast = Toast.makeText(activity, "Export complete", Toast.LENGTH_SHORT)
                            toast.show()
                        })
                    }
                    catch (e : Exception)
                    {
                        Log.e("DEBUG", e.toString())
                    }
                }.start()

                true
            }
            R.id.erase -> {

                try {
                val alertDialog: AlertDialog? = activity?.let {
                    val builder = AlertDialog.Builder(it)
                    builder.apply {
                        setPositiveButton("Erase",
                            DialogInterface.OnClickListener { dialog, id ->
                                var fileList = File(filesDir).listFiles()
                                for (file in fileList) {
                                    file.delete()
                                }
                            })
                        setNegativeButton("Cancel",
                            DialogInterface.OnClickListener { dialog, id ->
                            })
                    }
                    // Set other dialog properties
                    builder.setTitle("Erase cached fingerprints?")
                    builder.setMessage("All unexported images will be erased.")

                    // Create the AlertDialog
                    builder.create()
                }
                alertDialog?.show()
                }
                catch (e : Exception)
                {
                    Log.e("DEBUG", e.toString())
                }

                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!OpenCVLoader.initDebug()) {
            Log.d("DEBUG", "OpenCV failed to load!")
        } else {
            Log.d("DEBUG","OpenCV loaded successfully!")
        }

        pictureButton.setOnClickListener {
            capture_toast = Toast.makeText(activity, "Capturing...", Toast.LENGTH_SHORT)
            capture_toast?.show()

            rect = Rect((rectangleView.x * 1000.0f / camera0View.width).toInt(),
                (rectangleView.y * 1000.0f / camera0View.height).toInt(),
                (rectangleView.width * 1000.0f / camera0View.width).toInt(),
                (rectangleView.height * 1000.0f / camera0View.height).toInt())
            camera?.takePicture(ImageHandlerImpl(this, camera, rect))

            val animator = ObjectAnimator.ofFloat(flashView, View.ALPHA, 0.0f, 1.0f)
            animator.interpolator = LinearInterpolator()
            animator.duration = 200
            animator.repeatMode = ObjectAnimator.REVERSE
            animator.repeatCount = 1
            animator.start()

            pictureButton.isEnabled = false
        }

        torchButton.setOnClickListener {
            camera?.toggleTorch()
        }
    }

    fun handleCapturedImage(bitmap: Bitmap)
    {
        capture_toast?.cancel()
        val transaction = fragmentManager!!.beginTransaction()
        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
        transaction
            .add(android.R.id.content, CaptureFragment.newInstance(bitmap, filesDir))
            .addToBackStack(null)
            .commit()

        pictureButton.isEnabled = true
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        setHasOptionsMenu(true)

        //editTextId.setRawInputType(InputType.TYPE_CLASS_NUMBER);

        val manager = activity!!.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        camera = Camera.initInstance(manager)
    }

    override fun onResume() {
        super.onResume()

        if (camera0View.isAvailable && camera1View.isAvailable) {
            openCamera(camera0View.width, camera0View.height)
            openDualCamera(camera1View.width, camera1View.height)
            return
        }

        // wait for TextureView available
        val waiter0 = SurfaceTextureWaiter(camera0View)
        val waiter1 = SurfaceTextureWaiter(camera1View)
        GlobalScope.launch {
            val result0 = waiter0.textureIsReady()
            val result1 = waiter1.textureIsReady()

            if (result1.state != State.ON_TEXTURE_AVAILABLE)
                Log.e(TAG, "camera1View unexpected state = $result1.state")

            when (result0.state) {
                State.ON_TEXTURE_AVAILABLE -> {
                    withContext(Dispatchers.Main) {
                        openDualCamera(result0.width, result0.height)
                    }
                }
                State.ON_TEXTURE_SIZE_CHANGED -> {
                    withContext(Dispatchers.Main) {
                        val matrix = calculateTransform(result0.width, result0.height)
                        camera0View.setTransform(matrix)
                    }
                }
                else -> {
                    // do nothing.
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        camera?.close()
    }

    private fun requestCameraPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
            REQUEST_CAMERA_PERMISSION
            )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray) {

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorMessageDialog.newInstance(getString(R.string.request_permission))
                    .show(childFragmentManager, FRAGMENT_TAG_DIALOG)
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun openCamera(width: Int, height: Int) {
        activity ?: return

        val permission = ContextCompat.checkSelfPermission(activity!!, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }

        try {
            camera?.let {
                // Usually preview size has to be calculated based on the sensor rotation using getImageOrientation()
                // so that the sensor rotation and image rotation aspect matches correctly.
                // In this sample app, we know that Pixel series has the 90 degrees of sensor rotation,
                // so we just consider that width/ height < 1, which means portrait.
                val aspectRatio: Float = width / height.toFloat()
                previewSize = it.getPreviewSize(aspectRatio)

                camera0View.setAspectRatio(previewSize.height, previewSize.width)

                val matrix = calculateTransform(width, height)
                camera0View.setTransform(matrix)
                it.open()

                val texture1 = camera0View.surfaceTexture
                texture1.setDefaultBufferSize(previewSize.width, previewSize.height)
                it.start(listOf(Surface(texture1)))
            }
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openDualCamera(width: Int, height: Int) {
        activity ?: return

        val permission = ContextCompat.checkSelfPermission(activity!!, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }

        try {
            camera?.let {
                // Usually preview size has to be calculated based on the sensor rotation using getImageOrientation()
                // so that the sensor rotation and image rotation aspect matches correctly.
                // In this sample app, we know that Pixel series has the 90 degrees of sensor rotation,
                // so we just consider that width/ height < 1, which means portrait.
                val aspectRatio: Float = width / height.toFloat()
                previewSize = it.getPreviewSize(aspectRatio)

                camera0View.setAspectRatio(previewSize.height, previewSize.width)
                camera1View.setAspectRatio(previewSize.height, previewSize.width)

                val matrix = calculateTransform(width, height)
                camera0View.setTransform(matrix)
                camera1View.setTransform(matrix)
                it.open()

                val texture0 = camera0View.surfaceTexture
                val texture1 = camera1View.surfaceTexture
                texture0.setDefaultBufferSize(previewSize.width, previewSize.height)
                texture1.setDefaultBufferSize(previewSize.width, previewSize.height)
                it.start(listOf(Surface(texture0), Surface(texture1)))
            }
        }
        catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateTransform(viewWidth: Int, viewHeight: Int) : Matrix {
        val rotation = activity!!.windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            val scale = max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        return matrix
    }
}