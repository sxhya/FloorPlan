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
        override fun isCellEditable(row: Int, column: Int): Boolean {
            if (column != 1) return false
            val prop = getValueAt(row, 0)?.toString() ?: return false
            return when (prop) {
                "Type", "Element Area", "Opening Area", "Opening Volume", "" -> false
                else -> true
            }
        }
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
                val doc = app.activeDocument ?: return@addActionListener
                val es = doc.selectedElement as? FloorOpening ?: return@addActionListener
                doc.saveState()
                val v = es.vertices[row]
                es.vertices.add(row + 1, Point(v.x + 10, v.y + 10))
                es.updateBounds()
                updateFields(es)
                doc.canvas.repaint()
                app.statsPanel.update()
            }
        }
        polygonPopup.add(duplicateMarkerItem)

        val removeMarkerItem = JMenuItem("Remove Marker")
        removeMarkerItem.addActionListener {
            val row = polygonTable.selectedRow
            if (row != -1) {
                val doc = app.activeDocument ?: return@addActionListener
                val es = doc.selectedElement as? FloorOpening ?: return@addActionListener
                if (es.vertices.size > 3) {
                    doc.saveState()
                    es.vertices.removeAt(row)
                    es.updateBounds()
                    updateFields(es)
                    doc.canvas.repaint()
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
                val doc = app.activeDocument ?: return@addTableModelListener
                val es = doc.selectedElement as? FloorOpening ?: return@addTableModelListener
                val row = e.firstRow
                val col = e.column
                val value = polygonTableModel.getValueAt(row, col).toString().toIntOrNull()
                if (value != null) {
                    val currentVal = if (col == 1) es.vertices[row].x else if (col == 2) es.vertices[row].y else null
                    if (currentVal != value) {
                        doc.saveState()
                        if (col == 1) es.vertices[row].x = value
                        if (col == 2) es.vertices[row].y = value
                        es.updateBounds()
                        doc.canvas.repaint()
                    }
                }
            }
        }

        add(mainFieldsPanel, BorderLayout.CENTER)
    }

    fun addElement(type: ElementType) {
        val doc = app.activeDocument ?: return
        val centerX = doc.screenToModel(doc.canvas.width / 2, doc.offsetX).roundToInt()
        val centerY = doc.screenToModel(doc.canvas.height / 2, doc.offsetY).roundToInt()
        
        val el = when(type) {
            ElementType.WALL -> Wall(centerX - 50, centerY - 10, 100, 20)
            ElementType.ROOM -> Room(centerX - 50, centerY - 50, 100, 100)
            ElementType.STAIRS -> {
                val room = doc.selectedElement as? Room
                if (room != null) {
                    Stairs(room.x + 10, room.y + 10, 80, 40)
                } else {
                    Stairs(centerX - 40, centerY - 20, 80, 40)
                }
            }
            ElementType.FLOOR_OPENING -> {
                // This can now be triggered from empty space popup
                doc.currentMode = AppMode.RULER
                doc.canvas.isCreatingFloorOpening = true
                doc.canvas.rulerMarkers.clear()
                doc.canvas.rulerClosed = false
                doc.canvas.rulerProbeEnabled = true
                doc.canvas.repaint()
                return
            }
            else -> null
        }
        
        el?.let {
            doc.saveState()
            doc.elements.add(it)
            updateFields(it)
            app.statsPanel.update()
            doc.canvas.repaint()
        }
    }

    fun updateFieldsForActiveDocument() {
        val el = app.activeDocument?.selectedElement
        if (el != null) {
            updateFields(el)
        } else {
            clearFields()
        }
    }

    fun updateFields(el: PlanElement) {
        isUpdatingFields = true
        dimensionTableModel.setRowCount(0)
        dimensionTableModel.addRow(arrayOf("Type", el.javaClass.simpleName))
        dimensionTableModel.addRow(arrayOf("X", el.x))
        dimensionTableModel.addRow(arrayOf("Y", el.y))
        dimensionTableModel.addRow(arrayOf("Plan width", el.width))
        dimensionTableModel.addRow(arrayOf("Plan height", el.height))
        if (el is PlanWindow || el is Door) {
            val h3d = if (el is PlanWindow) el.height3D else (el as Door).verticalHeight
            dimensionTableModel.addRow(arrayOf("Vertical height", h3d))
            if (el is PlanWindow) {
                dimensionTableModel.addRow(arrayOf("Sill elevation", el.sillElevation))
            }
        }

        // Add blank row
        dimensionTableModel.addRow(arrayOf("", ""))

        // Add statistics
        dimensionTableModel.addRow(arrayOf("Element Area", "%.2f m²".format(el.getArea() / 10000.0)))
        if (el is PlanWindow || el is Door) {
            val h3d = if (el is PlanWindow) el.height3D else (el as Door).verticalHeight
            val doc = app.activeDocument
            val wall = doc?.findContainingWall(el.x, el.y, el.width, el.height)
            val effectiveWidth = if (wall != null) {
                val isVertical = wall.width < wall.height
                if (isVertical) el.height else el.width
            } else {
                maxOf(el.width, el.height)
            }

            val opArea = effectiveWidth.toDouble() * h3d
            val opVol = el.getArea() * h3d

            dimensionTableModel.addRow(arrayOf("Opening Area", "%.2f m²".format(opArea / 10000.0)))
            dimensionTableModel.addRow(arrayOf("Opening Volume", "%.2f m³".format(opVol / 1000000.0)))
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
        val doc = app.activeDocument ?: return
        val el = doc.selectedElement ?: return
        
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

        val isChanged = when (source) {
            "X" -> el.x != newVal
            "Y" -> el.y != newVal
            "Plan width" -> el.width != newVal
            "Plan height" -> el.height != newVal
            "Vertical height" -> {
                val current = if (el is PlanWindow) el.height3D else if (el is Door) el.verticalHeight else null
                current != doubleVal?.toInt()
            }
            "Sill elevation" -> {
                if (el is PlanWindow) el.sillElevation != doubleVal?.toInt() else false
            }
            else -> false
        }

        if (!isChanged) return

        doc.saveState()
        when (source) {
            "X" -> el.x = newVal!!
            "Y" -> el.y = newVal!!
            "Plan width" -> el.width = newVal!!
            "Plan height" -> el.height = newVal!!
            "Vertical height" -> {
                if (el is PlanWindow) el.height3D = doubleVal!!.toInt()
                if (el is Door) el.verticalHeight = doubleVal!!.toInt()
            }
            "Sill elevation" -> {
                if (el is PlanWindow) el.sillElevation = doubleVal!!.toInt()
            }
        }
        
        if (el is FloorOpening) el.updateBounds()
        
        doc.canvas.repaint()
        app.statsPanel.update()
    }
}
