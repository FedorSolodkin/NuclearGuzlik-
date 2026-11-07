package com.guzlik.voice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom sheet выбора шаблона обработки записи.
 * Свободная запись — доступна всегда. Профессиональные шаблоны — Pro-only.
 */
class TemplatePickerSheet : BottomSheetDialogFragment() {

    var onTemplateSelected: ((LlmHelper.Template?) -> Unit)? = null

    private val templateDescriptions = mapOf(
        LlmHelper.Template.MEETING      to "Участники, темы, решения, задачи",
        LlmHelper.Template.MEDICAL      to "Жалобы, анамнез, диагноз, назначения",
        LlmHelper.Template.CONSULTATION to "Запрос, рекомендации, договорённости",
        LlmHelper.Template.INTERVIEW    to "Боли, инсайты, цитаты респондента",
        LlmHelper.Template.LECTURE      to "Тезисы, определения, примеры, итог"
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_template_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val currentId = AppPrefs.getSelectedTemplateId(ctx)
        val checkFreeform = view.findViewById<TextView>(R.id.checkFreeform)
        val rowFreeform   = view.findViewById<LinearLayout>(R.id.rowFreeform)

        checkFreeform.visibility = if (currentId == null) View.VISIBLE else View.GONE
        rowFreeform.setOnClickListener {
            AppPrefs.setSelectedTemplateId(ctx, null)
            onTemplateSelected?.invoke(null)
            dismiss()
        }

        val container = view.findViewById<LinearLayout>(R.id.containerTemplates)
        val inflater  = LayoutInflater.from(ctx)
        val isPro     = ProFeatures.isProUser(ctx)

        LlmHelper.Template.values().forEach { tpl ->
            val row = inflater.inflate(R.layout.item_template_row, container, false) as LinearLayout
            val tvName = row.findViewById<TextView>(R.id.tvTemplateName)
            val tvDesc = row.findViewById<TextView>(R.id.tvTemplateDesc)
            val tvBadge = row.findViewById<TextView>(R.id.tvTemplateBadge)
            val tvCheck = row.findViewById<TextView>(R.id.tvTemplateCheck)

            tvName.text = tpl.displayName
            tvDesc.text = templateDescriptions[tpl] ?: ""
            tvCheck.visibility = if (currentId == tpl.id) View.VISIBLE else View.GONE
            tvBadge.visibility = if (isPro) View.GONE else View.VISIBLE

            row.setOnClickListener {
                if (!ProFeatures.requirePro(ctx, ProFeatures.Feature.AI_TEMPLATES)) {
                    dismiss()
                    return@setOnClickListener
                }
                AppPrefs.setSelectedTemplateId(ctx, tpl.id)
                onTemplateSelected?.invoke(tpl)
                dismiss()
            }
            container.addView(row)
        }

        if (!isPro) {
            for (i in 0 until container.childCount) {
                container.getChildAt(i).alpha = 0.65f
            }
        }
    }
}
