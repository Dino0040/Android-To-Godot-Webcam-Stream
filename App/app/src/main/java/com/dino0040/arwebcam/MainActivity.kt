package com.dino0040.arwebcam

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var textureView: TextureView
    private lateinit var ipAddressInput: EditText
    private lateinit var resolutionSpinner: Spinner
    private lateinit var framerateInput: EditText
    private lateinit var bitrateInput: EditText
    private lateinit var startButton: Button
    private lateinit var statusTextView: TextView
    private var mediaCodec: MediaCodec? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var isStreaming = false

    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var backgroundHandler: Handler
    private lateinit var backgroundThread: HandlerThread

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            startCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
        }
    }

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                initializeCamera()
            } else {
                Toast.makeText(this, "Permissions not granted", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        ipAddressInput = findViewById(R.id.ipAddressInput)
        resolutionSpinner = findViewById(R.id.resolutionSpinner)
        framerateInput = findViewById(R.id.framerateInput)
        bitrateInput = findViewById(R.id.bitrateInput)
        startButton = findViewById(R.id.startButton)
        statusTextView = findViewById(R.id.statusTextView)

        sharedPreferences = getSharedPreferences("StreamPreferences", Context.MODE_PRIVATE)
        loadPreferences()

        requestPermissions.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            )
        )

        startButton.setOnClickListener {
            if (!isStreaming) {
                savePreferences()
                val ipAddress = ipAddressInput.text.toString()
                if (ipAddress.isEmpty()) {
                    Toast.makeText(this, "Please enter an IP address", Toast.LENGTH_SHORT).show()
                } else {
                    startStreaming(ipAddress)
                }
            } else {
                stopStreaming()
            }
        }
    }

    private fun loadPreferences() {
        ipAddressInput.setText(sharedPreferences.getString("ipAddress", "192.168.1.2"))
        framerateInput.setText(sharedPreferences.getString("framerate", "30"))
        bitrateInput.setText(sharedPreferences.getString("bitrate", "125000"))

        val resolution = sharedPreferences.getString("resolution", "1280x720")
        val resolutionIndex = (resolutionSpinner.adapter as ArrayAdapter<String>).getPosition(resolution)
        resolutionSpinner.setSelection(resolutionIndex)
    }

    private fun savePreferences() {
        val editor = sharedPreferences.edit()
        editor.putString("ipAddress", ipAddressInput.text.toString())
        editor.putString("resolution", resolutionSpinner.selectedItem.toString())
        editor.putString("framerate", framerateInput.text.toString())
        editor.putString("bitrate", bitrateInput.text.toString())
        editor.apply()
    }

    private fun initializeCamera() {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {}

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        }
    }

    private fun openCamera() {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraId = cameraManager.cameraIdList[0]
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun startCameraPreview() {
        try {
            val texture = textureView.surfaceTexture
            texture?.setDefaultBufferSize(640, 480)
            val previewSurface = Surface(texture)

            val captureRequestBuilder =
                cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                    addTarget(previewSurface)
                }

            // Use the background handler for the camera capture session
            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        captureRequestBuilder?.let {
                            it.set(
                                CaptureRequest.CONTROL_MODE,
                                CameraMetadata.CONTROL_MODE_AUTO
                            )
                            try {
                                captureSession?.setRepeatingRequest(
                                    it.build(),
                                    null,
                                    backgroundHandler
                                )
                            } catch (e: CameraAccessException) {
                                e.printStackTrace()
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(
                            this@MainActivity,
                            "Camera configuration failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                backgroundHandler // Using backgroundHandler here instead of Executor
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun configureMediaCodec(previewSurface: Surface) {
        try {
            mediaCodec = MediaCodec.createEncoderByType("video/avc")
            val bundle = Bundle()
            bundle.putInt(MediaCodec.PARAMETER_KEY_LOW_LATENCY, 1)
            mediaCodec?.setParameters(bundle)
            val resolution = resolutionSpinner.selectedItem.toString().split("x")
            val format = MediaFormat.createVideoFormat("video/avc", resolution[0].toInt(), resolution[1].toInt())
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateInput.text.toString().toInt())
            format.setInteger(MediaFormat.KEY_FRAME_RATE, framerateInput.text.toString().toInt())
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)

            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = mediaCodec?.createInputSurface()

            // Use the background handler for the camera capture session
            cameraDevice?.createCaptureSession(
                listOf(previewSurface, inputSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                session.device.createCaptureRequest(
                                    CameraDevice.TEMPLATE_PREVIEW
                                ).apply {
                                    addTarget(previewSurface)
                                    inputSurface?.let { addTarget(it) }
                                }.build(),
                                null,
                                backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(
                            this@MainActivity,
                            "Capture session configuration failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                backgroundHandler // Using backgroundHandler here instead of Executor
            )

            mediaCodec?.start()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startStreaming(ipAddress: String) {
        isStreaming = true
        startButton.text = "Stop Streaming"
        statusTextView.text = "Connecting..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = Socket(ipAddress, 10040)
                val outputStream: OutputStream = socket.getOutputStream()

                val bufferInfo = MediaCodec.BufferInfo()
                runOnUiThread { statusTextView.text = "Connected" }

                // Reconfigure MediaCodec before starting
                val texture = textureView.surfaceTexture
                if (texture != null) {
                    val previewSurface = Surface(texture)
                    configureMediaCodec(previewSurface)

                    val infoMessage = ByteBuffer.allocate(12)
                    infoMessage.putInt(mediaCodec!!.outputFormat.getInteger(MediaFormat.KEY_WIDTH))
                    infoMessage.putInt(mediaCodec!!.outputFormat.getInteger(MediaFormat.KEY_HEIGHT))
                    infoMessage.putInt(mediaCodec!!.outputFormat.getInteger(MediaFormat.KEY_FRAME_RATE))
                    outputStream.write(infoMessage.array())

                    while (isStreaming) {
                        val outputBufferIndex =
                            mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                        if (outputBufferIndex >= 0) {
                            val outputBuffer: ByteBuffer =
                                mediaCodec?.getOutputBuffer(outputBufferIndex)!!
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            outputBuffer.clear()

                            outputStream.write(data)
                            outputStream.flush()

                            mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)
                        }
                    }
                }
                socket.close()
                runOnUiThread { statusTextView.text = "Disconnected" }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    statusTextView.text = "Failed to stream: ${e.message}"
                    Toast.makeText(
                        this@MainActivity,
                        "Failed to stream video: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun stopStreaming() {
        isStreaming = false
        startButton.text = "Start Streaming"
        statusTextView.text = "Disconnected"

        // Properly release MediaCodec
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "Failed to stop streaming: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        captureSession?.stopRepeating()
        captureSession?.abortCaptures()
        captureSession?.close()
        captureSession = null
    }

    override fun onResume() {
        super.onResume()
        backgroundThread = HandlerThread("CameraBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    override fun onPause() {
        super.onPause()
        backgroundThread.quitSafely()
    }
}
