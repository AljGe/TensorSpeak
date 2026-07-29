package com.github.aljge.tensorspeak

import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView

/** Binds a Material Exposed Dropdown to a fixed list of enum-like values. */
internal object EnumDropdown {
    fun <T> bind(
        field: AutoCompleteTextView,
        values: List<T>,
        label: (T) -> String,
        current: T,
        enabled: () -> Boolean = { true },
        onSelected: (T) -> Unit,
    ) {
        val labels = values.map(label)
        field.setAdapter(
            ArrayAdapter(
                field.context,
                android.R.layout.simple_dropdown_item_1line,
                labels,
            ),
        )
        field.setText(label(current), false)
        field.setOnItemClickListener { _, _, position, _ ->
            if (!enabled()) return@setOnItemClickListener
            onSelected(values[position])
        }
    }

    fun <T> setSelection(
        field: AutoCompleteTextView,
        values: List<T>,
        label: (T) -> String,
        current: T,
    ) {
        field.setText(label(current), false)
    }
}
