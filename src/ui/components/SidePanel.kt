package ui.components

import model.*
import model.Window as PlanWindow
import ui.FloorPlanApp
import ui.ThreeDDocument
import java.awt.*
import java.util.EventObject
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import kotlin.math.roundToInt

class SidePanel(private val app: FloorPlanApp) : JPanel() {
    private inner class PropertyTable(model: DefaultTableModel) : JTable(model) {
        init {
            putClientProperty("terminateEditOnFocusLost", true)
        }

        override fun tableChanged(e: javax.swing.event.TableModelEvent?) {
            if (isEditing) {
                cellEditor?.stopCellEditing()
            }
            super.tableChanged(e)
        }
    }

    private val mainFieldsPanel = JPanel(BorderLayout())
    private val dimensionTableModel = object : DefaultTableModel(arrayOf("Property", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean {
            if (column != 1) return false
            val prop = getValueAt(row, 0)?.toString() ?: return false
            val el = app.activeDocument?.selectedElement
            return when (prop) {
                "Type", "Element Area", "Opening Area", "Opening Volume", "", "Points count" -> false
                "Kind" -> if (el is UtilitiesConnection) false else true
                else -> true
            }
        }
    }
    private val dimensionTable = PropertyTable(dimensionTableModel)

    private val polygonPanel = JPanel(BorderLayout())
    private val polygonTableModel = DefaultTableModel(arrayOf("Marker No", "X", "Y"), 0)
    private val polygonTable = PropertyTable(polygonTableModel)

    private val threeDSidePanel = JPanel(BorderLayout())
    private val threeDTableModel = object : DefaultTableModel(arrayOf("Floor Plan", "Height (cm)", "Draw"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = true
        
        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                0 -> String::class.java
                1 -> Int::class.javaObjectType
                2 -> Boolean::class.javaObjectType
                else -> Any::class.java
            }
        }
    }
    private val threeDTable = PropertyTable(threeDTableModel)
    private var currentThreeDDoc: ThreeDDocument? = null

    private var isUpdatingFields = false
    private var activeWallLayoutDoc: ui.WallLayoutDocument? = null

    init {
        layout = BorderLayout()
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        preferredSize = Dimension(300, 700)

        mainFieldsPanel.add(JLabel("Dimension"), BorderLayout.NORTH)
        val dimScrollPane = JScrollPane(dimensionTable)
        // Stop editing when clicking in the viewport area below all table rows
        dimScrollPane.viewport.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (dimensionTable.isEditing) dimensionTable.cellEditor?.stopCellEditing()
                dimensionTable.clearSelection()
            }
        })
        mainFieldsPanel.add(dimScrollPane, BorderLayout.CENTER)

        polygonPanel.add(JLabel("Polygon Vertices"), BorderLayout.NORTH)
        polygonPanel.add(JScrollPane(polygonTable), BorderLayout.CENTER)

        // Setup 3D panel
        threeDSidePanel.add(JLabel("3D Model Floors"), BorderLayout.NORTH)
        threeDTable.rowHeight = 25
        threeDTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        setupFloorPlanComboBox()
        threeDTable.columnModel.getColumn(2).cellRenderer = threeDTable.getDefaultRenderer(Boolean::class.javaObjectType)
        threeDTable.columnModel.getColumn(2).cellEditor = threeDTable.getDefaultEditor(Boolean::class.javaObjectType)
        
        threeDTableModel.addTableModelListener { e ->
            if (!isUpdatingFields && e.type == javax.swing.event.TableModelEvent.UPDATE) {
                applyThreeDChanges(e.firstRow, e.column)
            }
        }
        threeDSidePanel.add(JScrollPane(threeDTable), BorderLayout.CENTER)

        val polygonPopup = JPopupMenu()
        val duplicateMarkerItem = JMenuItem("Duplicate Marker")
        duplicateMarkerItem.addActionListener {
            val row = polygonTable.selectedRow
            if (row != -1) {
                val doc = app.activeDocument ?: return@addActionListener
                val es = doc.selectedElement as? PolygonRoom ?: return@addActionListener
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
                val es = doc.selectedElement as? PolygonRoom ?: return@addActionListener
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
        
        setupDimensionTableEditors()

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
                val es = doc.selectedElement as? PolygonRoom ?: return@addTableModelListener
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

        // Stop editing when clicking the panel's padding area (outside all child components)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (dimensionTable.isEditing) dimensionTable.cellEditor?.stopCellEditing()
                dimensionTable.clearSelection()
            }
        })
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
            ElementType.POLYGON_ROOM -> {
                // This can now be triggered from empty space popup
                doc.currentMode = AppMode.RULER
                doc.canvas.isCreatingPolygonRoom = true
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
        val activeDoc = app.activeDocument
        if (activeDoc != null) {
            val el = activeDoc.selectedElement
            if (el != null) {
                updateFields(el)
            } else {
                clearFields()
            }
        } else {
            val wallLayoutWindow = app.activeWindow as? ui.WallLayoutWindow
            if (wallLayoutWindow != null) {
                updateWallLayoutFields(wallLayoutWindow.doc)
            } else {
                clearFields()
            }
        }
    }

    private fun updateWallLayoutFields(doc: ui.WallLayoutDocument) {
        clearFields()
        activeWallLayoutDoc = doc
        // Disable focus-loss auto-commit while wall layout spinners are shown.
        // Without this, clicking the canvas causes focus to leave the spinner, which
        // auto-commits the value and shifts the point's model position before mousePressed
        // runs its hit test, making the point "impossible to grab".
        dimensionTable.putClientProperty("terminateEditOnFocusLost", false)
        isUpdatingFields = true
        val p = doc.selectedPoint
        if (p != null) {
            dimensionTableModel.addRow(arrayOf("Type", "WallLayoutPoint"))
            dimensionTableModel.addRow(arrayOf("Name", p.name))
            dimensionTableModel.addRow(arrayOf("X", p.x))
            dimensionTableModel.addRow(arrayOf("Z", p.z))
            val kindName = doc.floorPlanDoc.kinds.getOrNull(p.kind)?.name ?: "Kind ${p.kind}"
            dimensionTableModel.addRow(arrayOf("Kind", kindName))
        } else {
            dimensionTableModel.addRow(arrayOf("Type", "WallLayout"))
            dimensionTableModel.addRow(arrayOf("Points count", doc.layout.points.size))
        }
        add(mainFieldsPanel, BorderLayout.CENTER)
        revalidate()
        repaint()
        isUpdatingFields = false
    }

    fun updateFields(el: PlanElement) {
        clearFields()
        isUpdatingFields = true
        dimensionTableModel.addRow(arrayOf("Type", el.javaClass.simpleName))
        if (el !is UtilitiesConnection) {
            dimensionTableModel.addRow(arrayOf("X", el.x))
            dimensionTableModel.addRow(arrayOf("Y", el.y))
            dimensionTableModel.addRow(arrayOf("Plan width", el.width))
            dimensionTableModel.addRow(arrayOf("Plan height", el.height))
        }
        if (el is PlanWindow || el is Door) {
            val h3d = if (el is PlanWindow) el.height3D else (el as Door).verticalHeight
            dimensionTableModel.addRow(arrayOf("Vertical height", h3d))
            if (el is PlanWindow) {
                dimensionTableModel.addRow(arrayOf("Sill elevation", el.sillElevation))
                dimensionTableModel.addRow(arrayOf("Window Position", el.windowPosition))
            }
        }
        if (el is Room) {
            dimensionTableModel.addRow(arrayOf("Floor thickness", el.floorThickness))
            dimensionTableModel.addRow(arrayOf("Z-offset", el.zOffset))
        }
        if (el is PolygonRoom) {
            dimensionTableModel.addRow(arrayOf("Floor thickness", el.floorThickness))
            dimensionTableModel.addRow(arrayOf("Z-offset", el.zOffset))
        }
        if (el is Stairs) {
            dimensionTableModel.addRow(arrayOf("Direction along X", el.directionAlongX))
            dimensionTableModel.addRow(arrayOf("Total raise", el.totalRaise))
            dimensionTableModel.addRow(arrayOf("Z-offset", el.zOffset))
        }
        if (el is UtilitiesConnection) {
            val doc = app.activeDocument ?: return
            dimensionTableModel.addRow(arrayOf("Kind", doc.kinds.getOrNull(el.kind)?.name ?: "Kind ${el.kind}"))
            dimensionTableModel.addRow(arrayOf("Start Point", formatPoint(el.startPoint, el.startWall, el.startIsFront)))
            dimensionTableModel.addRow(arrayOf("End Point", formatPoint(el.endPoint, el.endWall, el.endIsFront)))
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

        if (el is PolygonRoom) {
            polygonTableModel.setRowCount(0)
            el.vertices.forEachIndexed { index, point ->
                polygonTableModel.addRow(arrayOf(index + 1, point.x, point.y))
            }
            add(polygonPanel, BorderLayout.CENTER)
        } else {
            add(mainFieldsPanel, BorderLayout.CENTER)
        }
        
        revalidate()
        repaint()
        isUpdatingFields = false
    }

    private fun setupDimensionTableEditors() {
        val windowPositionComboBox = JComboBox(WindowPosition.values())
        windowPositionComboBox.addActionListener {
            if (dimensionTable.isEditing) {
                val row = dimensionTable.editingRow
                if (row != -1 && dimensionTable.getValueAt(row, 0)?.toString()?.replace(":", "")?.trim() == "Window Position") {
                    dimensionTable.setValueAt(windowPositionComboBox.selectedItem, row, 1)
                }
                dimensionTable.cellEditor?.stopCellEditing()
            }
        }
        val windowPositionEditor = DefaultCellEditor(windowPositionComboBox)

        val directionComboBox = JComboBox(arrayOf(true, false))
        directionComboBox.addActionListener {
            if (dimensionTable.isEditing) {
                val row = dimensionTable.editingRow
                if (row != -1 && dimensionTable.getValueAt(row, 0)?.toString()?.replace(":", "")?.trim() == "Direction along X") {
                    dimensionTable.setValueAt(directionComboBox.selectedItem, row, 1)
                }
                dimensionTable.cellEditor?.stopCellEditing()
            }
        }
        val directionRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                val text = if (value == true) "Along X" else if (value == false) "Along Y" else value?.toString() ?: ""
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
        directionComboBox.renderer = directionRenderer
        val directionEditor = DefaultCellEditor(directionComboBox)

        val kindComboBox = JComboBox<String>()
        kindComboBox.addActionListener {
            if (dimensionTable.isEditing) {
                val row = dimensionTable.editingRow
                if (row != -1 && dimensionTable.getValueAt(row, 0)?.toString()?.replace(":", "")?.trim() == "Kind") {
                    dimensionTable.setValueAt(kindComboBox.selectedItem, row, 1)
                }
                dimensionTable.cellEditor?.stopCellEditing()
            }
        }
        val kindEditor = object : DefaultCellEditor(kindComboBox) {
            override fun getTableCellEditorComponent(table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
                kindComboBox.removeAllItems()
                val wallLayoutWindow = app.activeWindow as? ui.WallLayoutWindow
                val doc = wallLayoutWindow?.doc?.floorPlanDoc ?: app.activeDocument
                doc?.kinds?.forEach {
                    kindComboBox.addItem(it.name)
                }
                return super.getTableCellEditorComponent(table, value, isSelected, row, column)
            }
        }

        val pointComboBox = JComboBox<PointInfo>()
        pointComboBox.addActionListener {
            if (dimensionTable.isEditing) {
                val row = dimensionTable.editingRow
                val propName = dimensionTable.getValueAt(row, 0)?.toString()?.replace(":", "")?.trim()
                if (row != -1 && (propName == "Start Point" || propName == "End Point")) {
                    dimensionTable.setValueAt(pointComboBox.selectedItem, row, 1)
                }
                dimensionTable.cellEditor?.stopCellEditing()
            }
        }
        val pointEditor = object : DefaultCellEditor(pointComboBox) {
            override fun getTableCellEditorComponent(table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
                pointComboBox.removeAllItems()
                val doc = app.activeDocument
                val el = doc?.selectedElement as? UtilitiesConnection
                if (doc != null && el != null) {
                    doc.elements.filterIsInstance<Wall>().forEach { wall ->
                        wall.frontLayout.points.filter { it.kind == el.kind }.forEach { p ->
                            pointComboBox.addItem(PointInfo(p, wall, true))
                        }
                        wall.backLayout.points.filter { it.kind == el.kind }.forEach { p ->
                            pointComboBox.addItem(PointInfo(p, wall, false))
                        }
                    }
                }
                return super.getTableCellEditorComponent(table, value, isSelected, row, column)
            }
        }

        // Custom text field editor: Enter commits directly via the outer (registered) editor,
        // bypassing DefaultCellEditor's inner editingStopped which the JTable never sees.
        val defaultTextField = JTextField()
        val defaultEditor = object : AbstractCellEditor(), TableCellEditor {
            override fun getTableCellEditorComponent(table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
                defaultTextField.text = value?.toString() ?: ""
                return defaultTextField
            }
            override fun getCellEditorValue(): Any? = defaultTextField.text
        }

        // Spinner for X (Double) — only active when editing a WallLayoutPoint
        val xSpinner = JSpinner(SpinnerNumberModel(0.0, -99999.0, 99999.0, 1.0))
        val xSpinnerEditor = object : AbstractCellEditor(), TableCellEditor {
            override fun getTableCellEditorComponent(table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
                xSpinner.value = value?.toString()?.toDoubleOrNull() ?: 0.0
                return xSpinner
            }
            override fun getCellEditorValue(): Any? {
                try { xSpinner.commitEdit() } catch (e: Exception) { /* keep current value */ }
                return xSpinner.value
            }
        }
        (xSpinner.editor as JSpinner.DefaultEditor).textField.addActionListener {
            try { xSpinner.commitEdit() } catch (e: Exception) { /* ignore */ }
            if (dimensionTable.isEditing) dimensionTable.cellEditor?.stopCellEditing()
            dimensionTable.clearSelection()
        }

        // Spinner for Z (Int) — only active when editing a WallLayoutPoint
        val zSpinner = JSpinner(SpinnerNumberModel(0, 0, 9999, 1))
        val zSpinnerEditor = object : AbstractCellEditor(), TableCellEditor {
            override fun getTableCellEditorComponent(table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
                zSpinner.value = value?.toString()?.toIntOrNull() ?: 0
                return zSpinner
            }
            override fun getCellEditorValue(): Any? {
                try { zSpinner.commitEdit() } catch (e: Exception) { /* keep current value */ }
                return zSpinner.value
            }
        }
        (zSpinner.editor as JSpinner.DefaultEditor).textField.addActionListener {
            try { zSpinner.commitEdit() } catch (e: Exception) { /* ignore */ }
            if (dimensionTable.isEditing) dimensionTable.cellEditor?.stopCellEditing()
            dimensionTable.clearSelection()
        }

        // Ensure the table rows are tall enough to fully paint the spinner up/down buttons.
        // The default row height (16-18px) clips them; use the spinner's own preferred height.
        dimensionTable.rowHeight = maxOf(dimensionTable.rowHeight, xSpinner.preferredSize.height)

        // Immediately repaint the table when a spinner editor gains focus so the
        // selected-row highlight updates without waiting for the next Swing repaint pass.
        val spinnerFocusRepaint = object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) = dimensionTable.repaint()
        }
        (xSpinner.editor as JSpinner.DefaultEditor).textField.addFocusListener(spinnerFocusRepaint)
        (zSpinner.editor as JSpinner.DefaultEditor).textField.addFocusListener(spinnerFocusRepaint)

        // Cap the "Property" column width so the "Value" column gets enough room for comboboxes.
        dimensionTable.columnModel.getColumn(0).maxWidth = 110
        dimensionTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        // Stop editing and clear selection when clicking on a non-editable area of the table:
        // the Property column (col 0) or empty space below all rows (row == -1).
        dimensionTable.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                val row = dimensionTable.rowAtPoint(e.point)
                val col = dimensionTable.columnAtPoint(e.point)
                if (row == -1 || col == 0) {
                    if (dimensionTable.isEditing) dimensionTable.cellEditor?.stopCellEditing()
                    dimensionTable.clearSelection()
                }
            }
        })

        // The outer wrapper editor is what the JTable registers its CellEditorListener on.
        // CRITICAL: stopCellEditing() MUST call fireEditingStopped() so the JTable knows
        // to call getCellEditorValue() and commit the value to the model.
        val outerEditor = object : AbstractCellEditor(), TableCellEditor {
            private var currentEditor: TableCellEditor? = null

            override fun getTableCellEditorComponent(table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
                val propName = table?.getValueAt(row, 0)?.toString()?.replace(":", "")?.trim()
                currentEditor = when (propName) {
                    "Window Position" -> windowPositionEditor
                    "Direction along X" -> directionEditor
                    "Kind" -> kindEditor
                    "Start Point", "End Point" -> pointEditor
                    "X" -> if (activeWallLayoutDoc != null) xSpinnerEditor else defaultEditor
                    "Z" -> if (activeWallLayoutDoc != null) zSpinnerEditor else defaultEditor
                    else -> defaultEditor
                }
                return currentEditor!!.getTableCellEditorComponent(table, value, isSelected, row, column)
            }

            override fun getCellEditorValue(): Any? = currentEditor?.cellEditorValue

            override fun isCellEditable(e: EventObject?) = true

            override fun shouldSelectCell(anEvent: EventObject?) = true

            override fun stopCellEditing(): Boolean {
                val result = currentEditor?.stopCellEditing() ?: true
                if (result) fireEditingStopped()   // notify JTable so it calls setValueAt()
                return result
            }

            override fun cancelCellEditing() {
                currentEditor?.cancelCellEditing()
                fireEditingCanceled()
            }
        }

        // Wire Enter key on the text field to commit via the outer registered editor
        defaultTextField.addActionListener {
            if (dimensionTable.isEditing) dimensionTable.cellEditor?.stopCellEditing()
            dimensionTable.clearSelection()
        }

        dimensionTable.columnModel.getColumn(1).cellEditor = outerEditor

        dimensionTable.columnModel.getColumn(1).cellRenderer = object : TableCellRenderer {
            private val defaultRenderer = dimensionTable.getDefaultRenderer(Any::class.java)
            override fun getTableCellRendererComponent(table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                val propName = table?.getValueAt(row, 0)?.toString()?.replace(":", "")?.trim()

                // Render Direction/Position as comboboxes so the display text is correct
                // (true/false → "Along X"/"Along Y", enum → name).
                // "Kind" is NOT rendered via the shared kindComboBox: using a shared comboBox
                // instance as both editor and renderer causes the internal layout to become
                // stale after the first edit, pushing the dropdown button off-centre.
                // Plain-text rendering via the default renderer is correct and simpler.
                if (propName == "Direction along X" || propName == "Window Position") {
                    val comboBox = if (propName == "Direction along X") directionComboBox else windowPositionComboBox
                    comboBox.selectedItem = value
                    comboBox.background = if (isSelected) table?.selectionBackground else table?.background
                    comboBox.foreground = if (isSelected) table?.selectionForeground else table?.foreground
                    comboBox.font = table?.font
                    return comboBox
                }

                return defaultRenderer.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            }
        }
    }

    private fun setupFloorPlanComboBox() {
        val comboBox = JComboBox<String>()
        comboBox.addActionListener {
            if (threeDTable.isEditing) {
                threeDTable.cellEditor?.stopCellEditing()
            }
        }
        val editor = object : DefaultCellEditor(comboBox) {
            override fun getTableCellEditorComponent(
                table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
            ): Component {
                comboBox.removeAllItems()
                app.getOpenDocuments().forEach { doc ->
                    val name = doc.currentFile?.absolutePath ?: "Untitled"
                    comboBox.addItem(name)
                }
                val currentValue = value?.toString() ?: ""
                if (currentValue.isNotEmpty() && (0 until comboBox.itemCount).none { comboBox.getItemAt(it) == currentValue }) {
                    comboBox.addItem(currentValue)
                }
                comboBox.selectedItem = value
                return super.getTableCellEditorComponent(table, value, isSelected, row, column)
            }

            override fun stopCellEditing(): Boolean {
                val row = threeDTable.editingRow
                if (row != -1) {
                    threeDTable.setValueAt(comboBox.selectedItem, row, 0)
                }
                return super.stopCellEditing()
            }
        }
        threeDTable.columnModel.getColumn(0).cellEditor = editor
        threeDTable.columnModel.getColumn(0).preferredWidth = 150
        threeDTable.columnModel.getColumn(1).preferredWidth = 80
        threeDTable.columnModel.getColumn(2).preferredWidth = 50
    }

    fun updateThreeDFields(doc: ThreeDDocument) {
        clearFields()
        isUpdatingFields = true
        currentThreeDDoc = doc
        for (floor in doc.floors) {
            threeDTableModel.addRow(arrayOf(floor.filePath, floor.height, floor.draw))
        }
        add(threeDSidePanel, BorderLayout.CENTER)
        revalidate()
        repaint()
        isUpdatingFields = false
    }

    private fun applyThreeDChanges(row: Int, column: Int) {
        val doc = currentThreeDDoc ?: return
        if (row < 0 || row >= doc.floors.size) return
        val floor = doc.floors[row]
        when (column) {
            0 -> {
                val newPath = threeDTableModel.getValueAt(row, 0)?.toString() ?: return
                if (newPath != floor.filePath) {
                    doc.floors[row] = ThreeDDocument.FloorData(newPath, floor.height, floor.draw)
                    doc.isModified = true
                    app.regenerate3DModelFromFloors(doc)
                }
            }
            1 -> {
                val newHeight = (threeDTableModel.getValueAt(row, 1) as? Int) ?: return
                if (newHeight != floor.height) {
                    floor.height = newHeight
                    doc.isModified = true
                    app.regenerate3DModelFromFloors(doc)
                }
            }
            2 -> {
                val newDraw = (threeDTableModel.getValueAt(row, 2) as? Boolean) ?: return
                if (newDraw != floor.draw) {
                    floor.draw = newDraw
                    doc.isModified = true
                    app.regenerate3DModelFromFloors(doc)
                }
            }
        }
        doc.window?.title = "3D House Model - ${doc.currentFile?.name ?: "Untitled"}*"
    }

    fun clearFields() {
        // Commit any pending cell edit before suppressing update events, so changes aren't lost
        if (!isUpdatingFields && dimensionTable.isEditing) {
            dimensionTable.cellEditor?.stopCellEditing()
        }
        isUpdatingFields = true
        activeWallLayoutDoc = null
        // Restore auto-commit on focus-loss for non-wall-layout contexts
        dimensionTable.putClientProperty("terminateEditOnFocusLost", true)
        if (dimensionTable.isEditing) {
            dimensionTable.cellEditor?.cancelCellEditing()
            dimensionTable.removeEditor()
        }
        if (polygonTable.isEditing) {
            polygonTable.cellEditor?.stopCellEditing()
            polygonTable.removeEditor()
        }
        if (threeDTable.isEditing) {
            threeDTable.cellEditor?.stopCellEditing()
            threeDTable.removeEditor()
        }
        dimensionTableModel.setRowCount(0)
        polygonTableModel.setRowCount(0)
        threeDTableModel.setRowCount(0)
        currentThreeDDoc = null
        
        remove(mainFieldsPanel)
        remove(polygonPanel)
        remove(threeDSidePanel)
        
        revalidate()
        repaint()
        isUpdatingFields = false
    }

    /** Cancel any active cell edit without committing, so the model stays unchanged.
     *  Called by the wall layout canvas at the start of mousePressed so that a pending
     *  spinner edit doesn't shift the point's position before the hit test runs. */
    fun cancelCurrentEdit() {
        if (dimensionTable.isEditing) {
            dimensionTable.cellEditor?.cancelCellEditing()
        }
    }

    private fun getFloorPlanCoords(p: WallLayoutPoint, wall: Wall, isFront: Boolean): java.awt.geom.Point2D.Double {
        val isVertical = wall.width < wall.height
        return if (isVertical) {
            val fx = if (isFront) wall.x.toDouble() else (wall.x + wall.width).toDouble()
            java.awt.geom.Point2D.Double(fx, p.x)
        } else {
            val fy = if (isFront) wall.y.toDouble() else (wall.y + wall.height).toDouble()
            java.awt.geom.Point2D.Double(p.x, fy)
        }
    }

    private fun formatPoint(p: WallLayoutPoint, wall: Wall, isFront: Boolean): String {
        val coords = getFloorPlanCoords(p, wall, isFront)
        return "X:%.1f, Y:%.1f, Z:%d".format(coords.x, coords.y, p.z)
    }

    data class PointInfo(val point: WallLayoutPoint, val wall: Wall, val isFront: Boolean) {
        override fun toString(): String {
            val isVertical = wall.width < wall.height
            val fx = if (isVertical) {
                if (isFront) wall.x.toDouble() else (wall.x + wall.width).toDouble()
            } else {
                point.x
            }
            val fy = if (isVertical) {
                point.x
            } else {
                if (isFront) wall.y.toDouble() else (wall.y + wall.height).toDouble()
            }
            return "X:%.1f, Y:%.1f, Z:%d".format(fx, fy, point.z)
        }
    }

    fun applyManualChanges(source: String) {
        val activeDoc = app.activeDocument
        if (activeDoc != null) {
            applyFloorPlanManualChanges(activeDoc, source)
        } else {
            val wlDoc = activeWallLayoutDoc
            if (wlDoc != null) {
                applyWallLayoutManualChanges(wlDoc, source)
            }
        }
    }

    private fun applyWallLayoutManualChanges(doc: ui.WallLayoutDocument, source: String) {
        val p = doc.selectedPoint ?: return
        var newVal: Any? = null
        for (i in 0 until dimensionTableModel.rowCount) {
            val propName = dimensionTableModel.getValueAt(i, 0).toString().replace(":", "").trim()
            if (propName == source) {
                newVal = dimensionTableModel.getValueAt(i, 1) ?: return
                break
            }
        }
        if (newVal == null) return

        when (source) {
            "Name" -> {
                val v = newVal.toString()
                if (p.name != v) {
                    doc.saveState()
                    p.name = v
                    doc.window?.canvas?.repaint()
                }
            }
            "X" -> {
                val v = newVal.toString().toDoubleOrNull() ?: return
                if (p.x != v) {
                    doc.saveState()
                    p.x = v
                    doc.window?.canvas?.repaint()
                }
            }
            "Z" -> {
                val v = newVal.toString().toDoubleOrNull()?.roundToInt() ?: return
                if (p.z != v) {
                    doc.saveState()
                    p.z = v
                    doc.window?.canvas?.repaint()
                }
            }
            "Kind" -> {
                val kindIndex = doc.floorPlanDoc.kinds.indexOfFirst { it.name == newVal.toString() }
                if (kindIndex != -1 && p.kind != kindIndex) {
                    doc.saveState()
                    p.kind = kindIndex
                    doc.window?.canvas?.repaint()
                }
            }
        }
    }

    private fun applyFloorPlanManualChanges(doc: ui.FloorPlanDocument, source: String) {
        val el = doc.selectedElement ?: return
        
        var newVal: Int? = null
        var doubleVal: Double? = null
        var boolVal: Boolean? = null
        var posVal: WindowPosition? = null
        var pointVal: PointInfo? = null
        
        for (i in 0 until dimensionTableModel.rowCount) {
            val propName = dimensionTableModel.getValueAt(i, 0).toString().replace(":", "").trim()
            if (propName == source) {
                val value = dimensionTableModel.getValueAt(i, 1) ?: return
                
                if (value is PointInfo) {
                    pointVal = value
                } else if (value is WindowPosition) {
                    posVal = value
                } else if (value is Boolean) {
                    boolVal = value
                } else {
                    val valueStr = value.toString()
                    newVal = valueStr.toIntOrNull()
                    doubleVal = valueStr.toDoubleOrNull()
                    boolVal = boolVal ?: valueStr.toBooleanStrictOrNull()
                    posVal = posVal ?: try { WindowPosition.valueOf(valueStr) } catch (e: Exception) { null }
                }
                break
            }
        }

        if (newVal == null && doubleVal == null && boolVal == null && posVal == null && pointVal == null) return

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
            "Floor thickness" -> {
                if (el is Room) el.floorThickness != doubleVal?.toInt() 
                else if (el is PolygonRoom) el.floorThickness != doubleVal?.toInt()
                else false
            }
            "Z-offset" -> {
                if (el is Room) el.zOffset != doubleVal?.toInt()
                else if (el is PolygonRoom) el.zOffset != doubleVal?.toInt()
                else if (el is Stairs) el.zOffset != doubleVal?.toInt()
                else false
            }
            "Direction along X" -> {
                if (el is Stairs) el.directionAlongX != boolVal else false
            }
            "Total raise" -> {
                if (el is Stairs) el.totalRaise != doubleVal?.toInt() else false
            }
            "Window Position" -> {
                if (el is PlanWindow) el.windowPosition != posVal else false
            }
            "Start Point" -> {
                if (el is UtilitiesConnection) {
                    el.startPoint != pointVal?.point || el.startWall != pointVal?.wall || el.startIsFront != pointVal?.isFront
                } else false
            }
            "End Point" -> {
                if (el is UtilitiesConnection) {
                    el.endPoint != pointVal?.point || el.endWall != pointVal?.wall || el.endIsFront != pointVal?.isFront
                } else false
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
            "Floor thickness" -> {
                if (el is Room) el.floorThickness = doubleVal!!.toInt()
                if (el is PolygonRoom) el.floorThickness = doubleVal!!.toInt()
            }
            "Z-offset" -> {
                if (el is Room) el.zOffset = doubleVal!!.toInt()
                if (el is PolygonRoom) el.zOffset = doubleVal!!.toInt()
                if (el is Stairs) el.zOffset = doubleVal!!.toInt()
            }
            "Direction along X" -> {
                if (el is Stairs) el.directionAlongX = boolVal!!
            }
            "Total raise" -> {
                if (el is Stairs) el.totalRaise = doubleVal!!.toInt()
            }
            "Window Position" -> {
                if (el is PlanWindow) el.windowPosition = posVal!!
            }
            "Start Point" -> {
                if (el is UtilitiesConnection && pointVal != null) {
                    el.startPoint = pointVal.point
                    el.startWall = pointVal.wall
                    el.startIsFront = pointVal.isFront
                    el.updateBounds()
                }
            }
            "End Point" -> {
                if (el is UtilitiesConnection && pointVal != null) {
                    el.endPoint = pointVal.point
                    el.endWall = pointVal.wall
                    el.endIsFront = pointVal.isFront
                    el.updateBounds()
                }
            }
        }
        
        if (el is PolygonRoom) el.updateBounds()
        
        doc.canvas.repaint()
        app.statsPanel.update()
    }
}
