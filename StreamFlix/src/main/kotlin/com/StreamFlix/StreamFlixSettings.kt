package com.StreamFlix

import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StreamFlixSettingsFragment : BottomSheetDialogFragment() {

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Infla o layout
        val view = NestedScrollView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val contentLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // Título
        val titleText = TextView(requireContext()).apply {
            text = "StreamFlix Settings"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
        }
        contentLayout.addView(titleText)

        // Divisor
        contentLayout.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply { 
                bottomMargin = 20
                topMargin = 10
            }
            setBackgroundColor(0x33FFFFFF)
        })

        // Seção de Filmes
        val moviesTitle = TextView(requireContext()).apply {
            text = "🎬 Filmes"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { 
                topMargin = 20
                bottomMargin = 15
            }
        }
        contentLayout.addView(moviesTitle)

        // Checkboxes para filmes
        val movieCategories = StreamFlixProvider.ALL_MOVIE_CATEGORIES
        val activeMovies = StreamFlixProvider.getActiveMovieCategories().keys
        val movieCheckStates = mutableMapOf<String, Boolean>()

        movieCategories.forEach { (id, name) ->
            val checkBox = android.widget.CheckBox(requireContext()).apply {
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
            contentLayout.addView(checkBox)
            movieCheckStates[id] = isChecked
        }

        // Seção de Séries
        val seriesTitle = TextView(requireContext()).apply {
            text = "📺 Séries"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { 
                topMargin = 30
                bottomMargin = 15
            }
        }
        contentLayout.addView(seriesTitle)

        // Checkboxes para séries
        val seriesCategories = StreamFlixProvider.ALL_SERIES_CATEGORIES
        val activeSeries = StreamFlixProvider.getActiveSeriesCategories().keys
        val seriesCheckStates = mutableMapOf<String, Boolean>()

        seriesCategories.forEach { (id, name) ->
            val checkBox = android.widget.CheckBox(requireContext()).apply {
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
            contentLayout.addView(checkBox)
            seriesCheckStates[id] = isChecked
        }

        // Botão Salvar
        val saveButton = android.widget.Button(requireContext()).apply {
            text = "💾 Salvar e Reiniciar"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { 
                topMargin = 40
                bottomMargin = 20
            }
            setOnClickListener {
                val selectedMovies = movieCheckStates.filter { it.value }.keys
                val selectedSeries = seriesCheckStates.filter { it.value }.keys
                
                StreamFlixProvider.saveActiveMovieCategories(selectedMovies)
                StreamFlixProvider.saveActiveSeriesCategories(selectedSeries)
                
                AlertDialog.Builder(requireContext())
                    .setTitle("Reiniciar App?")
                    .setMessage("As configurações foram salvas. Reiniciar o aplicativo para aplicar as mudanças?")
                    .setPositiveButton("Sim") { _, _ ->
                        restartApp(requireContext())
                    }
                    .setNegativeButton("Não") { dialog, _ ->
                        dialog.dismiss()
                        Toast.makeText(requireContext(), "Configurações salvas! Reinicie o app manualmente.", Toast.LENGTH_LONG).show()
                        dismiss()
                    }
                    .show()
            }
        }
        contentLayout.addView(saveButton)

        // Botão Selecionar Todos
        val selectAllButton = android.widget.Button(requireContext()).apply {
            text = "✅ Selecionar Todos"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 10 }
            setOnClickListener {
                movieCheckStates.keys.forEach { movieCheckStates[it] = true }
                seriesCheckStates.keys.forEach { seriesCheckStates[it] = true }
                // Atualizar UI
                for (i in 0 until contentLayout.childCount) {
                    val child = contentLayout.getChildAt(i)
                    if (child is android.widget.CheckBox) {
                        child.isChecked = true
                    }
                }
            }
        }
        contentLayout.addView(selectAllButton)

        // Botão Desmarcar Todos
        val deselectAllButton = android.widget.Button(requireContext()).apply {
            text = "❌ Desmarcar Todos"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                movieCheckStates.keys.forEach { movieCheckStates[it] = false }
                seriesCheckStates.keys.forEach { seriesCheckStates[it] = false }
                for (i in 0 until contentLayout.childCount) {
                    val child = contentLayout.getChildAt(i)
                    if (child is android.widget.CheckBox) {
                        child.isChecked = false
                    }
                }
            }
        }
        contentLayout.addView(deselectAllButton)

        view.addView(contentLayout)
        return view
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        (dialog as? BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
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
