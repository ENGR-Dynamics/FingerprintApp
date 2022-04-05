package com.engrdynamics.fingerprintapp.views

import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.engrdynamics.fingerprintapp.R
import kotlinx.android.synthetic.main.fragment_capture.*
import kotlinx.android.synthetic.main.fragment_main.*
import java.io.File
import java.io.FileOutputStream

class CaptureFragment : Fragment() {
    private var bitmap : Bitmap? = null
    private var filesDir : String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_capture, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        imageView.scaleType = ImageView.ScaleType.FIT_XY
        imageView.setImageBitmap(bitmap)
        editTextId.setText(last_subject_id)

        cancel_button.setOnClickListener {
            fragmentManager?.popBackStack()
        }

        save_button.setOnClickListener {
            val subject_id = editTextId.getText().toString()
            last_subject_id = subject_id
            val finger = fingerSpinner.getSelectedItem().toString().substring(0, 1)
            val set = editTextSet.text.toString()
            val filename = "ENGRDynamics_Subject-" + subject_id + "_Set-" + set + "_Finger-" + finger + ".png"

            val file = File(filesDir + "/" + filename)
            if(file.exists())
            {
                try {
                    val alertDialog: AlertDialog? = activity?.let {
                        val builder = AlertDialog.Builder(it)
                        builder.apply {
                            setPositiveButton("Overwrite",
                                DialogInterface.OnClickListener { dialog, id ->
                                    bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, FileOutputStream(filesDir + "/" + filename))
                                    fragmentManager?.popBackStack()
                                })
                            setNegativeButton("Cancel",
                                DialogInterface.OnClickListener { dialog, id ->
                                })
                        }
                        // Set other dialog properties
                        builder.setTitle("Overwrite existing capture?")
                        builder.setMessage(filename + " already exists.")

                        // Create the AlertDialog
                        builder.create()
                    }
                    alertDialog?.show()
                }
                catch (e : Exception)
                {
                    Log.e("DEBUG", e.toString())
                }
            }
            else
            {
                bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, FileOutputStream(filesDir + "/" + filename))
                fragmentManager?.popBackStack()
            }
        }
    }

    companion object {
        @JvmStatic
        var last_subject_id : String = ""

        @JvmStatic
        fun newInstance(bitmap: Bitmap, filesDir : String) : CaptureFragment
        {
            val fragment = CaptureFragment()
            fragment.bitmap = bitmap
            fragment.filesDir = filesDir
            return fragment
        }
    }
}