package ui.components

import model.*
import model.Window as PlanWindow
import ui.FloorPlanApp
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableModel
import kotlin.math.roundToInt

class SidePanel(private val app: FloorPlanApp) : JPanel() {
    private val mainFieldsPanel = JPanel(BorderLayout())
    private val dimensionTableModel = object : DefaultTableModel(arrayOf("Property", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 1
    }
    private val dimensionTable = JTable(dimensionTableModel)

    private val polygonPanel = JPanel(BorderLayout())
    private val polygonTableModel = DefaultTableModel(arrayOf("Marker No", "X", "Y"), 0)
    private val polygonTable = JTable(polygonTableModel)

    private var isUpdatingFields = false

    init {
        layout = BorderLayout()
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        preferredSize = Dimension(300, 700)

        mainFieldsPanel.add(JLabel("Dimension"), BorderLayout.NORTH)
        mainFieldsPanel.add(JScrollPane(dimensionTable), BorderLayout.CENTER)

        polygonPanel.add(JLabel("Polygon Vertices"), BorderLayout.NORTH)
        polygonPanel.add(JScrollPane(polygonTable), BorderLayout.CENTER)

        val polygonPopup = JPopupMenu()
        val duplicateMarkerItem = JMenuItem("Duplicate Marker")
        duplicateMarkerItem.addActionListener {
            val row = polygonTable.selectedRow
            if (row != -1) {
                val es = app.selectedElement as? FloorOpening ?: return@addActionListener
                app.saveState()
                val v = es.vertices[row]
                es.vertices.add(row + 1, Point(v.x + 10, v.y + 10))
                es.updateBounds()
                updateFields(es)
                app.canvas.repaint()
                app.statsPanel.update()
            }
        }
        polygonPopup.add(duplicateMarkerItem)

        val removeMarkerItem = JMenuItem("Remove Marker")
        removeMarkerItem.addActionListener {
            val row = polygonTable.selectedRow
            if (row != -1) {
                val es = app.selectedElement as? FloorOpening ?: return@addActionListener
                if (es.vertices.size > 3) {
                    app.saveState()
                    es.vertices.removeAt(row)
                    es.updateBounds()
                    updateFields(es)
                    app.canvas.repaint()
                    app.statsPanel.update()
                } else {
                    JOptionPane.showMessageDialog(this, "A polygon must have at least 3 vertices")
                }
            }
        }
        polygonPopup.add(removeMarkerItem)

        polygonTable.componentPopupMenu = polygonPopup
        
        dimensionTableModel.addTableModelListener { e ->
            if (!isUpdatingFields && e.type == javax.swing.event.TableModelEvent.UPDATE) {
                val row = e.firstRow
                val source = dimensionTableModel.getValueAt(row, 0).toString().replace(":", "").trim()
                applyManualChanges(source)
            }
        }

        polygonTableModel.addTableModelListener { e ->
            if (!isUpdatingFields && e.type == javax.swing.event.TableModelEvent.UPDATE) {
                val es = app.selectedElement as? FloorOpening ?: return@addTableModelListener
                val row = e.firstRow
                val col = e.column
                val value = polygonTableModel.getValueAt(row, col).toString().toIntOrNull()
                if (value != null) {
                    app.saveState()
                    if (col == 1) es.vertices[row].x = value
                    if (col == 2) es.vertices[row].y = value
                    es.updateBounds()
                    app.canvas.repaint()
                }
            }
        }

        add(mainFieldsPanel, BorderLayout.CENTER)
    }

    fun addElement(type: ElementType) {
        val centerX = app.screenToModel(app.canvas.width / 2, app.offsetX).roundToInt()
        val centerY = app.screenToModel(app.canvas.height / 2, app.offsetY).roundToInt()
        
        val el = when(type) {
            ElementType.WALL -> Wall(centerX - 50, centerY - 10, 100, 20)
            ElementType.ROOM -> Room(centerX - 50, centerY - 50, 100, 100)
            ElementType.STAIRS -> {
                val room = app.selectedElement as? Room
                if (room != null) {
                    Stairs(room.x + 10, room.y + 10, 80, 40)
                } else {
                    Stairs(centerX - 40, centerY - 20, 80, 40)
                }
            }
            ElementType.FLOOR_OPENING -> {
                // This can now be triggered from empty space popup
                app.currentMode = AppMode.RULER
                app.canvas.isCreatingFloorOpening = true
                app.canvas.rulerMarkers.clear()
                app.canvas.rulerClosed = false
                app.canvas.rulerProbeEnabled = true
                app.canvas.repaint()
                return
            }
            else -> null
        }
        
        el?.let {
            app.elements.add(it)
            app.saveState()
            app.selectedElement = it
            updateFields(it)
            app.elementStatsPanel.updateElementStats(it)
            app.statsPanel.update()
            app.canvas.repaint()
        }
    }

    fun updateFields(el: PlanElement) {
        isUpdatingFields = true
        dimensionTableModel.setRowCount(0)
        dimensionTableModel.addRow(arrayOf("Type", el.javaClass.simpleName))
        dimensionTableModel.addRow(arrayOf("X", el.x))
        dimensionTableModel.addRow(arrayOf("Y", el.y))
        dimensionTableModel.addRow(arrayOf("Width", el.width))
        dimensionTableModel.addRow(arrayOf("Height", el.height))
        if (el is PlanWindow || el is Door) {
            val h3d = if (el is PlanWindow) el.height3D else (el as Door).height3D
            dimensionTableModel.addRow(arrayOf("Height 3D", h3d))
            if (el is PlanWindow) {
                dimensionTableModel.addRow(arrayOf("Above floor height", el.aboveFloorHeight))
            }
        }

        if (el is FloorOpening) {
            polygonTableModel.setRowCount(0)
            el.vertices.forEachIndexed { index, point ->
                polygonTableModel.addRow(arrayOf(index + 1, point.x, point.y))
            }
            remove(mainFieldsPanel)
            add(polygonPanel, BorderLayout.CENTER)
        } else {
            remove(polygonPanel)
            add(mainFieldsPanel, BorderLayout.CENTER)
        }
        
        revalidate()
        repaint()
        isUpdatingFields = false
    }

    fun clearFields() {
        isUpdatingFields = true
        dimensionTableModel.setRowCount(0)
        polygonTableModel.setRowCount(0)
        isUpdatingFields = false
    }

    fun applyManualChanges(source: String) {
        val el = app.selectedElement ?: return
        
        var newVal: Int? = null
        var doubleVal: Double? = null
        
        for (i in 0 until dimensionTableModel.rowCount) {
            if (dimensionTableModel.getValueAt(i, 0).toString().replace(":", "").trim() == source) {
                newVal = dimensionTableModel.getValueAt(i, 1).toString().toIntOrNull()
                doubleVal = dimensionTableModel.getValueAt(i, 1).toString().toDoubleOrNull()
                break
            }
        }

        if (newVal == null && doubleVal == null) return

        app.saveState()
        when (source) {
            "X" -> el.x = newVal!!
            "Y" -> el.y = newVal!!
            "Width" -> el.width = newVal!!
            "Height" -> el.height = newVal!!
            "Height 3D" -> {
                if (el is PlanWindow) el.height3D = doubleVal!!.toInt()
                if (el is Door) el.height3D = doubleVal!!.toInt()
            }
            "Above floor height" -> {
                if (el is PlanWindow) el.aboveFloorHeight = doubleVal!!.toInt()
            }
        }
        
        if (el is FloorOpening) el.updateBounds()
        
        app.canvas.repaint()
        app.elementStatsPanel.updateElementStats(el)
        app.statsPanel.update()
    }
}
