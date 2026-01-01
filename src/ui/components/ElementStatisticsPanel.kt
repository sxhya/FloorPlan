package ui.components

import model.Door
import model.PlanElement
import model.Window as PlanWindow
import ui.FloorPlanApp
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

class ElementStatisticsPanel(private val app: FloorPlanApp) : JPanel() {
    private val areaLabel = JLabel("Element Area: 0.00 m²")
    private val openingAreaLabel = JLabel("")
    private val openingVolumeLabel = JLabel("")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createTitledBorder("Element Statistics")
        add(areaLabel)
        add(openingAreaLabel)
        add(openingVolumeLabel)
        openingAreaLabel.isVisible = false
        openingVolumeLabel.isVisible = false
        isVisible = false
    }

    fun updateElementStats(el: PlanElement?) {
        if (el == null) {
            isVisible = false
            return
        }
        isVisible = true
        areaLabel.text = "Element Area: %.2f m²".format(el.getArea() / 10000.0)

        if (el is PlanWindow || el is Door) {
            val h3d = if (el is PlanWindow) el.height3D else (el as Door).height3D
            val wall = app.findContainingWall(el.x, el.y, el.width, el.height)
            val effectiveWidth = if (wall != null) {
                val isVertical = wall.width < wall.height
                if (isVertical) el.height else el.width
            } else {
                maxOf(el.width, el.height)
            }
            
            val opArea = effectiveWidth.toDouble() * h3d
            val opVol = el.getArea() * h3d
            
            openingAreaLabel.text = "Opening Area: %.2f m²".format(opArea / 10000.0)
            openingVolumeLabel.text = "Opening Volume: %.2f m³".format(opVol / 1000000.0)
            openingAreaLabel.isVisible = true
            openingVolumeLabel.isVisible = true
        } else {
            openingAreaLabel.isVisible = false
            openingVolumeLabel.isVisible = false
        }
    }
}
