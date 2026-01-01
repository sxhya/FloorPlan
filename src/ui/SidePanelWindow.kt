package ui

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel

class SidePanelWindow(val app: FloorPlanApp) : JFrame() {
    init {
        title = "Properties & Statistics"
        setSize(350, 800)
        layout = BorderLayout()
        
        val sideContainer = JPanel(BorderLayout())
        sideContainer.add(app.sidePanel, BorderLayout.CENTER)
        
        val bottomPanel = JPanel()
        bottomPanel.layout = BoxLayout(bottomPanel, BoxLayout.Y_AXIS)
        bottomPanel.add(app.statsPanel)
        sideContainer.add(bottomPanel, BorderLayout.SOUTH)
        
        add(sideContainer, BorderLayout.CENTER)
        
        defaultCloseOperation = DISPOSE_ON_CLOSE
        
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                app.quitApp()
            }
        })
        
        isVisible = true
    }
}
