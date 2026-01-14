package ui

import ui.components.AppIcons
import ui.components.ThreeDPanel
import java.awt.*
import javax.swing.*

class ThreeDWindow(val app: FloorPlanApp, val doc: ThreeDDocument) : JFrame() {
    init {
        doc.window = this
        title = if (doc.currentFile != null) "3D House Model - ${doc.currentFile?.name}" else "3D House Model"
        setSize(1000, 700)
        
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                app.close3DDocument(doc)
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
        
        val rotateBtn = JToggleButton("Rotate", true)
        
        val highlightBtn = JCheckBox("Highlighting", true)
        highlightBtn.addActionListener {
            doc.showHighlighting = highlightBtn.isSelected
            doc.panel.repaint()
        }
        
        val group = ButtonGroup()
        group.add(rotateBtn)
        
        rotateBtn.addActionListener {
            doc.panel.setDragMode(ThreeDPanel.DragMode.ROTATE)
        }
        
        toolBar.add(rotateBtn)
        toolBar.add(highlightBtn)
        
        toolBar.addSeparator()
        
        val zoomOutBtn = JButton("🔍-")
        zoomOutBtn.addActionListener {
            doc.scale /= 1.1
            doc.panel.repaint()
        }
        toolBar.add(zoomOutBtn)
        
        val zoomInBtn = JButton("🔍+")
        zoomInBtn.addActionListener {
            doc.scale *= 1.1
            doc.panel.repaint()
        }
        toolBar.add(zoomInBtn)
        
        add(toolBar, BorderLayout.NORTH)
        
        listOf(rotateBtn, zoomInBtn, zoomOutBtn).forEach {
            it.preferredSize = Dimension(40, 40)
            it.maximumSize = Dimension(40, 40)
            it.minimumSize = Dimension(40, 40)
        }
    }
}
