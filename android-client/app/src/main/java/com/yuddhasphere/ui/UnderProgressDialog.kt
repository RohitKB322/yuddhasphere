package com.yuddhasphere.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.Window
import android.widget.Button
import android.widget.TextView
import com.yuddhasphere.R

class UnderProgressDialog(private val context: Context) {

    fun show(
        title: String,
        description: String,
        milestone: String
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = LayoutInflater.from(context).inflate(R.layout.dialog_under_progress, null)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvProgressTitle)
        val tvDesc = view.findViewById<TextView>(R.id.tvProgressDesc)
        val tvMilestone = view.findViewById<TextView>(R.id.tvProgressMilestone)
        val btnDismiss = view.findViewById<Button>(R.id.btnProgressDismiss)

        tvTitle.text = title
        tvDesc.text = description
        tvMilestone.text = milestone

        btnDismiss.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }
}
