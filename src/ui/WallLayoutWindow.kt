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
            val oldScale = doc.scale
            doc.scale = (doc.scale / 1.1).coerceAtLeast(doc.MIN_SCALE)
            if (oldScale != doc.scale) {
                val centerX = canvas.width / 2.0
                val centerY = canvas.height / 2.0
                val modelCenterX = doc.screenToModel(centerX.toInt(), doc.offsetX)
                val modelCenterY = doc.screenToModel(centerY.toInt(), doc.offsetY, true)
                
                doc.offsetX = centerX / (doc.scale * doc.pixelsPerCm) - modelCenterX
                doc.offsetY = centerY / (doc.scale * doc.pixelsPerCm) + modelCenterY
                canvas.repaint()
            }
        }
        toolBar.add(zoomOutBtn)
        
        val zoomInBtn = JButton("🔍+")
        zoomInBtn.addActionListener {
            val oldScale = doc.scale
            doc.scale = (doc.scale * 1.1).coerceAtMost(doc.MAX_SCALE)
            if (oldScale != doc.scale) {
                val centerX = canvas.width / 2.0
                val centerY = canvas.height / 2.0
                val modelCenterX = doc.screenToModel(centerX.toInt(), doc.offsetX)
                val modelCenterY = doc.screenToModel(centerY.toInt(), doc.offsetY, true)
                
                doc.offsetX = centerX / (doc.scale * doc.pixelsPerCm) - modelCenterX
                doc.offsetY = centerY / (doc.scale * doc.pixelsPerCm) + modelCenterY
                canvas.repaint()
            }
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
