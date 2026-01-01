package ui

import model.*
import ui.components.AppIcons
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import kotlin.math.roundToInt

class EditorWindow(val app: FloorPlanApp, val doc: FloorPlanDocument) : JFrame() {
    private val scaleLabel = JLabel("100%")
    
    val normalBtn = JToggleButton(AppIcons.MOUSE_POINTER, true)
    val dragBtn = JToggleButton(AppIcons.HAND)
    val rulerBtn = JToggleButton(AppIcons.RULER)

    init {
        doc.window = this
        title = "Floor Plan Editor"
        setSize(1000, 700)
        
        layout = BorderLayout()
        add(doc.canvas, BorderLayout.CENTER)
        
        setupToolbar()
        
        addWindowListener(object : WindowAdapter() {
            override fun windowActivated(e: WindowEvent?) {
                app.activeDocument = doc
                app.activeWindow = this@EditorWindow
                app.updateUndoRedoStates()
                app.sidePanel.updateFieldsForActiveDocument()
                app.statsPanel.update()
                app.repaintAllCanvases()
            }
            
            override fun windowClosing(e: WindowEvent?) {
                app.closeDocument(doc)
            }
        })
        
        updateTitle()
        updateScaleLabel()
        isVisible = true
    }

    private fun setupToolbar() {
        val toolBar = JToolBar()
        
        normalBtn.toolTipText = "Normal Mode"
        dragBtn.toolTipText = "Scene Drag Mode"
        rulerBtn.toolTipText = "Ruler Mode"

        val group = ButtonGroup()
        group.add(normalBtn)
        group.add(dragBtn)
        group.add(rulerBtn)

        normalBtn.addActionListener { 
            doc.currentMode = AppMode.NORMAL 
            doc.canvas.cursor = Cursor.getDefaultCursor()
            doc.canvas.repaint()
        }
        dragBtn.addActionListener { 
            doc.currentMode = AppMode.DRAG 
            doc.canvas.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            doc.canvas.repaint()
        }
        rulerBtn.addActionListener { 
            doc.currentMode = AppMode.RULER 
            doc.canvas.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            doc.canvas.repaint()
        }

        toolBar.add(normalBtn)
        toolBar.add(dragBtn)
        toolBar.add(rulerBtn)
        
        toolBar.addSeparator()
        
        val zoomOutBtn = JButton("🔍-")
        zoomOutBtn.toolTipText = "Zoom Out"
        zoomOutBtn.addActionListener {
            doc.scale = (doc.scale / 1.1).coerceAtLeast(doc.MIN_SCALE)
            updateScaleLabel()
            doc.canvas.repaint()
        }
        toolBar.add(zoomOutBtn)
        
        scaleLabel.border = BorderFactory.createEmptyBorder(0, 5, 0, 5)
        toolBar.add(scaleLabel)
        
        val zoomInBtn = JButton("🔍+")
        zoomInBtn.toolTipText = "Zoom In"
        zoomInBtn.addActionListener {
            doc.scale = (doc.scale * 1.1).coerceAtMost(doc.MAX_SCALE)
            updateScaleLabel()
            doc.canvas.repaint()
        }
        toolBar.add(zoomInBtn)

        add(toolBar, BorderLayout.NORTH)
        
        // Fixed size for buttons as per previous requirements
        listOf(normalBtn, dragBtn, rulerBtn, zoomInBtn, zoomOutBtn).forEach {
            it.preferredSize = Dimension(40, 40)
            it.maximumSize = Dimension(40, 40)
            it.minimumSize = Dimension(40, 40)
        }
    }

    fun updateTitle() {
        val baseTitle = if (doc.currentFile != null) {
            "Floor Plan Editor - ${doc.currentFile!!.name}"
        } else {
            "Floor Plan Editor"
        }
        title = if (doc.isModified) "$baseTitle *" else baseTitle
    }

    fun updateScaleLabel() {
        scaleLabel.text = "${(doc.scale * 10000).roundToInt()}%"
    }
}
