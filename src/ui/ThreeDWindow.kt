package ui

import ui.components.AppIcons
import ui.components.ThreeDPanel
import java.awt.*
import javax.swing.*

class ThreeDWindow(val app: FloorPlanApp, val doc: ThreeDDocument) : JFrame() {
    init {
        doc.window = this
        title = "3D House Model"
        setSize(1000, 700)
        
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
