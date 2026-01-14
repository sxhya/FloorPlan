package ui

import ui.components.AppIcons
import ui.components.ThreeDPanel
import java.awt.*
import javax.swing.*

class ThreeDWindow(val app: FloorPlanApp, val doc: ThreeDDocument) : JFrame() {
    private lateinit var cameraModeBtn: JToggleButton
    private lateinit var walkModeBtn: JToggleButton
    private lateinit var zoomInBtn: JButton
    private lateinit var zoomOutBtn: JButton
    
    // Walk mode control buttons
    private lateinit var forwardBtn: JButton
    private lateinit var backBtn: JButton
    private lateinit var leftBtn: JButton
    private lateinit var rightBtn: JButton
    private lateinit var jumpBtn: JButton
    
    init {
        doc.window = this
        title = if (doc.currentFile != null) "3D House Model - ${doc.currentFile?.name}" else "3D House Model"
        setSize(1000, 700)
        
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                doc.panel.stopWalkMode()
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
        
        // Camera mode toggle buttons
        cameraModeBtn = JToggleButton("Camera", true)
        walkModeBtn = JToggleButton("Walk", false)
        
        val modeGroup = ButtonGroup()
        modeGroup.add(cameraModeBtn)
        modeGroup.add(walkModeBtn)
        
        cameraModeBtn.addActionListener {
            if (cameraModeBtn.isSelected) {
                doc.cameraMode = CameraMode.ORBIT
                doc.panel.stopWalkMode()
                zoomInBtn.isEnabled = true
                zoomOutBtn.isEnabled = true
                setWalkButtonsEnabled(false)
            }
        }
        
        walkModeBtn.addActionListener {
            if (walkModeBtn.isSelected) {
                doc.cameraMode = CameraMode.WALK
                doc.panel.startWalkMode()
                zoomInBtn.isEnabled = false
                zoomOutBtn.isEnabled = false
                setWalkButtonsEnabled(true)
            }
        }
        
        toolBar.add(cameraModeBtn)
        toolBar.add(walkModeBtn)
        
        toolBar.addSeparator()
        
        val highlightBtn = JCheckBox("Highlighting", true)
        highlightBtn.addActionListener {
            doc.showHighlighting = highlightBtn.isSelected
            doc.panel.repaint()
        }
        toolBar.add(highlightBtn)
        
        toolBar.addSeparator()
        
        zoomOutBtn = JButton("🔍-")
        zoomOutBtn.addActionListener {
            doc.scale /= 1.1
            doc.panel.repaint()
        }
        toolBar.add(zoomOutBtn)
        
        zoomInBtn = JButton("🔍+")
        zoomInBtn.addActionListener {
            doc.scale *= 1.1
            doc.panel.repaint()
        }
        toolBar.add(zoomInBtn)
        
        toolBar.addSeparator()
        
        // Walk mode control buttons
        forwardBtn = JButton("↑")
        forwardBtn.toolTipText = "Move Forward (W)"
        forwardBtn.isEnabled = false
        forwardBtn.addActionListener { doc.panel.moveForward() }
        toolBar.add(forwardBtn)
        
        backBtn = JButton("↓")
        backBtn.toolTipText = "Move Backward (S)"
        backBtn.isEnabled = false
        backBtn.addActionListener { doc.panel.moveBackward() }
        toolBar.add(backBtn)
        
        leftBtn = JButton("←")
        leftBtn.toolTipText = "Move Left (A)"
        leftBtn.isEnabled = false
        leftBtn.addActionListener { doc.panel.moveLeft() }
        toolBar.add(leftBtn)
        
        rightBtn = JButton("→")
        rightBtn.toolTipText = "Move Right (D)"
        rightBtn.isEnabled = false
        rightBtn.addActionListener { doc.panel.moveRight() }
        toolBar.add(rightBtn)
        
        jumpBtn = JButton("Jump")
        jumpBtn.toolTipText = "Jump (Space)"
        jumpBtn.isEnabled = false
        jumpBtn.addActionListener { doc.panel.jump() }
        toolBar.add(jumpBtn)
        
        add(toolBar, BorderLayout.NORTH)
        
        listOf(cameraModeBtn, walkModeBtn, zoomInBtn, zoomOutBtn).forEach {
            it.preferredSize = Dimension(60, 30)
            it.maximumSize = Dimension(80, 30)
            it.minimumSize = Dimension(50, 30)
        }
        
        listOf(forwardBtn, backBtn, leftBtn, rightBtn).forEach {
            it.preferredSize = Dimension(40, 30)
            it.maximumSize = Dimension(50, 30)
            it.minimumSize = Dimension(30, 30)
        }
        
        jumpBtn.preferredSize = Dimension(50, 30)
        jumpBtn.maximumSize = Dimension(60, 30)
        jumpBtn.minimumSize = Dimension(40, 30)
    }
    
    private fun setWalkButtonsEnabled(enabled: Boolean) {
        forwardBtn.isEnabled = enabled
        backBtn.isEnabled = enabled
        leftBtn.isEnabled = enabled
        rightBtn.isEnabled = enabled
        jumpBtn.isEnabled = enabled
    }
}
