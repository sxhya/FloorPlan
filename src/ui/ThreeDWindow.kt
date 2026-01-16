package ui

import ui.components.AppIcons
import ui.components.ThreeDPanel
import java.awt.*
import javax.swing.*

class ThreeDWindow(val app: FloorPlanApp, val doc: ThreeDDocument) : JFrame() {
    private lateinit var zoomInBtn: JButton
    private lateinit var zoomOutBtn: JButton
    
    init {
        doc.window = this
        title = if (doc.currentFile != null) "3D House Model - ${doc.currentFile?.name}" else "3D House Model"
        setSize(1000, 700)
        
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                doc.panel.dispose()
                app.close3DDocument(doc)
            }
            override fun windowOpened(e: java.awt.event.WindowEvent?) {
                doc.panel.updateModel()
            }
            override fun windowActivated(e: java.awt.event.WindowEvent?) {
                app.activeWindow = this@ThreeDWindow
                app.activeDocument = null
                app.updateUndoRedoStates()
            }
        })

        layout = BorderLayout()
        add(doc.panel, BorderLayout.CENTER)
        
        setupToolbar()
        
        isVisible = true
    }

    private fun setupToolbar() {
        val toolBar = JToolBar()
        
        zoomOutBtn = JButton("🔍-")
        zoomOutBtn.addActionListener {
            doc.scale /= 1.1
            doc.panel.updateCamera()
        }
        toolBar.add(zoomOutBtn)
        
        zoomInBtn = JButton("🔍+")
        zoomInBtn.addActionListener {
            doc.scale *= 1.1
            doc.panel.updateCamera()
        }
        toolBar.add(zoomInBtn)
        
        add(toolBar, BorderLayout.NORTH)
        
        listOf(zoomInBtn, zoomOutBtn).forEach {
            it.preferredSize = Dimension(60, 30)
            it.maximumSize = Dimension(80, 30)
            it.minimumSize = Dimension(50, 30)
        }
    }
}
