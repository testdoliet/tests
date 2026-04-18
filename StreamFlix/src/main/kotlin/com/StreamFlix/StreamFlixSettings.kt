package com.StreamFlix

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StreamFlixSettingsFragment : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val scrollView = ScrollView(requireContext())
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // Título
        val titleText = TextView(requireContext()).apply {
            text = "⚙️ StreamFlix Settings"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
        }
        layout.addView(titleText)

        // Divisor
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                bottomMargin = 20
                topMargin = 10
            }
            setBackgroundColor(0x33FFFFFF)
        }
        layout.addView(divider)

        // Seção de Filmes
        val moviesTitle = TextView(requireContext()).apply {
            text = "🎬 FILMES"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 20
                bottomMargin = 15
            }
        }
        layout.addView(moviesTitle)

        val movieCategories = StreamFlixProvider.ALL_MOVIE_CATEGORIES
        val activeMovies = StreamFlixProvider.getActiveMovieCategories().keys
        val movieCheckStates = mutableMapOf<String, Boolean>()

        movieCategories.forEach { (id, name) ->
            val checkBox = CheckBox(requireContext()).apply {
                text = name
                isChecked = activeMovies.contains(id)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                setOnCheckedChangeListener { _, isChecked ->
                    movieCheckStates[id] = isChecked
                }
            }
            layout.addView(checkBox)
            movieCheckStates[id] = checkBox.isChecked
        }

        // Seção de Séries
        val seriesTitle = TextView(requireContext()).apply {
            text = "📺 SÉRIES"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 30
                bottomMargin = 15
            }
        }
        layout.addView(seriesTitle)

        val seriesCategories = StreamFlixProvider.ALL_SERIES_CATEGORIES
        val activeSeries = StreamFlixProvider.getActiveSeriesCategories().keys
        val seriesCheckStates = mutableMapOf<String, Boolean>()

        seriesCategories.forEach { (id, name) ->
            val checkBox = CheckBox(requireContext()).apply {
                text = name
                isChecked = activeSeries.contains(id)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                setOnCheckedChangeListener { _, isChecked ->
                    seriesCheckStates[id] = isChecked
                }
            }
            layout.addView(checkBox)
            seriesCheckStates[id] = checkBox.isChecked
        }

        // Botões
        val selectAllButton = Button(requireContext()).apply {
            text = "✅ Selecionar Todas"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 30 }
            setOnClickListener {
                movieCheckStates.keys.forEach { movieCheckStates[it] = true }
                seriesCheckStates.keys.forEach { seriesCheckStates[it] = true }
                for (i in 0 until layout.childCount) {
                    val child = layout.getChildAt(i)
                    if (child is CheckBox) {
                        child.isChecked = true
                    }
                }
            }
        }
        layout.addView(selectAllButton)

        val deselectAllButton = Button(requireContext()).apply {
            text = "❌ Desmarcar Todas"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 10 }
            setOnClickListener {
                movieCheckStates.keys.forEach { movieCheckStates[it] = false }
                seriesCheckStates.keys.forEach { seriesCheckStates[it] = false }
                for (i in 0 until layout.childCount) {
                    val child = layout.getChildAt(i)
                    if (child is CheckBox) {
                        child.isChecked = false
                    }
                }
            }
        }
        layout.addView(deselectAllButton)

        val saveButton = Button(requireContext()).apply {
            text = "💾 Salvar e Reiniciar"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
            setOnClickListener {
                val selectedMovies = movieCheckStates.filter { it.value }.keys
                val selectedSeries = seriesCheckStates.filter { it.value }.keys
                
                StreamFlixProvider.saveActiveMovieCategories(selectedMovies)
                StreamFlixProvider.saveActiveSeriesCategories(selectedSeries)
                
                AlertDialog.Builder(requireContext())
                    .setTitle("Reiniciar App?")
                    .setMessage("Configurações salvas! Reiniciar o aplicativo para aplicar as mudanças?")
                    .setPositiveButton("Sim") { _, _ ->
                        restartApp(requireContext())
                    }
                    .setNegativeButton("Não") { dialog, _ ->
                        dialog.dismiss()
                        dismiss()
                    }
                    .show()
            }
        }
        layout.addView(saveButton)

        scrollView.addView(layout)
        return scrollView
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.peekHeight = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            600f,
            resources.displayMetrics
        ).toInt()
        return dialog
    }

    private fun restartApp(context: Context) {
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component

        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
