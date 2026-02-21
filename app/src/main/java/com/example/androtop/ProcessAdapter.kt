package com.example.androtop

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class ProcessAdapter : ListAdapter<ProcessInfo, ProcessAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val pidText: TextView = view.findViewById(R.id.pidText)
        val nameText: TextView = view.findViewById(R.id.nameText)
        val cpuText: TextView = view.findViewById(R.id.cpuText)
        val memText: TextView = view.findViewById(R.id.memText)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_process, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val process = getItem(position)

        holder.pidText.text = process.pid.toString()
        holder.nameText.text = process.name

        val cpuStr = if (process.cpuPercent >= 10.0) {
            "${process.cpuPercent.toInt()}%"
        } else {
            "%.1f%%".format(process.cpuPercent)
        }
        holder.cpuText.text = cpuStr

        val memStr = if (process.memPercent >= 10.0) {
            "${process.memPercent.toInt()}%"
        } else {
            "%.1f%%".format(process.memPercent)
        }
        holder.memText.text = memStr

        // Color-code CPU usage
        val context = holder.itemView.context
        val cpuColor = when {
            process.cpuPercent >= 100.0 -> context.getColor(R.color.cpu_high)
            process.cpuPercent >= 50.0 -> context.getColor(R.color.cpu_medium)
            else -> context.getColor(R.color.cpu_low)
        }
        holder.cpuText.setTextColor(cpuColor)
    }

    private class DiffCallback : DiffUtil.ItemCallback<ProcessInfo>() {
        override fun areItemsTheSame(oldItem: ProcessInfo, newItem: ProcessInfo): Boolean {
            return oldItem.pid == newItem.pid
        }

        override fun areContentsTheSame(oldItem: ProcessInfo, newItem: ProcessInfo): Boolean {
            return oldItem == newItem
        }
    }
}
