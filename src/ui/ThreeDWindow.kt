package ui

import ui.components.AppIcons
import ui.components.ThreeDPanel
import java.awt.*
import javax.swing.*

class ThreeDWindow(val app: FloorPlanApp, val doc: ThreeDDocument) : JFrame() {
    private lateinit var zoomInBtn: JButton
    private lateinit var zoomOutBtn: JButton
    private lateinit var walkModeBtn: JToggleButton
    private lateinit var dayModeCheckbox: JCheckBox
    private lateinit var controlsLabel: JLabel
    
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
                app.sidePanel.updateThreeDFields(doc)
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
        
        toolBar.addSeparator()
        
        // Walk mode toggle button
        walkModeBtn = JToggleButton("🚶 Walk Mode")
        walkModeBtn.toolTipText = "Enter first-person walking mode (WASD to move, mouse to look)"
        walkModeBtn.addActionListener {
            if (walkModeBtn.isSelected) {
                // Enter walk mode
                doc.cameraMode = CameraMode.WALK
                doc.initializePlayerPosition()
                doc.panel.updateCamera()
                doc.panel.requestFocusInWindow()
                controlsLabel.isVisible = true
                zoomInBtn.isEnabled = false
                zoomOutBtn.isEnabled = false
            } else {
                // Return to orbit mode
                doc.cameraMode = CameraMode.ORBIT
                doc.panel.updateCamera()
                controlsLabel.isVisible = false
                zoomInBtn.isEnabled = true
                zoomOutBtn.isEnabled = true
            }
        }
        toolBar.add(walkModeBtn)
        
        toolBar.addSeparator()
        
        // Day mode checkbox
        dayModeCheckbox = JCheckBox("Day mode", doc.isDayMode)
        dayModeCheckbox.toolTipText = "Toggle between day (lit from all sides) and night (room lights only) mode"
        dayModeCheckbox.addActionListener {
            doc.isDayMode = dayModeCheckbox.isSelected
            doc.panel.updateLighting()
        }
        toolBar.add(dayModeCheckbox)
        
        toolBar.addSeparator()
        
        // Controls help label (shown only in walk mode)
        controlsLabel = JLabel("  WASD: Move | Mouse: Look | Space/Shift: Up/Down | ESC: Exit")
        controlsLabel.foreground = Color.DARK_GRAY
        controlsLabel.isVisible = false
        toolBar.add(controlsLabel)
        
        add(toolBar, BorderLayout.NORTH)
        
        listOf(zoomInBtn, zoomOutBtn).forEach {
            it.preferredSize = Dimension(60, 30)
            it.maximumSize = Dimension(80, 30)
            it.minimumSize = Dimension(50, 30)
        }
        
        walkModeBtn.preferredSize = Dimension(120, 30)
        walkModeBtn.maximumSize = Dimension(140, 30)
        walkModeBtn.minimumSize = Dimension(100, 30)
        
        // Add ESC key listener to exit walk mode
        doc.panel.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE && doc.cameraMode == CameraMode.WALK) {
                    walkModeBtn.isSelected = false
                    doc.cameraMode = CameraMode.ORBIT
                    doc.panel.updateCamera()
                    controlsLabel.isVisible = false
                    zoomInBtn.isEnabled = true
                    zoomOutBtn.isEnabled = true
                }
            }
        })
    }
}
