package ui.components

import ui.FloorPlanApp
import ui.ThreeDDocument
import java.awt.*
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class ThreeDSidePanel(private val app: FloorPlanApp) : JPanel() {
    private val tableModel = object : DefaultTableModel(arrayOf("Floor Plan", "Height (cm)", "Draw"), 0) {
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
    
    private val table = JTable(tableModel)
    private var isUpdating = false
    private var currentDoc: ThreeDDocument? = null
    
    init {
        layout = BorderLayout()
        border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        preferredSize = Dimension(300, 300)
        
        add(JLabel("3D Model Floors"), BorderLayout.NORTH)
        
        // Setup table
        table.rowHeight = 25
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        
        // Setup combo box cell editor for floor plan column
        setupFloorPlanComboBox()
        
        // Setup checkbox renderer/editor for draw column
        table.columnModel.getColumn(2).cellRenderer = table.getDefaultRenderer(Boolean::class.javaObjectType)
        table.columnModel.getColumn(2).cellEditor = table.getDefaultEditor(Boolean::class.javaObjectType)
        
        // Listen for table changes
        tableModel.addTableModelListener { e ->
            if (!isUpdating && e.type == javax.swing.event.TableModelEvent.UPDATE) {
                applyChanges(e.firstRow, e.column)
            }
        }
        
        add(JScrollPane(table), BorderLayout.CENTER)
    }
    
    private fun setupFloorPlanComboBox() {
        val comboBox = JComboBox<String>()
        
        // Custom cell editor that updates combo box items when editing starts
        val editor = object : DefaultCellEditor(comboBox) {
            override fun getTableCellEditorComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                row: Int,
                column: Int
            ): Component {
                // Update combo box with current open documents
                comboBox.removeAllItems()
                app.getOpenDocuments().forEach { doc ->
                    val name = doc.currentFile?.absolutePath ?: "Untitled"
                    comboBox.addItem(name)
                }
                // Also add the current value if it's not in the list (file might not be open)
                val currentValue = value?.toString() ?: ""
                if (currentValue.isNotEmpty() && (0 until comboBox.itemCount).none { comboBox.getItemAt(it) == currentValue }) {
                    comboBox.addItem(currentValue)
                }
                comboBox.selectedItem = value
                return super.getTableCellEditorComponent(table, value, isSelected, row, column)
            }
        }
        
        table.columnModel.getColumn(0).cellEditor = editor
        
        // Set column widths
        table.columnModel.getColumn(0).preferredWidth = 150
        table.columnModel.getColumn(1).preferredWidth = 80
        table.columnModel.getColumn(2).preferredWidth = 50
    }
    
    fun updateFields(doc: ThreeDDocument) {
        isUpdating = true
        currentDoc = doc
        tableModel.setRowCount(0)
        
        for (floor in doc.floors) {
            tableModel.addRow(arrayOf(floor.filePath, floor.height, floor.draw))
        }
        
        isUpdating = false
    }
    
    fun clearFields() {
        isUpdating = true
        currentDoc = null
        tableModel.setRowCount(0)
        isUpdating = false
    }
    
    private fun applyChanges(row: Int, column: Int) {
        val doc = currentDoc ?: return
        if (row < 0 || row >= doc.floors.size) return
        
        val floor = doc.floors[row]
        
        when (column) {
            0 -> {
                // Floor plan path changed
                val newPath = tableModel.getValueAt(row, 0)?.toString() ?: return
                if (newPath != floor.filePath) {
                    doc.floors[row] = ThreeDDocument.FloorData(newPath, floor.height, floor.draw)
                    doc.isModified = true
                    regenerate3DModel(doc)
                }
            }
            1 -> {
                // Height changed
                val newHeight = (tableModel.getValueAt(row, 1) as? Int) ?: return
                if (newHeight != floor.height) {
                    floor.height = newHeight
                    doc.isModified = true
                    regenerate3DModel(doc)
                }
            }
            2 -> {
                // Draw checkbox changed
                val newDraw = (tableModel.getValueAt(row, 2) as? Boolean) ?: return
                if (newDraw != floor.draw) {
                    floor.draw = newDraw
                    doc.isModified = true
                    // Regenerate model to apply transparency change
                    regenerate3DModel(doc)
                }
            }
        }
        
        doc.window?.title = "3D House Model - ${doc.currentFile?.name ?: "Untitled"}*"
    }
    
    private fun regenerate3DModel(doc: ThreeDDocument) {
        // Regenerate the 3D model from the floor data
        app.regenerate3DModelFromFloors(doc)
    }
}
