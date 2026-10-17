package com.framegen.pvr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private companion object {
        const val REQ_CAPTURE = 100
    }

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        col.addView(TextView(this).apply {
            text = "FrameGen PVR"
            textSize = 24f
        })
        col.addView(text("Interpolacao de quadros (2x) para jogos e emuladores. Prototipo experimental."))

        status = text("")
        status.setPadding(0, dp(12), 0, dp(12))
        col.addView(status)

        col.addView(button("1. Ativar servico de acessibilidade") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("2. Iniciar e escolher o jogo") { startCapture() })
        col.addView(button("Parar") {
            stopService(Intent(this, ProjectionService::class.java))
            refresh()
        })

        col.addView(section("Ajustes (valem na proxima vez que iniciar)"))

        addSpinner(col, "Qualidade do movimento", Prefs.QUALITY_NAMES, Prefs.getQuality(this)) {
            Prefs.setQuality(this, it)
        }
        addSpinner(col, "Resolucao de processamento", Prefs.SCALE_NAMES, Prefs.getScale(this)) {
            Prefs.setScale(this, it)
        }

        val sharpLabel = text("Nitidez: ${Prefs.getSharpen(this)}%")
        col.addView(sharpLabel)
        col.addView(SeekBar(this).apply {
            max = 100
            progress = Prefs.getSharpen(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    sharpLabel.text = "Nitidez: $p%"
                    Prefs.setSharpen(this@MainActivity, p)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        val protLabel = text("Protecao contra distorcao: ${Prefs.getProtect(this)}%")
        col.addView(protLabel)
        col.addView(SeekBar(this).apply {
            max = 100
            progress = Prefs.getProtect(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    protLabel.text = "Protecao contra distorcao: $p%"
                    Prefs.setProtect(this@MainActivity, p)
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        col.addView(text("Mais alto = menos distorcao, mas mais quadros repetidos nas areas duvidosas."))

        col.addView(check("Interpolacao ligada", Prefs.getInterpolate(this)) { Prefs.setInterpolate(this, it) })
        col.addView(
            check(
                "Baixa latencia (preve o proximo quadro, menos atraso no toque)",
                Prefs.getLowLat(this)
            ) { Prefs.setLowLat(this, it) }
        )
        col.addView(check("Mostrar FPS na tela", Prefs.getHud(this)) { Prefs.setHud(this, it) })
        col.addView(
            check(
                "Nao interpolar se o jogo ja passar de ~55 FPS (esconde o overlay)",
                Prefs.getBypass(this)
            ) { Prefs.setBypass(this, it) }
        )

        col.addView(section("Como usar"))
        col.addView(
            text(
                "1) Ative o servico em Acessibilidade > FrameGen PVR.\n" +
                    "2) Abra o jogo/emulador e limite ele a 30 FPS (ou 40).\n" +
                    "3) Volte aqui, toque em \"Iniciar\" e, na janela do sistema, escolha " +
                    "\"Um unico app\" e selecione o jogo. NAO escolha \"Tela inteira\" " +
                    "(a imagem entraria em loop).\n" +
                    "4) Abra o jogo de novo. O FPS aparece no canto da tela.\n\n" +
                    "Para parar: botao Parar ou a notificacao."
            )
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val a11y = if (Bus.overlay != null) "Acessibilidade: ATIVA" else "Acessibilidade: DESATIVADA"
        val run = if (Bus.running) "Captura: RODANDO" else "Captura: parada"
        status.text = "$a11y\n$run"
    }

    private fun startCapture() {
        if (Bus.overlay == null) {
            Toast.makeText(this, "Ative o servico de acessibilidade primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        startActivityForResult(intent, REQ_CAPTURE)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            stopService(Intent(this, ProjectionService::class.java))
            val i = Intent(this, ProjectionService::class.java)
                .putExtra(ProjectionService.EXTRA_CODE, resultCode)
                .putExtra(ProjectionService.EXTRA_DATA, data)
            startForegroundService(i)
            Toast.makeText(this, "Pronto! Abra o jogo.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "Captura cancelada.", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    // ---------------- helpers de UI ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String) = TextView(this).apply {
        text = s
        textSize = 14f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun section(s: String) = TextView(this).apply {
        text = s
        textSize = 17f
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun check(label: String, initial: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        text = label
        isChecked = initial
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    private fun addSpinner(
        parent: LinearLayout, label: String, items: Array<String>, selected: Int, onSel: (Int) -> Unit
    ) {
        parent.addView(text(label))
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        sp.setSelection(selected)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSel(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        parent.addView(sp)
    }
}
