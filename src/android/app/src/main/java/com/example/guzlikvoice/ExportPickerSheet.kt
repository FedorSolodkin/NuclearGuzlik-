package com.example.guzlikvoice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom sheet выбора формата экспорта записи.
 * Формат SRT доступен только когда есть аудио — иначе строка disabled.
 */
class ExportPickerSheet : BottomSheetDialogFragment() {

    enum class Format { JSON, SRT, AUDIO, PDF, DOCX }

    companion object {
        const val RESULT_FORMAT = "format"
        fun newInstance(hasAudio: Boolean, requestKey: String) = ExportPickerSheet().apply {
            arguments = Bundle().apply { putBoolean("has_audio",hasAudio); putString("request_key",requestKey) }
        }
    }
    private val hasAudio: Boolean get() = arguments?.getBoolean("has_audio") == true
    private fun select(format: Format) {
        val key = requireArguments().getString("request_key") ?: return
        parentFragmentManager.setFragmentResult(key,Bundle().apply {putString(RESULT_FORMAT,format.name)})
        dismiss()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_export_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<LinearLayout>(R.id.rowExportJson).setOnClickListener { select(Format.JSON) }
        val rowSrt  = view.findViewById<LinearLayout>(R.id.rowExportSrt)
        val descSrt = view.findViewById<TextView>(R.id.tvExportSrtDesc)
        val rowPdf  = view.findViewById<LinearLayout>(R.id.rowExportPdf)
        val rowDocx = view.findViewById<LinearLayout>(R.id.rowExportDocx)

        if (hasAudio) {
            rowSrt.alpha = 1f
            rowSrt.setOnClickListener {
                select(Format.SRT)
            }
        } else {
            rowSrt.alpha = 0.5f
            descSrt.text = "Недоступно — у записи нет аудио"
            rowSrt.setOnClickListener {
                Toast.makeText(requireContext(), "SRT доступен только для записей с аудио", Toast.LENGTH_SHORT).show()
            }
        }

        val rowAudio  = view.findViewById<LinearLayout>(R.id.rowExportAudio)
        val descAudio = view.findViewById<TextView>(R.id.tvExportAudioDesc)
        if (hasAudio) {
            rowAudio.setOnClickListener {
                select(Format.AUDIO)
            }
        } else {
            rowAudio.alpha = 0.5f
            descAudio.text = "Недоступно — у записи нет аудио"
            rowAudio.setOnClickListener {
                Toast.makeText(requireContext(), "Аудио доступно только для записей с аудио", Toast.LENGTH_SHORT).show()
            }
        }

        rowPdf.setOnClickListener {
            select(Format.PDF)
        }
        rowDocx.setOnClickListener {
            select(Format.DOCX)
        }
    }
}
