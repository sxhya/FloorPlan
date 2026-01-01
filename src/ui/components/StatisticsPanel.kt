package ui.components

import model.*
import model.Window as PlanWindow
import ui.FloorPlanApp
import java.awt.Color
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

class StatisticsPanel(private val app: FloorPlanApp) : JPanel() {
    private val wallAreaLabel = JLabel("Total Wall Area: 0.00 m²")
    private val roomAreaLabel = JLabel("Total Room Area: 0.00 m²")
    private val unusableAreaLabel = JLabel("Unusable Area: 0.00 m²")
    private val doorAreaLabel = JLabel("Door Area/Vol: 0.00 m² / 0.00 m³")
    private val windowAreaLabel = JLabel("Window Area/Vol: 0.00 m² / 0.00 m³")
    private val openingVolumeLabel = JLabel("Opening Volume: 0.00 m³")
    private val warningLabel = JLabel("")
    private var problematicElements = mutableListOf<PlanElement>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createTitledBorder("Statistics")
        add(wallAreaLabel)
        add(roomAreaLabel)
        add(unusableAreaLabel)
        add(doorAreaLabel)
        add(windowAreaLabel)
        add(openingVolumeLabel)
        add(warningLabel)
        warningLabel.foreground = Color.RED
        warningLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        warningLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (problematicElements.isNotEmpty()) {
                    val doc = app.activeDocument ?: return
                    val el = problematicElements.first()
                    doc.recenterOnElement(el)
                    doc.selectedElement = el
                    app.sidePanel.updateFields(el)
                    doc.canvas.repaint()
                }
            }
        })
    }

    fun update() {
        val doc = app.activeDocument ?: return
        val walls = doc.elements.filterIsInstance<Wall>()
        val rooms = doc.elements.filterIsInstance<Room>()
        val windows = doc.elements.filterIsInstance<PlanWindow>()
        val doors = doc.elements.filterIsInstance<Door>()
        val unusable = doc.elements.filter { it is Stairs }
        val emptySpaces = doc.elements.filterIsInstance<FloorOpening>()
        
        problematicElements.clear()

        var totalWallArea = 0.0
        for (wall in walls) {
            totalWallArea += wall.width.toDouble() * wall.height.toDouble()
        }
        wallAreaLabel.text = "Total Wall Area: %.2f m²".format(totalWallArea / 10000.0)

        var totalRoomArea = 0.0
        for (room in rooms) {
            totalRoomArea += room.width.toDouble() * room.height.toDouble()
        }
        roomAreaLabel.text = "Total Room Area: %.2f m²".format(totalRoomArea / 10000.0)

        var totalUnusableArea = 0.0
        for (u in unusable) {
            totalUnusableArea += u.getArea()
        }
        for (es in emptySpaces) {
            totalUnusableArea += es.getArea()
        }
        unusableAreaLabel.text = "Unusable Area: %.2f m²".format(totalUnusableArea / 10000.0)

        var totalDoorArea = 0.0
        var totalDoorVol = 0.0
        for (door in doors) {
            val wall = doc.findContainingWall(door.x, door.y, door.width, door.height)
            val effectiveWidth = if (wall != null) {
                val isVertical = wall.width < wall.height
                if (isVertical) door.height else door.width
            } else {
                maxOf(door.width, door.height)
            }
            totalDoorArea += effectiveWidth.toDouble() * door.verticalHeight
            totalDoorVol += door.getArea() * door.verticalHeight
        }
        doorAreaLabel.text = "Door Area/Vol: %.2f m² / %.2f m³".format(totalDoorArea / 10000.0, totalDoorVol / 1000000.0)

        var totalWindowArea = 0.0
        var totalWindowVol = 0.0
        for (window in windows) {
            val wall = doc.findContainingWall(window.x, window.y, window.width, window.height)
            val effectiveWidth = if (wall != null) {
                val isVertical = wall.width < wall.height
                if (isVertical) window.height else window.width
            } else {
                maxOf(window.width, window.height)
            }
            totalWindowArea += effectiveWidth.toDouble() * window.height3D
            totalWindowVol += window.getArea() * window.height3D
        }
        windowAreaLabel.text = "Window Area/Vol: %.2f m² / %.2f m³".format(totalWindowArea / 10000.0, totalWindowVol / 1000000.0)

        openingVolumeLabel.text = "Opening Volume: %.2f m³".format((totalDoorVol + totalWindowVol) / 1000000.0)

        val sb = StringBuilder()
        for (room in rooms) {
            val containedWalls = walls.filter { room.getBounds().contains(it.getBounds()) }
            val wallArea = containedWalls.sumOf { it.width.toDouble() * it.height.toDouble() }
            if (wallArea > room.getArea() * 0.5) {
                sb.append("Room at (${room.x},${room.y}) is >50% walls. ")
                problematicElements.add(room)
            }
        }
        
        val intersections = doc.calculateIntersections()
        if (intersections.isNotEmpty()) {
            sb.append("${intersections.size} intersections detected. ")
            problematicElements.addAll(intersections.map { it.el1 })
            problematicElements.addAll(intersections.map { it.el2 })
        }

        if (sb.isNotEmpty()) {
            warningLabel.text = sb.toString()
            warningLabel.isVisible = true
        } else {
            warningLabel.text = ""
            warningLabel.isVisible = false
        }
    }
}
