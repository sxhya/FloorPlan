package ui

import model.*
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*

class WallLayoutWindow(val app: FloorPlanApp, val doc: WallLayoutDocument) : JFrame() {
    val canvas = WallLayoutCanvas(doc)

    init {
        doc.window = this
        val side = if (doc.isFront) "Front" else "Back"
        title = "Wall Layout Editor - $side Face"
        setSize(800, 600)
        
        layout = BorderLayout()
        add(canvas, BorderLayout.CENTER)
        
        setupToolbar()
        
        addWindowListener(object : WindowAdapter() {
            override fun windowActivated(e: WindowEvent?) {
                app.activeWindow = this@WallLayoutWindow
                app.activeDocument = null // WallLayoutDocument is not a FloorPlanDocument
                app.sidePanel.updateFieldsForActiveDocument()
                doc.autoScaleToFit()
            }
        })
        
        isVisible = true
    }

    private fun setupToolbar() {
        val toolBar = JToolBar()
        
        val zoomOutBtn = JButton("🔍-")
        zoomOutBtn.addActionListener {
            val centerX = canvas.width / 2.0
            val centerY = canvas.height / 2.0
            doc.zoom(1.0 / 1.1, centerX, centerY)
            canvas.repaint()
        }
        toolBar.add(zoomOutBtn)
        
        val zoomInBtn = JButton("🔍+")
        zoomInBtn.addActionListener {
            val centerX = canvas.width / 2.0
            val centerY = canvas.height / 2.0
            doc.zoom(1.1, centerX, centerY)
            canvas.repaint()
        }
        toolBar.add(zoomInBtn)

        add(toolBar, BorderLayout.NORTH)
    }

    fun updateTitle() {
        val side = if (doc.isFront) "Front" else "Back"
        val baseTitle = "Wall Layout Editor - $side Face"
        title = if (doc.isModified) "$baseTitle *" else baseTitle
    }
}
