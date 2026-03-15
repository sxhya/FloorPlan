package ui

import model.*
import model.Vector3D
import model.Rect3D
import model.ElementType
import model.Wall
import model.Room
import model.Window as PlanWindow
import model.Door
import model.Stairs
import model.PolygonRoom
import javafx.application.Platform
import ui.components.SidePanel
import ui.components.StatisticsPanel
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import kotlin.math.roundToInt

class FloorPlanApp {
    private val documents = mutableListOf<FloorPlanDocument>()
    internal var activeDocument: FloorPlanDocument? = null
    internal var activeWindow: JFrame? = null
    
    internal val sidePanel = SidePanel(this)
    internal val statsPanel = StatisticsPanel(this)
    
    private var sidePanelWindow: SidePanelWindow? = null
    
    internal var isExporting = false
    
    private val SETTINGS_FILE = "floorplan_settings.properties"
    private var lastDirectory: String? = null
    private val recentFiles = mutableListOf<String>()
    private val openFiles = mutableListOf<String>()
    private val threeDDocuments = mutableListOf<ThreeDDocument>()
    private data class WindowState(val pos: Point, val size: Dimension, val scale: Double, val offsetX: Double, val offsetY: Double)
    private val openFileStates = mutableMapOf<String, WindowState>()
    private var sidePanelX: Int? = null
    private var sidePanelY: Int? = null
    private val MAX_RECENT = 10


    private val menuBars = mutableListOf<JMenuBar>()
    private val undoMenuItems = mutableListOf<JMenuItem>()
    private val redoMenuItems = mutableListOf<JMenuItem>()
    private val recentMenus = mutableListOf<JMenu>()

    internal val popupMenu = JPopupMenu()
    internal lateinit var popAddWallMenu: JMenuItem
    internal lateinit var popAddRoomMenu: JMenuItem
    internal lateinit var popSepGeneral: JSeparator
    internal lateinit var popDuplicateMenu: JMenuItem
    internal lateinit var popRemoveMenu: JMenuItem
    internal lateinit var popSepElements: JSeparator
    internal lateinit var popAddWindowMenu: JMenuItem
    internal lateinit var popAddDoorMenu: JMenuItem
    internal lateinit var popSepRoom: JSeparator
    internal lateinit var popAddStairsMenu: JMenuItem
    internal lateinit var popAddFloorOpeningMenu: JMenuItem
    internal lateinit var popAddUtilityMenu: JMenu
    internal lateinit var popConvertToPolygonMenu: JMenuItem
    internal lateinit var popEditFrontFaceMenu: JMenuItem
    internal lateinit var popEditBackFaceMenu: JMenuItem

    init {
        Platform.setImplicitExit(false)
        loadSettings()
        setupPopupMenu()
        sidePanelWindow = SidePanelWindow(this)
        sidePanelWindow?.let { window ->
            if (sidePanelX != null && sidePanelY != null) {
                window.setLocation(sidePanelX!!, sidePanelY!!)
            }
            window.jMenuBar = createMenuBar()
        }
        
        // Restore previously open documents
        val filesToOpen = openFiles.toList()
        openFiles.clear() // Will be re-added as they are opened
        for (filePath in filesToOpen) {
            val file = File(filePath)
            if (file.exists()) {
                val state = openFileStates[filePath]
                openFile(file, state)
            }
        }
        
        // If no documents were restored, don't create any dummy scene
    }

    fun createNew() {
        val doc = FloorPlanDocument(this)
        documents.add(doc)
        val window = EditorWindow(this, doc)
        window.jMenuBar = createMenuBar()
        activeDocument = doc
        activeWindow = window
        
        doc.saveState()
        doc.isModified = false // Initial state after createNew with saveState
        window.updateTitle()
        
        updateUndoRedoStates()
        updateRecentMenu()
    }

    fun closeDocument(doc: FloorPlanDocument, saveImmediately: Boolean = true) {
        // If this floor doc is embedded in a house plan, just close the window
        // without removing it from the house or asking to save separately
        val houseDoc = doc.ambientHouseDoc
        if (houseDoc != null) {
            doc.window?.jMenuBar?.let { bar ->
                menuBars.remove(bar)
                for (i in 0 until bar.menuCount) {
                    val menu = bar.getMenu(i)
                    if (menu?.text == "File") {
                        for (j in 0 until menu.itemCount) {
                            val item = menu.getItem(j)
                            if (item is JMenu && item.text == "Recent Files") recentMenus.remove(item)
                        }
                    }
                    if (menu?.text == "Edit") {
                        for (j in 0 until menu.itemCount) {
                            val item = menu.getItem(j)
                            if (item?.text == "Undo") undoMenuItems.remove(item)
                            if (item?.text == "Redo") redoMenuItems.remove(item)
                        }
                    }
                }
            }
            documents.remove(doc)
            if (activeDocument == doc) activeDocument = null
            if (activeWindow == doc.window) activeWindow = null
            doc.window?.dispose()
            // Regenerate 3D model now that the floor has been edited
            regenerate3DModelFromFloors(houseDoc)
            return
        }

        if (doc.isModified) {
            val result = JOptionPane.showConfirmDialog(
                doc.window,
                "Save changes to ${doc.currentFile?.name ?: "Untitled"}?",
                "Confirm Close",
                JOptionPane.YES_NO_CANCEL_OPTION
            )
            when (result) {
                JOptionPane.YES_OPTION -> {
                    activeDocument = doc
                    activeWindow = doc.window
                    save()
                    if (doc.isModified) return // Save cancelled or failed
                }
                JOptionPane.NO_OPTION -> {} // Just close
                else -> return // Cancel
            }
        }

        doc.window?.jMenuBar?.let { bar ->
            menuBars.remove(bar)
            // Also need to remove menus and items from lists to avoid memory leaks/updates to closed windows
            for (i in 0 until bar.menuCount) {
                val menu = bar.getMenu(i)
                if (menu?.text == "File") {
                    for (j in 0 until menu.itemCount) {
                        val item = menu.getItem(j)
                        if (item is JMenu && item.text == "Recent Files") {
                            recentMenus.remove(item)
                        }
                    }
                }
                if (menu?.text == "Edit") {
                    for (j in 0 until menu.itemCount) {
                        val item = menu.getItem(j)
                        if (item?.text == "Undo") undoMenuItems.remove(item)
                        if (item?.text == "Redo") redoMenuItems.remove(item)
                    }
                }
            }
        }

        documents.remove(doc)
        if (activeDocument == doc) activeDocument = null
        if (activeWindow == doc.window) activeWindow = null
        doc.window?.dispose()
        doc.currentFile?.let { openFiles.remove(it.absolutePath) }
        if (saveImmediately) saveSettings()
    }

    fun close3DDocument(doc: ThreeDDocument, saveImmediately: Boolean = true) {
        if (doc.isModified) {
            val result = JOptionPane.showConfirmDialog(
                doc.window,
                "Save changes to 3D model ${doc.currentFile?.name ?: "Untitled"}?",
                "Confirm Close",
                JOptionPane.YES_NO_CANCEL_OPTION
            )
            when (result) {
                JOptionPane.YES_OPTION -> {
                    save3D(doc)
                    if (doc.isModified) return
                }
                JOptionPane.NO_OPTION -> {}
                else -> return
            }
        }
        
        doc.window?.jMenuBar?.let { bar ->
            menuBars.remove(bar)
            for (i in 0 until bar.menuCount) {
                val menu = bar.getMenu(i)
                if (menu?.text == "File") {
                    for (j in 0 until menu.itemCount) {
                        val item = menu.getItem(j)
                        if (item is JMenu && item.text == "Recent Files") {
                            recentMenus.remove(item)
                        }
                    }
                }
            }
        }

        threeDDocuments.remove(doc)
        if (activeWindow == doc.window) activeWindow = null
        doc.panel.dispose()
        doc.window?.dispose()
        if (saveImmediately) saveSettings()
    }

    fun quitApp() {
        val docsToClose = documents.toList()
        val threedToClose = threeDDocuments.toList()
        // Save settings with the current open documents BEFORE closing them
        saveSettings(docsToClose, threedToClose)
        for (doc in docsToClose) {
            closeDocument(doc, saveImmediately = false)
        }
        for (doc in threedToClose) {
            close3DDocument(doc, saveImmediately = false)
        }
        if (documents.isEmpty() && threeDDocuments.isEmpty()) {
            System.exit(0)
        }
    }

    internal fun duplicateSelected() {
        activeDocument?.let { doc ->
            doc.selectedElement?.let { el ->
                val shift = (maxOf(el.width, el.height) * 0.05).roundToInt()
                val newEl = when (el) {
                    is Wall -> Wall(el.x + shift, el.y + shift, el.width, el.height)
                    is Room -> Room(el.x + shift, el.y + shift, el.width, el.height)
                    is PlanWindow -> PlanWindow(el.x + shift, el.y + shift, el.width, el.height, el.height3D, el.sillElevation)
                    is Door -> Door(el.x + shift, el.y + shift, el.width, el.height, el.verticalHeight)
                    is Stairs -> Stairs(el.x + shift, el.y + shift, el.width, el.height)
                    is PolygonRoom -> PolygonRoom(el.vertices.map { Point(it.x + shift, it.y + shift) }.toMutableList(), el.floorThickness)
                    else -> null
                }
                newEl?.let {
                    doc.saveState()
                    doc.elements.add(it)
                    doc.selectedElement = it
                    sidePanel.updateFields(it)
                    statsPanel.update()
                    doc.canvas.repaint()
                }
            }
        }
    }

    internal fun removeSelected() {
        activeDocument?.let { doc ->
            doc.selectedElement?.let { el ->
                doc.saveState()
                doc.elements.remove(el)
                doc.selectedElement = null
                sidePanel.clearFields()
                statsPanel.update()
                doc.canvas.repaint()
            }
        }
    }

    private fun loadSettings() {
        val file = File(SETTINGS_FILE)
        if (file.exists()) {
            val props = java.util.Properties()
            props.load(file.inputStream())
            lastDirectory = props.getProperty("lastDirectory")
            props.getProperty("recentFiles")?.split(";")?.filter { it.isNotEmpty() }?.let {
                recentFiles.addAll(it.filter { path -> File(path).exists() })
            }
            props.getProperty("openFiles")?.split(";")?.filter { it.isNotEmpty() }?.let {
                it.forEach { entry ->
                    val parts = entry.split("|")
                    val path = parts[0]
                    if (File(path).exists()) {
                        openFiles.add(path)
                        if (parts.size > 1) {
                            val values = parts[1].split(",")
                            if (values.size >= 2) {
                                val x = values[0].toIntOrNull()
                                val y = values[1].toIntOrNull()
                                if (x != null && y != null) {
                                    val pos = Point(x, y)
                                    if (values.size == 7) {
                                        val w = values[2].toIntOrNull()
                                        val h = values[3].toIntOrNull()
                                        val sc = values[4].toDoubleOrNull()
                                        val ox = values[5].toDoubleOrNull()
                                        val oy = values[6].toDoubleOrNull()
                                        if (w != null && h != null && sc != null && ox != null && oy != null) {
                                            openFileStates[path] = WindowState(pos, Dimension(w, h), sc, ox, oy)
                                        } else {
                                            openFileStates[path] = WindowState(pos, Dimension(1000, 700), 0.01, 0.0, 0.0)
                                        }
                                    } else {
                                        openFileStates[path] = WindowState(pos, Dimension(1000, 700), 0.01, 0.0, 0.0)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            sidePanelX = props.getProperty("sidePanelX")?.toIntOrNull()
            sidePanelY = props.getProperty("sidePanelY")?.toIntOrNull()
        }
    }

    private fun saveSettings(docsToPersist: List<FloorPlanDocument>? = null, threeDDocsToPersist: List<ThreeDDocument>? = null) {
        val props = java.util.Properties()
        if (lastDirectory != null) props.setProperty("lastDirectory", lastDirectory)
        props.setProperty("recentFiles", recentFiles.joinToString(";"))
        
        // Save currently open floor plan documents that have a file with their relative positions
        val docs = docsToPersist ?: documents
        val currentOpen = docs.mapNotNull { doc ->
            doc.currentFile?.let { file ->
                val window = doc.window
                val sideWindow = sidePanelWindow
                if (window != null && sideWindow != null) {
                    val relX = window.x - sideWindow.x
                    val relY = window.y - sideWindow.y
                    "${file.absolutePath}|$relX,$relY,${window.width},${window.height},${doc.scale},${doc.offsetX},${doc.offsetY}"
                } else {
                    file.absolutePath
                }
            }
        }.toMutableList()
        
        // Save currently open 3D documents that have a file with their relative positions
        val threeDDocs = threeDDocsToPersist ?: threeDDocuments
        val threeDOpen = threeDDocs.mapNotNull { doc ->
            doc.currentFile?.let { file ->
                val window = doc.window
                val sideWindow = sidePanelWindow
                if (window != null && sideWindow != null) {
                    val relX = window.x - sideWindow.x
                    val relY = window.y - sideWindow.y
                    "${file.absolutePath}|$relX,$relY,${window.width},${window.height},${doc.scale},${doc.rotationX},${doc.rotationZ}"
                } else {
                    file.absolutePath
                }
            }
        }
        currentOpen.addAll(threeDOpen)
        
        props.setProperty("openFiles", currentOpen.distinct().joinToString(";"))
        
        sidePanelWindow?.let {
            props.setProperty("sidePanelX", it.x.toString())
            props.setProperty("sidePanelY", it.y.toString())
        }
        
        props.store(File(SETTINGS_FILE).outputStream(), "FloorPlanApp Settings")
    }

    private fun addToRecent(file: File) {
        val path = file.absolutePath
        recentFiles.remove(path)
        recentFiles.add(0, path)
        while (recentFiles.size > MAX_RECENT) {
            recentFiles.removeAt(recentFiles.size - 1)
        }
        lastDirectory = file.parent
        saveSettings()
        updateRecentMenu()
    }

    private fun updateRecentMenu() {
        for (recentMenu in recentMenus) {
            recentMenu.removeAll()
            recentMenu.isVisible = true
            if (recentFiles.isEmpty()) {
                val noneItem = JMenuItem("None")
                noneItem.isEnabled = false
                recentMenu.add(noneItem)
            } else {
                for (filePath in recentFiles) {
                    val file = File(filePath)
                    val item = JMenuItem(file.name)
                    item.toolTipText = filePath
                    item.addActionListener { openFile(file) }
                    recentMenu.add(item)
                }
            }
        }
        for (menuBar in menuBars) {
            menuBar.revalidate()
            menuBar.repaint()
        }
    }

    private fun createMenuBar(): JMenuBar {
        val menuBar = JMenuBar()
        
        val fileMenu = JMenu("File")
        val newItem = JMenuItem("New Document")
        newItem.addActionListener { createNew() }
        fileMenu.add(newItem)
        
        val openItem = JMenuItem("Open")
        openItem.addActionListener { 
            val chooser = JFileChooser()
            chooser.isMultiSelectionEnabled = true
            lastDirectory?.let { chooser.currentDirectory = File(it) }
            if (chooser.showOpenDialog(activeWindow) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFiles.forEach { openFile(it) }
            }
        }
        fileMenu.add(openItem)
        
        val recentMenu = JMenu("Recent Files")
        recentMenu.isVisible = true
        fileMenu.add(recentMenu)
        recentMenus.add(recentMenu)
        
        fileMenu.addSeparator()
        
        val saveItem = JMenuItem("Save")
        saveItem.addActionListener { 
            activeDocument?.let { save() }
            activeWindow?.let { win ->
                if (win is ThreeDWindow) save3D(win.doc)
            }
        }
        fileMenu.add(saveItem)
        
        val saveAsItem = JMenuItem("Save As...")
        saveAsItem.addActionListener { 
            activeDocument?.let { saveAs() }
            activeWindow?.let { win ->
                if (win is ThreeDWindow) saveAs3D(win.doc)
            }
        }
        fileMenu.add(saveAsItem)
        
        fileMenu.addSeparator()
        
        val exportItem = JMenuItem("Export to PNG")
        exportItem.addActionListener { exportToPNG() }
        fileMenu.add(exportItem)
        
        fileMenu.addSeparator()
        
        val quitItem = JMenuItem("Quit")
        quitItem.addActionListener { quitApp() }
        fileMenu.add(quitItem)
        
        menuBar.add(fileMenu)
        
        val editMenu = JMenu("Edit")
        val undoMenuItem = JMenuItem("Undo")
        undoMenuItem.accelerator = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        undoMenuItem.addActionListener { activeDocument?.undo() }
        undoMenuItem.isVisible = true
        editMenu.add(undoMenuItem)
        undoMenuItems.add(undoMenuItem)
        
        val redoMenuItem = JMenuItem("Redo")
        redoMenuItem.accelerator = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        redoMenuItem.addActionListener { activeDocument?.redo() }
        redoMenuItem.isVisible = true
        editMenu.add(redoMenuItem)
        redoMenuItems.add(redoMenuItem)
        
        updateUndoRedoStates()
        
        editMenu.addSeparator()
        
        val duplicateItem = JMenuItem("Duplicate")
        duplicateItem.addActionListener { duplicateSelected() }
        editMenu.add(duplicateItem)
        
        val removeItem = JMenuItem("Remove")
        removeItem.addActionListener { removeSelected() }
        editMenu.add(removeItem)
        
        menuBar.add(editMenu)

        val viewMenu = JMenu("View")
        val fitSceneItem = JMenuItem("Fit scene")
        fitSceneItem.addActionListener { activeDocument?.autoScaleToFit() }
        viewMenu.add(fitSceneItem)
        
        viewMenu.addSeparator()
        
        val dimLabelsItem = JCheckBoxMenuItem("Toggle dimension labels")
        dimLabelsItem.addActionListener {
            activeDocument?.let { doc ->
                doc.showDimensionLabels = dimLabelsItem.isSelected
                doc.canvas.repaint()
            }
        }
        viewMenu.add(dimLabelsItem)
        menuBar.add(viewMenu)

        val toolsMenu = JMenu("Tools")
        val create3DItem = JMenuItem("Create 3D model")
        create3DItem.addActionListener { showCreate3DDialog() }
        toolsMenu.add(create3DItem)
        menuBar.add(toolsMenu)
        
        menuBars.add(menuBar)
        updateRecentMenu()
        val utilitiesMenu = JMenu("Utilities")
        val manageKindsItem = JMenuItem("Manage Wall Layout Kinds")
        manageKindsItem.addActionListener { showManageKindsDialog() }
        utilitiesMenu.add(manageKindsItem)

        val assetManagerItem = JMenuItem("Asset Manager")
        assetManagerItem.addActionListener { showAssetManagerDialog() }
        utilitiesMenu.add(assetManagerItem)

        toolsMenu.add(utilitiesMenu)
        
        return menuBar
    }

    private fun showCreate3DDialog() {
        val dialog = JDialog(activeWindow, "Create 3D Model", true)
        dialog.layout = BorderLayout()
        dialog.setSize(500, 400)
        dialog.setLocationRelativeTo(activeWindow)

        val topPanel = JPanel()
        topPanel.add(JLabel("Number of floors:"))
        val floorCountSpinner = JSpinner(SpinnerNumberModel(1, 1, 20, 1))
        topPanel.add(floorCountSpinner)

        val tableModel = DefaultTableModel(arrayOf("Floor", "Document", "Height (cm)"), 0)
        val table = JTable(tableModel)

        fun updateRows() {
            val count = floorCountSpinner.value as Int
            val currentRows = tableModel.rowCount
            if (count > currentRows) {
                for (i in currentRows until count) {
                    tableModel.addRow(arrayOf(i + 1, "None", 300))
                }
            } else if (count < currentRows) {
                for (i in currentRows - 1 downTo count) {
                    tableModel.removeRow(i)
                }
            }
        }

        updateRows()
        floorCountSpinner.addChangeListener { updateRows() }

        val docNames = documents.map { it.currentFile?.name ?: "Untitled" }.toTypedArray()
        val comboBox = JComboBox(docNames)
        table.columnModel.getColumn(1).cellEditor = DefaultCellEditor(comboBox)

        dialog.add(topPanel, BorderLayout.NORTH)
        dialog.add(JScrollPane(table), BorderLayout.CENTER)

        val okBtn = JButton("OK")
        okBtn.addActionListener {
            if (table.isEditing) table.cellEditor?.stopCellEditing()
            val doc3d = ThreeDDocument(this)
            var firstSourceDoc: FloorPlanDocument? = null

            for (i in 0 until tableModel.rowCount) {
                val docName = tableModel.getValueAt(i, 1) as String
                val height = tableModel.getValueAt(i, 2).toString().toDouble().toInt()
                val sourceDoc = documents.find { (it.currentFile?.name ?: "Untitled") == docName }
                if (sourceDoc != null) {
                    if (firstSourceDoc == null) firstSourceDoc = sourceDoc
                    val embeddedFloorDoc = FloorPlanDocument(this)
                    embeddedFloorDoc.elements.addAll(sourceDoc.cloneElements(sourceDoc.elements))
                    embeddedFloorDoc.ambientHouseDoc = doc3d
                    val floorData = ThreeDDocument.FloorData("Floor ${i + 1}", height, true, embeddedFloorDoc)
                    doc3d.floors.add(floorData)
                }
            }

            if (doc3d.floors.isNotEmpty()) {
                if (firstSourceDoc != null) {
                    doc3d.kinds.clear()
                    doc3d.kinds.addAll(firstSourceDoc.kinds.map { it.copy() })
                    firstSourceDoc.assetDefinitions.forEach { (k, v) ->
                        doc3d.assetDefinitions[k] = v.map { it.copy() }.toMutableList()
                    }
                }
                doc3d.model = buildModel3D(doc3d)
                doc3d.isModified = true
                threeDDocuments.add(doc3d)
                val window = ThreeDWindow(this, doc3d)
                window.jMenuBar = createMenuBar()
                dialog.dispose()
            } else {
                JOptionPane.showMessageDialog(dialog, "Please select documents for floors.")
            }
        }
        dialog.add(okBtn, BorderLayout.SOUTH)
        dialog.isVisible = true
    }

    private fun areAdjacentRooms(r1: Room, r2: Room): Boolean {
        val rect1 = r1.getBounds()
        val rect2 = r2.getBounds()
        if (rect1.y < rect2.y + rect2.height && rect1.y + rect1.height > rect2.y) {
            if (rect1.x == rect2.x + rect2.width || rect2.x == rect1.x + rect1.width) return true
        }
        if (rect1.x < rect2.x + rect2.width && rect1.x + rect1.width > rect2.x) {
            if (rect1.y == rect2.y + rect2.height || rect2.y == rect1.y + rect1.height) return true
        }
        return false
    }
    
    private fun isPolygonRoomDockedToRoom(pr: PolygonRoom, room: Room): Boolean {
        // Check if polygon room shares a horizontal or vertical edge with the room
        val roomBounds = room.getBounds()
        val vertices = pr.vertices
        
        for (i in vertices.indices) {
            val v1 = vertices[i]
            val v2 = vertices[(i + 1) % vertices.size]
            
            // Check if this edge is horizontal or vertical
            if (v1.x == v2.x) {
                // Vertical edge
                val edgeX = v1.x
                val edgeMinY = minOf(v1.y, v2.y)
                val edgeMaxY = maxOf(v1.y, v2.y)
                
                // Check if this edge aligns with room's left or right edge
                if (edgeX == roomBounds.x || edgeX == roomBounds.x + roomBounds.width) {
                    // Check if there's vertical overlap
                    if (edgeMinY < roomBounds.y + roomBounds.height && edgeMaxY > roomBounds.y) {
                        return true
                    }
                }
            } else if (v1.y == v2.y) {
                // Horizontal edge
                val edgeY = v1.y
                val edgeMinX = minOf(v1.x, v2.x)
                val edgeMaxX = maxOf(v1.x, v2.x)
                
                // Check if this edge aligns with room's top or bottom edge
                if (edgeY == roomBounds.y || edgeY == roomBounds.y + roomBounds.height) {
                    // Check if there's horizontal overlap
                    if (edgeMinX < roomBounds.x + roomBounds.width && edgeMaxX > roomBounds.x) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun setupPopupMenu() {
        popAddWallMenu = JMenuItem("Add Wall")
        popAddWallMenu.addActionListener { sidePanel.addElement(ElementType.WALL) }
        popupMenu.add(popAddWallMenu)

        popAddRoomMenu = JMenuItem("Add Room")
        popAddRoomMenu.addActionListener { sidePanel.addElement(ElementType.ROOM) }
        popupMenu.add(popAddRoomMenu)

        popAddFloorOpeningMenu = JMenuItem("Add Room Polygon")
        popAddFloorOpeningMenu.addActionListener { sidePanel.addElement(ElementType.POLYGON_ROOM) }
        popupMenu.add(popAddFloorOpeningMenu)

        popAddUtilityMenu = JMenu("Add utility connection")
        popupMenu.add(popAddUtilityMenu)

        popSepGeneral = JSeparator()
        popupMenu.add(popSepGeneral)

        popDuplicateMenu = JMenuItem("Duplicate")
        popDuplicateMenu.addActionListener { duplicateSelected() }
        popupMenu.add(popDuplicateMenu)

        popRemoveMenu = JMenuItem("Remove")
        popRemoveMenu.addActionListener { removeSelected() }
        popupMenu.add(popRemoveMenu)

        popSepElements = JSeparator()
        popupMenu.add(popSepElements)

        popAddWindowMenu = JMenuItem("Add Window")
        popAddWindowMenu.addActionListener {
            activeDocument?.let { doc ->
                doc.selectedElement?.let { el ->
                    if (el is Wall) {
                        val window = PlanWindow(el.x + 10, el.y, 80, el.height)
                        doc.saveState()
                        doc.elements.add(window)
                        doc.selectedElement = window
                        sidePanel.updateFields(window)
                        statsPanel.update()
                        doc.canvas.repaint()
                    }
                }
            }
        }
        popupMenu.add(popAddWindowMenu)

        popAddDoorMenu = JMenuItem("Add Door")
        popAddDoorMenu.addActionListener {
            activeDocument?.let { doc ->
                doc.selectedElement?.let { el ->
                    if (el is Wall) {
                        val door = Door(el.x + 10, el.y, 80, el.height)
                        doc.saveState()
                        doc.elements.add(door)
                        doc.selectedElement = door
                        sidePanel.updateFields(door)
                        statsPanel.update()
                        doc.canvas.repaint()
                    }
                }
            }
        }
        popupMenu.add(popAddDoorMenu)

        popSepRoom = JSeparator()
        popupMenu.add(popSepRoom)

        popAddStairsMenu = JMenuItem("Add Stairs")
        popAddStairsMenu.addActionListener { sidePanel.addElement(ElementType.STAIRS) }
        popupMenu.add(popAddStairsMenu)
        
        popConvertToPolygonMenu = JMenuItem("Convert to Polygon Room")
        popConvertToPolygonMenu.addActionListener {
            activeDocument?.let { doc ->
                val room = doc.selectedElement as? Room ?: return@addActionListener
                doc.saveState()
                // Convert room rectangle to polygon vertices
                val vertices = mutableListOf(
                    Point(room.x, room.y),
                    Point(room.x + room.width, room.y),
                    Point(room.x + room.width, room.y + room.height),
                    Point(room.x, room.y + room.height)
                )
                val polygonRoom = PolygonRoom(vertices, room.floorThickness)
                // Replace room with polygon room
                val index = doc.elements.indexOf(room)
                doc.elements.remove(room)
                doc.elements.add(index, polygonRoom)
                doc.selectedElement = polygonRoom
                sidePanel.updateFields(polygonRoom)
                statsPanel.update()
                doc.canvas.repaint()
            }
        }
        popupMenu.add(popConvertToPolygonMenu)

        popEditFrontFaceMenu = JMenuItem("Edit Front Face")
        popEditFrontFaceMenu.addActionListener {
            activeDocument?.let { doc ->
                (doc.selectedElement as? Wall)?.let { wall ->
                    WallLayoutWindow(this, WallLayoutDocument(this, doc, wall, wall.frontLayout, true))
                }
            }
        }
        popupMenu.add(popEditFrontFaceMenu)

        popEditBackFaceMenu = JMenuItem("Edit Back Face")
        popEditBackFaceMenu.addActionListener {
            activeDocument?.let { doc ->
                (doc.selectedElement as? Wall)?.let { wall ->
                    WallLayoutWindow(this, WallLayoutDocument(this, doc, wall, wall.backLayout, false))
                }
            }
        }
        popupMenu.add(popEditBackFaceMenu)
    }

    internal fun refreshUI() {
        activeDocument?.let { doc ->
            doc.selectedElement = null
            sidePanel.clearFields()
            statsPanel.update()
            doc.canvas.repaint()
        }
    }
    
    fun getOpenDocuments(): List<FloorPlanDocument> = documents.toList()
    fun getThreeDDocuments(): List<ThreeDDocument> = threeDDocuments.toList()
    
    fun regenerate3DModelFromFloors(doc3d: ThreeDDocument) {
        doc3d.model = buildModel3D(doc3d)
        doc3d.panel.updateModel()
    }

    internal fun openEmbeddedFloorEditor(doc3d: ThreeDDocument, floorIndex: Int) {
        val floorData = doc3d.floors.getOrNull(floorIndex) ?: return
        val floorDoc = floorData.floorDoc

        // Bring to front if already open
        val existingWindow = floorDoc.window
        if (existingWindow != null && existingWindow.isDisplayable) {
            existingWindow.toFront()
            activeDocument = floorDoc
            activeWindow = existingWindow
            sidePanel.updateFieldsForActiveDocument()
            updateUndoRedoStates()
            return
        }

        floorDoc.ambientHouseDoc = doc3d
        floorDoc.displayName = "${floorData.name} [${doc3d.currentFile?.name ?: "New House"}]"

        if (!documents.contains(floorDoc)) {
            documents.add(floorDoc)
        }

        if (floorDoc.undoStack.isEmpty()) {
            floorDoc.saveState()
            floorDoc.isModified = false
            doc3d.isModified = doc3d.floors.any { it.floorDoc.isModified }
        }

        val window = EditorWindow(this, floorDoc)
        window.jMenuBar = createMenuBar()

        activeDocument = floorDoc
        activeWindow = window
        updateUndoRedoStates()
        sidePanel.updateFieldsForActiveDocument()
    }
    
    // Internal function to build Model3D from the embedded floors in doc3d
    private fun buildModel3D(doc3d: ThreeDDocument): model.Model3D {
        val model3d = model.Model3D()
        val floors = doc3d.floors

        var currentZ = 0.0
        for ((floorIndex, floorEntry) in floors.withIndex()) {
            val floorHeight = floorEntry.height.toDouble()
            val roomsOnFloor = floorEntry.floorDoc.elements.filterIsInstance<Room>()

            // Compute light positions based on dockedness components
            val roomsWithoutOffset = roomsOnFloor.filter { it.zOffset == 0 }
            if (roomsWithoutOffset.isNotEmpty()) {
                val centerZ = currentZ + floorHeight / 2.0
                val adjacency = mutableMapOf<Room, MutableSet<Room>>()
                for (i in roomsWithoutOffset.indices) {
                    for (j in i + 1 until roomsWithoutOffset.size) {
                        val r1 = roomsWithoutOffset[i]
                        val r2 = roomsWithoutOffset[j]
                        if (areAdjacentRooms(r1, r2)) {
                            adjacency.getOrPut(r1) { mutableSetOf() }.add(r2)
                            adjacency.getOrPut(r2) { mutableSetOf() }.add(r1)
                        }
                    }
                }

                val visited = mutableSetOf<Room>()
                for (room in roomsWithoutOffset) {
                    if (room !in visited) {
                        val component = mutableListOf<Room>()
                        val queue: java.util.Queue<Room> = java.util.LinkedList()
                        queue.add(room)
                        visited.add(room)
                        while (queue.isNotEmpty()) {
                            val r = queue.poll()
                            component.add(r)
                            adjacency[r]?.forEach { neighbor ->
                                if (neighbor !in visited) {
                                    visited.add(neighbor)
                                    queue.add(neighbor)
                                }
                            }
                        }

                        val largestRoom = component.maxByOrNull { it.width.toDouble() * it.height.toDouble() }
                        if (largestRoom != null) {
                            val centerX = largestRoom.x.toDouble() + largestRoom.width.toDouble() / 2.0
                            val centerY = largestRoom.y.toDouble() + largestRoom.height.toDouble() / 2.0
                            model3d.lightPositions.add(Vector3D(centerX, centerY, centerZ))
                        }
                    }
                }
            }

            // Check if this floor should be drawn
            val shouldDraw = floorEntry.draw
            if (!shouldDraw) {
                currentZ += floorHeight
                continue
            }
            val wallColor = Color.DARK_GRAY
            val floorAlpha = 255

            for (el in floorEntry.floorDoc.elements) {
                when (el) {
                    is Room -> {
                        val x1 = el.x.toDouble()
                        val y1 = el.y.toDouble()
                        val x2 = (el.x + el.width).toDouble()
                        val y2 = (el.y + el.height).toDouble()
                        val thickness = el.floorThickness.toDouble()
                        val zOff = el.zOffset.toDouble()
                        val slabTopZ = currentZ + zOff
                        val slabBottomZ = slabTopZ - thickness
                        
                        val otherRooms = roomsOnFloor.filter { it !== el }
                        val hasAdjacentTop = otherRooms.any { other ->
                            other.y + other.height == el.y && 
                            other.x < el.x + el.width && other.x + other.width > el.x
                        }
                        val hasAdjacentBottom = otherRooms.any { other ->
                            other.y == el.y + el.height && 
                            other.x < el.x + el.width && other.x + other.width > el.x
                        }
                        val hasAdjacentLeft = otherRooms.any { other ->
                            other.x + other.width == el.x && 
                            other.y < el.y + el.height && other.y + other.height > el.y
                        }
                        val hasAdjacentRight = otherRooms.any { other ->
                            other.x == el.x + el.width && 
                            other.y < el.y + el.height && other.y + other.height > el.y
                        }
                        
                        val slabColor = Color(Color.LIGHT_GRAY.red, Color.LIGHT_GRAY.green, Color.LIGHT_GRAY.blue, floorAlpha)
                        
                        model3d.rects.add(Rect3D(
                            Vector3D(x1, y1, slabTopZ), Vector3D(x2, y1, slabTopZ),
                            Vector3D(x2, y2, slabTopZ), Vector3D(x1, y2, slabTopZ),
                            slabColor
                        ))
                        model3d.rects.add(Rect3D(
                            Vector3D(x1, y1, slabBottomZ), Vector3D(x2, y1, slabBottomZ),
                            Vector3D(x2, y2, slabBottomZ), Vector3D(x1, y2, slabBottomZ),
                            slabColor
                        ))
                        if (!hasAdjacentTop) {
                            model3d.rects.add(Rect3D(
                                Vector3D(x1, y1, slabBottomZ), Vector3D(x2, y1, slabBottomZ),
                                Vector3D(x2, y1, slabTopZ), Vector3D(x1, y1, slabTopZ),
                                slabColor
                            ))
                        }
                        if (!hasAdjacentRight) {
                            model3d.rects.add(Rect3D(
                                Vector3D(x2, y1, slabBottomZ), Vector3D(x2, y2, slabBottomZ),
                                Vector3D(x2, y2, slabTopZ), Vector3D(x2, y1, slabTopZ),
                                slabColor
                            ))
                        }
                        if (!hasAdjacentBottom) {
                            model3d.rects.add(Rect3D(
                                Vector3D(x2, y2, slabBottomZ), Vector3D(x1, y2, slabBottomZ),
                                Vector3D(x1, y2, slabTopZ), Vector3D(x2, y2, slabTopZ),
                                slabColor
                            ))
                        }
                        if (!hasAdjacentLeft) {
                            model3d.rects.add(Rect3D(
                                Vector3D(x1, y2, slabBottomZ), Vector3D(x1, y1, slabBottomZ),
                                Vector3D(x1, y1, slabTopZ), Vector3D(x1, y2, slabTopZ),
                                slabColor
                            ))
                        }
                    }
                    is PolygonRoom -> {
                        val thickness = el.floorThickness.toDouble()
                        val zOff = el.zOffset.toDouble()
                        val slabTopZ = currentZ + zOff
                        val slabBottomZ = slabTopZ - thickness
                        val vertices = el.vertices
                        
                        val slabColor = Color(Color.LIGHT_GRAY.red, Color.LIGHT_GRAY.green, Color.LIGHT_GRAY.blue, floorAlpha)
                        
                        if (vertices.size >= 3) {
                            val centroidX = vertices.sumOf { it.x.toDouble() } / vertices.size
                            val centroidY = vertices.sumOf { it.y.toDouble() } / vertices.size
                            
                            for (i in vertices.indices) {
                                val v1 = vertices[i]
                                val v2 = vertices[(i + 1) % vertices.size]
                                
                                model3d.triangles.add(Triangle3D(
                                    Vector3D(centroidX, centroidY, slabTopZ),
                                    Vector3D(v1.x.toDouble(), v1.y.toDouble(), slabTopZ),
                                    Vector3D(v2.x.toDouble(), v2.y.toDouble(), slabTopZ),
                                    slabColor
                                ))
                                
                                model3d.triangles.add(Triangle3D(
                                    Vector3D(centroidX, centroidY, slabBottomZ),
                                    Vector3D(v2.x.toDouble(), v2.y.toDouble(), slabBottomZ),
                                    Vector3D(v1.x.toDouble(), v1.y.toDouble(), slabBottomZ),
                                    slabColor
                                ))
                                
                                model3d.triangles.add(Triangle3D(
                                    Vector3D(v1.x.toDouble(), v1.y.toDouble(), slabTopZ),
                                    Vector3D(v1.x.toDouble(), v1.y.toDouble(), slabBottomZ),
                                    Vector3D(v2.x.toDouble(), v2.y.toDouble(), slabBottomZ),
                                    slabColor
                                ))
                                model3d.triangles.add(Triangle3D(
                                    Vector3D(v1.x.toDouble(), v1.y.toDouble(), slabTopZ),
                                    Vector3D(v2.x.toDouble(), v2.y.toDouble(), slabBottomZ),
                                    Vector3D(v2.x.toDouble(), v2.y.toDouble(), slabTopZ),
                                    slabColor
                                ))
                            }
                        }
                    }
                    is Wall -> {
                        val wallBounds = el.getBounds()
                        val openings = floorEntry.floorDoc.elements.filter { it is PlanWindow || it is Door }
                            .filter { wallBounds.contains(it.getBounds()) }

                        var wallTopReduction = 0.0
                        if (floorIndex < floors.size - 1) {
                            val floorAbove = floors[floorIndex + 1]
                            val roomsAbove = floorAbove.floorDoc.elements.filterIsInstance<Room>()
                            for (roomAbove in roomsAbove) {
                                val roomBounds = roomAbove.getBounds()
                                if (roomBounds.contains(wallBounds)) {
                                    wallTopReduction = roomAbove.floorThickness.toDouble()
                                    break
                                }
                            }
                        }
                        val effectiveWallTop = currentZ + floorHeight - wallTopReduction

                        fun addBox(x1: Double, y1: Double, x2: Double, y2: Double, z1: Double, z2: Double, color: Color) {
                            if (x1 >= x2 || y1 >= y2 || z1 >= z2) return
                            model3d.rects.add(Rect3D(Vector3D(x1, y1, z1), Vector3D(x2, y1, z1), Vector3D(x2, y2, z1), Vector3D(x1, y2, z1), color))
                            model3d.rects.add(Rect3D(Vector3D(x1, y1, z2), Vector3D(x2, y1, z2), Vector3D(x2, y2, z2), Vector3D(x1, y2, z2), color))
                            model3d.rects.add(Rect3D(Vector3D(x1, y1, z1), Vector3D(x2, y1, z1), Vector3D(x2, y1, z2), Vector3D(x1, y1, z2), color))
                            model3d.rects.add(Rect3D(Vector3D(x1, y2, z1), Vector3D(x2, y2, z1), Vector3D(x2, y2, z2), Vector3D(x1, y2, z2), color))
                            model3d.rects.add(Rect3D(Vector3D(x1, y1, z1), Vector3D(x1, y2, z1), Vector3D(x1, y2, z2), Vector3D(x1, y1, z2), color))
                            model3d.rects.add(Rect3D(Vector3D(x2, y1, z1), Vector3D(x2, y2, z1), Vector3D(x2, y2, z2), Vector3D(x2, y1, z2), color))
                        }

                        if (openings.isEmpty()) {
                            addBox(el.x.toDouble(), el.y.toDouble(), (el.x + el.width).toDouble(), (el.y + el.height).toDouble(), currentZ, effectiveWallTop, wallColor)
                        } else {
                            val isVertical = el.width < el.height
                            if (isVertical) {
                                val x1 = el.x.toDouble()
                                val x2 = (el.x + el.width).toDouble()
                                var lastY = el.y.toDouble()
                                val sortedOpenings = openings.sortedBy { it.y }
                                for (op in sortedOpenings) {
                                    val opY1 = op.y.toDouble()
                                    val opY2 = (op.y + op.height).toDouble()
                                    val opZ1 = currentZ + (if (op is PlanWindow) op.sillElevation.toDouble() else 0.0)
                                    val opZ2 = opZ1 + (if (op is PlanWindow) op.height3D.toDouble() else (op as Door).verticalHeight.toDouble())
                                    
                                    addBox(x1, lastY, x2, opY1, currentZ, effectiveWallTop, wallColor)
                                    addBox(x1, opY1, x2, opY2, opZ2, effectiveWallTop, wallColor)
                                    addBox(x1, opY1, x2, opY2, currentZ, opZ1, wallColor)

                                    // Generate window frame for windows
                                    if (op is PlanWindow) {
                                        val windowColor = Color(100, 150, 255, 100) // Semi-transparent bluish
                                        val windowPos = op.windowPosition
                                        
                                        // Determine which edge to place the window frame based on WindowPosition
                                        val frameX = if (windowPos == WindowPosition.X2Y2) x2 else x1
                                        
                                        // Create window frame as a thin rectangle (cylinder over the edge line)
                                        model3d.rects.add(Rect3D(
                                            Vector3D(frameX, opY1, opZ1),
                                            Vector3D(frameX, opY2, opZ1),
                                            Vector3D(frameX, opY2, opZ2),
                                            Vector3D(frameX, opY1, opZ2),
                                            windowColor,
                                            isWindow = true
                                        ))
                                    }

                                    lastY = opY2
                                }
                                addBox(x1, lastY, x2, (el.y + el.height).toDouble(), currentZ, effectiveWallTop, wallColor)
                            } else {
                                val y1 = el.y.toDouble()
                                val y2 = (el.y + el.height).toDouble()
                                var lastX = el.x.toDouble()
                                val sortedOpenings = openings.sortedBy { it.x }
                                for (op in sortedOpenings) {
                                    val opX1 = op.x.toDouble()
                                    val opX2 = (op.x + op.width).toDouble()
                                    val opZ1 = currentZ + (if (op is PlanWindow) op.sillElevation.toDouble() else 0.0)
                                    val opZ2 = opZ1 + (if (op is PlanWindow) op.height3D.toDouble() else (op as Door).verticalHeight.toDouble())

                                    addBox(lastX, y1, opX1, y2, currentZ, effectiveWallTop, wallColor)
                                    addBox(opX1, y1, opX2, y2, opZ2, effectiveWallTop, wallColor)
                                    addBox(opX1, y1, opX2, y2, currentZ, opZ1, wallColor)

                                    // Generate window frame for windows
                                    if (op is PlanWindow) {
                                        val windowColor = Color(100, 150, 255, 100) // Semi-transparent bluish
                                        val windowPos = op.windowPosition
                                        
                                        // Determine which edge to place the window frame based on WindowPosition
                                        val frameY = if (windowPos == WindowPosition.X2Y2) y2 else y1
                                        
                                        // Create window frame as a thin rectangle (cylinder over the edge line)
                                        model3d.rects.add(Rect3D(
                                            Vector3D(opX1, frameY, opZ1),
                                            Vector3D(opX2, frameY, opZ1),
                                            Vector3D(opX2, frameY, opZ2),
                                            Vector3D(opX1, frameY, opZ2),
                                            windowColor,
                                            isWindow = true
                                        ))
                                    }

                                    lastX = opX2
                                }
                                addBox(lastX, y1, (el.x + el.width).toDouble(), y2, currentZ, effectiveWallTop, wallColor)
                            }
                        }
                    }
                    is PlanWindow -> {
                        // Free-standing window - generate window frame
                        val isVertical = el.width < el.height
                        val windowColor = Color(100, 150, 255, 100) // Semi-transparent bluish
                        val windowPos = el.windowPosition
                        
                        val x1 = el.x.toDouble()
                        val y1 = el.y.toDouble()
                        val x2 = (el.x + el.width).toDouble()
                        val y2 = (el.y + el.height).toDouble()
                        val opZ1 = currentZ + el.sillElevation.toDouble()
                        val opZ2 = opZ1 + el.height3D.toDouble()
                        
                        if (isVertical) {
                            // Vertical window - frame on left or right edge
                            val frameX = if (windowPos == WindowPosition.X2Y2) x2 else x1
                            model3d.rects.add(Rect3D(
                                Vector3D(frameX, y1, opZ1),
                                Vector3D(frameX, y2, opZ1),
                                Vector3D(frameX, y2, opZ2),
                                Vector3D(frameX, y1, opZ2),
                                windowColor,
                                isWindow = true
                            ))
                        } else {
                            // Horizontal window - frame on top or bottom edge
                            val frameY = if (windowPos == WindowPosition.X2Y2) y2 else y1
                            model3d.rects.add(Rect3D(
                                Vector3D(x1, frameY, opZ1),
                                Vector3D(x2, frameY, opZ1),
                                Vector3D(x2, frameY, opZ2),
                                Vector3D(x1, frameY, opZ2),
                                windowColor,
                                isWindow = true
                            ))
                        }
                    }
                    is Door -> {
                        val x1 = el.x.toDouble()
                        val y1 = el.y.toDouble()
                        val x2 = (el.x + el.width).toDouble()
                        val y2 = (el.y + el.height).toDouble()
                        val doorZ1 = currentZ
                        val doorZ2 = currentZ + el.verticalHeight.toDouble()
                        
                        model3d.doorInfos.add(model.DoorInfo(x1, y1, x2, y2, doorZ1, doorZ2))
                    }
                    is Stairs -> {
                        val x1 = el.x.toDouble()
                        val y1 = el.y.toDouble()
                        val x2 = (el.x + el.width).toDouble()
                        val y2 = (el.y + el.height).toDouble()
                        
                        val stepHeight = 17.0
                        val totalRaise = el.totalRaise.toDouble()
                        val numSteps = if (totalRaise == 0.0) 1 else kotlin.math.max(1, kotlin.math.abs(totalRaise / stepHeight).toInt())
                        val actualStepHeight = if (totalRaise == 0.0) 0.0 else totalRaise / numSteps
                        
                        val isAlongX = el.directionAlongX
                        val stairLength = if (isAlongX) (x2 - x1) else (y2 - y1)
                        val actualStepDepth = stairLength / numSteps
                        
                        val baseZ = currentZ + el.zOffset.toDouble()
                        
                        model3d.stairInfos.add(model.StairInfo(x1, y1, x2, y2, baseZ, totalRaise, isAlongX))
                        
                        val stairColor = Color(139, 119, 101, floorAlpha)
                        val slabColor = Color(160, 140, 120, floorAlpha)
                        val slabThickness = 15.0
                        
                        for (step in 0 until numSteps) {
                            val stepZ = baseZ + step * actualStepHeight
                            val nextStepZ = baseZ + (step + 1) * actualStepHeight
                            
                            if (isAlongX) {
                                val stepX1 = if (totalRaise >= 0) x1 + step * actualStepDepth else x2 - (step + 1) * actualStepDepth
                                val stepX2 = if (totalRaise >= 0) x1 + (step + 1) * actualStepDepth else x2 - step * actualStepDepth
                                
                                model3d.rects.add(Rect3D(
                                    Vector3D(stepX1, y1, nextStepZ), Vector3D(stepX2, y1, nextStepZ),
                                    Vector3D(stepX2, y2, nextStepZ), Vector3D(stepX1, y2, nextStepZ),
                                    stairColor
                                ))
                                
                                if (totalRaise >= 0) {
                                    model3d.rects.add(Rect3D(
                                        Vector3D(stepX1, y1, stepZ), Vector3D(stepX1, y2, stepZ),
                                        Vector3D(stepX1, y2, nextStepZ), Vector3D(stepX1, y1, nextStepZ),
                                        stairColor
                                    ))
                                } else {
                                    model3d.rects.add(Rect3D(
                                        Vector3D(stepX2, y1, stepZ), Vector3D(stepX2, y2, stepZ),
                                        Vector3D(stepX2, y2, nextStepZ), Vector3D(stepX2, y1, nextStepZ),
                                        stairColor
                                    ))
                                }
                                
                                model3d.triangles.add(Triangle3D(Vector3D(stepX1, y1, stepZ), Vector3D(stepX2, y1, stepZ), Vector3D(stepX2, y1, nextStepZ), stairColor, isStairsVisualOnly = true))
                                model3d.triangles.add(Triangle3D(Vector3D(stepX1, y1, stepZ), Vector3D(stepX2, y1, nextStepZ), Vector3D(stepX1, y1, nextStepZ), stairColor, isStairsVisualOnly = true))
                                model3d.triangles.add(Triangle3D(Vector3D(stepX1, y2, stepZ), Vector3D(stepX2, y2, nextStepZ), Vector3D(stepX2, y2, stepZ), stairColor, isStairsVisualOnly = true))
                                model3d.triangles.add(Triangle3D(Vector3D(stepX1, y2, stepZ), Vector3D(stepX1, y2, nextStepZ), Vector3D(stepX2, y2, nextStepZ), stairColor, isStairsVisualOnly = true))
                            } else {
                                val stepY1 = if (totalRaise >= 0) y1 + step * actualStepDepth else y2 - (step + 1) * actualStepDepth
                                val stepY2 = if (totalRaise >= 0) y1 + (step + 1) * actualStepDepth else y2 - step * actualStepDepth
                                
                                model3d.rects.add(Rect3D(
                                    Vector3D(x1, stepY1, nextStepZ), Vector3D(x2, stepY1, nextStepZ),
                                    Vector3D(x2, stepY2, nextStepZ), Vector3D(x1, stepY2, nextStepZ),
                                    stairColor
                                ))
                                
                                if (totalRaise >= 0) {
                                    model3d.rects.add(Rect3D(
                                        Vector3D(x1, stepY1, stepZ), Vector3D(x2, stepY1, stepZ),
                                        Vector3D(x2, stepY1, nextStepZ), Vector3D(x1, stepY1, nextStepZ),
                                        stairColor
                                    ))
                                } else {
                                    model3d.rects.add(Rect3D(
                                        Vector3D(x1, stepY2, stepZ), Vector3D(x2, stepY2, stepZ),
                                        Vector3D(x2, stepY2, nextStepZ), Vector3D(x1, stepY2, nextStepZ),
                                        stairColor
                                    ))
                                }
                                
                                model3d.triangles.add(Triangle3D(Vector3D(x1, stepY1, stepZ), Vector3D(x1, stepY2, nextStepZ), Vector3D(x1, stepY2, stepZ), stairColor, isStairsVisualOnly = true))
                                model3d.triangles.add(Triangle3D(Vector3D(x1, stepY1, stepZ), Vector3D(x1, stepY1, nextStepZ), Vector3D(x1, stepY2, nextStepZ), stairColor, isStairsVisualOnly = true))
                                model3d.triangles.add(Triangle3D(Vector3D(x2, stepY1, stepZ), Vector3D(x2, stepY2, stepZ), Vector3D(x2, stepY2, nextStepZ), stairColor, isStairsVisualOnly = true))
                                model3d.triangles.add(Triangle3D(Vector3D(x2, stepY1, stepZ), Vector3D(x2, stepY2, nextStepZ), Vector3D(x2, stepY1, nextStepZ), stairColor, isStairsVisualOnly = true))
                            }
                        }
                        
                        val endZ = baseZ + totalRaise
                        val slabTopStartZ = baseZ
                        val slabTopEndZ = endZ
                        val slabBottomStartZ = baseZ - slabThickness
                        val slabBottomEndZ = endZ - slabThickness
                        
                        if (isAlongX) {
                            val slabStartX = if (totalRaise >= 0) x1 else x2
                            val slabEndX = if (totalRaise >= 0) x2 else x1
                            
                            model3d.rects.add(Rect3D(Vector3D(slabStartX, y1, slabTopStartZ), Vector3D(slabEndX, y1, slabTopEndZ), Vector3D(slabEndX, y2, slabTopEndZ), Vector3D(slabStartX, y2, slabTopStartZ), slabColor, isStairsVisualOnly = true))
                            model3d.rects.add(Rect3D(Vector3D(slabStartX, y1, slabBottomStartZ), Vector3D(slabStartX, y2, slabBottomStartZ), Vector3D(slabEndX, y2, slabBottomEndZ), Vector3D(slabEndX, y1, slabBottomEndZ), slabColor, isStairsVisualOnly = true))
                            model3d.rects.add(Rect3D(Vector3D(slabStartX, y1, slabBottomStartZ), Vector3D(slabEndX, y1, slabBottomEndZ), Vector3D(slabEndX, y1, slabTopEndZ), Vector3D(slabStartX, y1, slabTopStartZ), slabColor, isStairsVisualOnly = true))
                            model3d.rects.add(Rect3D(Vector3D(slabStartX, y2, slabBottomStartZ), Vector3D(slabStartX, y2, slabTopStartZ), Vector3D(slabEndX, y2, slabTopEndZ), Vector3D(slabEndX, y2, slabBottomEndZ), slabColor, isStairsVisualOnly = true))
                            model3d.rects.add(Rect3D(Vector3D(slabStartX, y1, slabBottomStartZ), Vector3D(slabStartX, y1, slabTopStartZ), Vector3D(slabStartX, y2, slabTopStartZ), Vector3D(slabStartX, y2, slabBottomStartZ), slabColor, isStairsVisualOnly = true))
                            model3d.rects.add(Rect3D(Vector3D(slabEndX, y1, slabBottomEndZ), Vector3D(slabEndX, y2, slabBottomEndZ), Vector3D(slabEndX, y2, slabTopEndZ), Vector3D(slabEndX, y1, slabTopEndZ), slabColor, isStairsVisualOnly = true))
                        } else {
                            val slabStartY = if (totalRaise >= 0) y1 else y2
                            val slabEndY = if (totalRaise >= 0) y2 else y1
                            
                            model3d.rects.add(Rect3D(Vector3D(x1, slabStartY, slabTopStartZ), Vector3D(x2, slabStartY, slabTopStartZ), Vector3D(x2, slabEndY, slabTopEndZ), Vector3D(x1, slabEndY, slabTopEndZ), slabColor, isStairsVisualOnly = true))
                            model3d.rects.add(Rect3D(Vector3D(x1, slabStartY, slabBottomStartZ), Vector3D(x1, slabEndY, slabBottomEndZ), Vector3D(x2, slabEndY, slabBottomEndZ), Vector3D(x2, slabStartY, slabBottomStartZ), slabColor, isStairsVisualOnly = true))
                            model3d.rects.add(Rect3D(Vector3D(x1, slabStartY, slabBottomStartZ), Vector3D(x1, slabStartY, slabTopStartZ), Vector3D(x1, slabEndY, slabTopEndZ), Vector3D(x1, slabEndY, slabBottomEndZ), slabColor, isStairsVisualOnly = true))
                            model3d.rects.add(Rect3D(Vector3D(x2, slabStartY, slabBottomStartZ), Vector3D(x2, slabEndY, slabBottomEndZ), Vector3D(x2, slabEndY, slabTopEndZ), Vector3D(x2, slabStartY, slabTopStartZ), slabColor, isStairsVisualOnly = true))
                            model3d.rects.add(Rect3D(Vector3D(x1, slabStartY, slabBottomStartZ), Vector3D(x2, slabStartY, slabBottomStartZ), Vector3D(x2, slabStartY, slabTopStartZ), Vector3D(x1, slabStartY, slabTopStartZ), slabColor, isStairsVisualOnly = true))
                            model3d.rects.add(Rect3D(Vector3D(x1, slabEndY, slabBottomEndZ), Vector3D(x1, slabEndY, slabTopEndZ), Vector3D(x2, slabEndY, slabTopEndZ), Vector3D(x2, slabEndY, slabBottomEndZ), slabColor, isStairsVisualOnly = true))
                        }
                    }
                }
            }
            currentZ += floorHeight
        }

        var utilZ = 0.0
        for (floorEntry in floors) {
            addUtilityCylinders(model3d, floorEntry.floorDoc.elements, doc3d.kinds, utilZ, floorEntry.height.toDouble())
            utilZ += floorEntry.height.toDouble()
        }

        return model3d
    }

    internal fun repaintAllCanvases() {
        documents.forEach { it.canvas.repaint() }
        threeDDocuments.forEach { it.panel.repaint() }
        for (w in Window.getWindows()) {
            if (w is WallLayoutWindow) {
                w.canvas.repaint()
            }
        }
    }

    internal fun clearOtherSelections(exceptFloorPlan: FloorPlanDocument? = null, exceptWallLayout: WallLayoutDocument? = null) {
        documents.forEach { doc ->
            if (doc !== exceptFloorPlan && doc.selectedElement != null) {
                doc.selectedElement = null
                doc.canvas.repaint()
            }
        }
        for (w in Window.getWindows()) {
            if (w is WallLayoutWindow && w.doc !== exceptWallLayout && w.doc.selectedPoint != null) {
                w.doc.selectedPoint = null
                w.canvas.repaint()
            }
        }
    }

    private fun addUtilityCylinders(
        model3d: model.Model3D,
        floorElements: List<PlanElement>,
        kinds: List<WallLayoutKind>,
        currentZ: Double,
        floorHeight: Double
    ) {
        val allWalls = floorElements.filterIsInstance<Wall>()
        val openingElements = floorElements.filter { it is PlanWindow || it is Door }

        for (el in floorElements) {
            when (el) {
                is Wall -> {
                    val isVertical = el.width < el.height
                    val wallStart = if (isVertical) el.y.toDouble() else el.x.toDouble()
                    val wallEnd = if (isVertical) (el.y + el.height).toDouble() else (el.x + el.width).toDouble()
                    val wallBounds = el.getBounds()

                    // Filter openings to only those on this wall
                    val wallOpenings = openingElements.filter { wallBounds.contains(it.getBounds()) }
                    val openings = WallLayoutCanvas.buildOpeningsForWall(el, isVertical, wallOpenings)

                    fun waypoint3D(layoutX: Double, layoutZ: Double, isFront: Boolean): Vector3D {
                        return if (isVertical) {
                            val wallX = if (isFront) el.x.toDouble() else (el.x + el.width).toDouble()
                            Vector3D(wallX, layoutX, currentZ + layoutZ)
                        } else {
                            val wallY = if (isFront) el.y.toDouble() else (el.y + el.height).toDouble()
                            Vector3D(layoutX, wallY, currentZ + layoutZ)
                        }
                    }

                    fun addLayoutCylinders(layout: WallLayout, isFront: Boolean) {
                        val dockedPoints = WallLayoutCanvas.computeDockedPoints(el, isFront, allWalls)
                        val allPoints = layout.points + dockedPoints
                        val pointsByKind = allPoints.groupBy { it.kind }
                        for ((kindIdx, pts) in pointsByKind) {
                            if (pts.size < 2) continue
                            val kind = kinds.getOrNull(kindIdx) ?: continue
                            val radius = kind.diameter / 2.0
                            val color = kind.color
                            for ((p1, p2) in WallLayoutCanvas.computeManhattanMST(pts)) {
                                val waypoints = WallLayoutCanvas.computeOrthogonalPath(
                                    p1, p2, openings, wallStart, wallEnd, floorHeight)
                                for (i in 0 until waypoints.size - 1) {
                                    val (lx1, lz1) = waypoints[i]
                                    val (lx2, lz2) = waypoints[i + 1]
                                    if (kotlin.math.abs(lx1 - lx2) > 0.001 || kotlin.math.abs(lz1 - lz2) > 0.001) {
                                        model3d.cylinders.add(Cylinder3D(
                                            waypoint3D(lx1, lz1, isFront),
                                            waypoint3D(lx2, lz2, isFront),
                                            radius, color
                                        ))
                                    }
                                }
                            }
                        }
                        // Add 3D labels for named points
                        for (p in layout.points) {
                            if (p.name.isNotEmpty()) {
                                val pos = waypoint3D(p.x, p.z.toDouble(), isFront)
                                val color = kinds.getOrNull(p.kind)?.color ?: java.awt.Color.WHITE
                                model3d.labels.add(Label3D(pos, p.name, color, isVertical, isFront))
                            }
                        }
                    }

                    addLayoutCylinders(el.frontLayout, true)
                    addLayoutCylinders(el.backLayout, false)
                }
                is UtilitiesConnection -> {
                    val kind = kinds.getOrNull(el.kind) ?: continue
                    val radius = kind.diameter / 2.0
                    val color = kind.color
                    val startFP = el.startWall.getFloorPlanCoords(el.startPoint, el.startIsFront)
                    val endFP = el.endWall.getFloorPlanCoords(el.endPoint, el.endIsFront)
                    model3d.cylinders.add(Cylinder3D(
                        Vector3D(startFP.x, startFP.y, currentZ + el.startPoint.z),
                        Vector3D(endFP.x, endFP.y, currentZ + el.endPoint.z),
                        radius, color
                    ))
                }
                else -> {}
            }
        }
    }

    private fun showManageKindsDialog() {
        val doc = activeDocument ?: return
        val effectiveKinds = doc.effectiveKinds
        val houseDoc = doc.ambientHouseDoc
        val dialog = JDialog(activeWindow, "Manage Wall Layout Kinds", true)
        dialog.layout = BorderLayout()
        dialog.setSize(500, 350)
        dialog.setLocationRelativeTo(activeWindow)

        val model = object : DefaultTableModel(arrayOf("Name", "Color", "Diameter (cm)"), 0) {
            override fun getColumnClass(columnIndex: Int): Class<*> = if (columnIndex == 1) Color::class.java else String::class.java
        }
        for (kind in effectiveKinds) {
            model.addRow(arrayOf(kind.name, kind.color, kind.diameter.toString()))
        }
        val table = JTable(model)
        table.putClientProperty("terminateEditOnFocusLost", true)

        table.columnModel.getColumn(1).setCellRenderer(object : javax.swing.table.TableCellRenderer {
            override fun getTableCellRendererComponent(table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
                val panel = JPanel()
                if (value is Color) {
                    panel.background = value
                }
                if (isSelected) {
                    panel.border = BorderFactory.createLineBorder(table?.selectionForeground, 2)
                } else {
                    panel.border = BorderFactory.createLineBorder(Color.GRAY, 1)
                }
                return panel
            }
        })

        table.columnModel.getColumn(1).setCellEditor(object : javax.swing.AbstractCellEditor(), javax.swing.table.TableCellEditor {
            private var currentColor: Color? = null
            override fun getTableCellEditorComponent(table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int): Component {
                currentColor = value as? Color
                val panel = JPanel()
                panel.background = currentColor

                SwingUtilities.invokeLater {
                    val color = JColorChooser.showDialog(dialog, "Choose Kind Color", currentColor ?: Color.RED)
                    if (color != null) {
                        currentColor = color
                        stopCellEditing()
                    } else {
                        cancelCellEditing()
                    }
                }
                return panel
            }

            override fun getCellEditorValue(): Any? = currentColor
        })

        val scrollPane = JScrollPane(table)
        dialog.add(scrollPane, BorderLayout.CENTER)

        val topPanel = JPanel()
        val addBtn = JButton("Add Kind")
        addBtn.addActionListener {
            val color = JColorChooser.showDialog(dialog, "Choose Kind Color", Color.RED)
            if (color != null) {
                model.addRow(arrayOf("New Kind", color, "1.0"))
            }
        }
        val removeBtn = JButton("Remove Kind")
        removeBtn.addActionListener {
            if (table.selectedRow != -1) model.removeRow(table.selectedRow)
        }
        topPanel.add(addBtn)
        topPanel.add(removeBtn)
        dialog.add(topPanel, BorderLayout.NORTH)

        val bottomPanel = JPanel()
        val okBtn = JButton("OK")
        okBtn.addActionListener {
            if (table.isEditing) table.cellEditor.stopCellEditing()
            effectiveKinds.clear()
            for (i in 0 until model.rowCount) {
                val name = model.getValueAt(i, 0) as String
                val color = model.getValueAt(i, 1) as Color
                val diameter = model.getValueAt(i, 2).toString().toDoubleOrNull()?.coerceAtLeast(0.1) ?: 1.0
                effectiveKinds.add(WallLayoutKind(name, color, diameter))
            }
            if (houseDoc != null) {
                houseDoc.isModified = true
                houseDoc.window?.title = "3D House Model - ${houseDoc.currentFile?.name ?: "Untitled"}*"
            } else {
                doc.isModified = true
            }
            repaintAllCanvases()
            sidePanel.updateFieldsForActiveDocument()
            dialog.dispose()
        }
        val cancelBtn = JButton("Cancel")
        cancelBtn.addActionListener {
            dialog.dispose()
        }
        bottomPanel.add(okBtn)
        bottomPanel.add(cancelBtn)
        dialog.add(bottomPanel, BorderLayout.SOUTH)

        dialog.isVisible = true
    }

    /** Sum of all asset assignments with [assetName] on utility points of [kindIndex].
     *  If [contextDoc] is provided and is embedded in a house, scans all floors of that house.
     *  Otherwise scans all open documents. */
    internal fun computeSpentAssets(kindIndex: Int, assetName: String, contextDoc: FloorPlanDocument? = null): Int {
        var total = 0
        val docsToScan: List<FloorPlanDocument> = when {
            contextDoc?.ambientHouseDoc != null -> contextDoc.ambientHouseDoc!!.floors.map { it.floorDoc }
            contextDoc != null -> listOf(contextDoc)
            else -> documents.toList()
        }
        for (doc in docsToScan) {
            for (wall in doc.elements.filterIsInstance<Wall>()) {
                for (p in wall.frontLayout.points + wall.backLayout.points) {
                    if (p.kind == kindIndex) {
                        total += p.assets.filter { it.assetName == assetName }.sumOf { it.quantity }
                    }
                }
            }
        }
        return total
    }

    /** Open the modal Asset Manager dialog. */
    private fun showAssetManagerDialog() {
        // ── Resolve data source once at open time ────────────────────────
        // Priority: active 3D window > embedded floor's house doc > standalone floor doc.
        val doc3d: ThreeDDocument? = when {
            activeWindow is ThreeDWindow -> (activeWindow as ThreeDWindow).doc
            activeDocument?.ambientHouseDoc != null -> activeDocument!!.ambientHouseDoc
            else -> null
        }
        val standaloneDoc: FloorPlanDocument? = if (doc3d == null) activeDocument else null

        if (doc3d == null && standaloneDoc == null) {
            JOptionPane.showMessageDialog(activeWindow, "No document is open.", "Asset Manager", JOptionPane.INFORMATION_MESSAGE)
            return
        }

        val sourceKinds: MutableList<WallLayoutKind> = doc3d?.kinds ?: standaloneDoc!!.kinds
        val sourceAssetDefs: MutableMap<Int, MutableList<AssetDefinition>> =
            doc3d?.assetDefinitions ?: standaloneDoc!!.assetDefinitions
        val sourceName: String = when {
            doc3d != null -> doc3d.currentFile?.name ?: "New House"
            else -> standaloneDoc!!.currentFile?.name ?: "Untitled"
        }
        // Docs to scan when computing "Spent" totals.
        val floorDocsToScan: List<FloorPlanDocument> =
            doc3d?.floors?.map { it.floorDoc } ?: documents

        fun computeSpent(kindIdx: Int, assetName: String): Int {
            var total = 0
            for (fpDoc in floorDocsToScan) {
                for (wall in fpDoc.elements.filterIsInstance<Wall>()) {
                    for (p in wall.frontLayout.points + wall.backLayout.points) {
                        if (p.kind == kindIdx)
                            total += p.assets.filter { it.assetName == assetName }.sumOf { it.quantity }
                    }
                }
            }
            return total
        }

        fun markModified() {
            if (doc3d != null) {
                doc3d.isModified = true
            } else {
                standaloneDoc!!.isModified = true
                standaloneDoc.window?.updateTitle()
            }
        }

        // ── Dialog setup ─────────────────────────────────────────────────
        val dialog = JDialog(activeWindow, "Asset Manager", true)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.setSize(720, 520)
        dialog.isResizable = true
        dialog.layout = BorderLayout()

        // ── kind selector ────────────────────────────────────────────────
        val kindComboBox = JComboBox<String>()
        val docLabel = JLabel(sourceName)

        val tableModel = object : DefaultTableModel(
            arrayOf("Asset name", "Asset limit", "Asset physical width (cm)",
                    "Asset physical height (cm)", "Spent assets"), 0
        ) {
            override fun isCellEditable(row: Int, column: Int) = column != 4
        }

        val table = JTable(tableModel)
        table.putClientProperty("terminateEditOnFocusLost", true)

        // Red text for spent > limit in the Spent column
        table.columnModel.getColumn(4).cellRenderer = object : javax.swing.table.DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                tbl: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
            ): java.awt.Component {
                val comp = super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col)
                if (!isSelected) {
                    val spent = value?.toString()?.toIntOrNull() ?: 0
                    val limit = tbl?.getValueAt(row, 1)?.toString()?.toIntOrNull() ?: 0
                    comp.foreground = if (spent > limit) Color.RED else tbl?.foreground
                } else {
                    comp.foreground = tbl?.selectionForeground
                }
                return comp
            }
        }

        // ── helpers ──────────────────────────────────────────────────────
        var suppressTableListener = false

        fun refreshKinds() {
            suppressTableListener = true
            val sel = kindComboBox.selectedItem
            kindComboBox.removeAllItems()
            sourceKinds.forEach { kindComboBox.addItem(it.name) }
            if (sel != null && kindComboBox.itemCount > 0) kindComboBox.selectedItem = sel
            suppressTableListener = false
        }

        fun refreshTable() {
            val kindIdx = kindComboBox.selectedIndex
            if (kindIdx < 0) { tableModel.setRowCount(0); return }
            suppressTableListener = true
            tableModel.setRowCount(0)
            val defs = sourceAssetDefs.getOrDefault(kindIdx, mutableListOf())
            for (def in defs) {
                tableModel.addRow(arrayOf(def.name, def.limit, def.physicalWidth, def.physicalHeight,
                    computeSpent(kindIdx, def.name)))
            }
            suppressTableListener = false
        }

        // Commit any in-progress cell edit WITHOUT triggering the model listener path.
        // Must NOT be called from within a TableModelListener callback.
        fun commitEdit() {
            if (table.isEditing) table.cellEditor?.stopCellEditing()
        }

        fun applyTableToDoc() {
            // NOTE: do NOT call commitEdit() here – this function is invoked from the
            // TableModelListener which fires synchronously inside editingStopped→setValueAt,
            // before removeEditor() runs. Calling stopCellEditing() here would recurse
            // back into editingStopped() and cause a StackOverflowError on the EDT.
            val kindIdx = kindComboBox.selectedIndex
            if (kindIdx < 0) return
            val defs = mutableListOf<AssetDefinition>()
            for (i in 0 until tableModel.rowCount) {
                val name = tableModel.getValueAt(i, 0)?.toString() ?: continue
                val limit = tableModel.getValueAt(i, 1)?.toString()?.toIntOrNull() ?: 0
                val w = tableModel.getValueAt(i, 2)?.toString()?.toDoubleOrNull() ?: 0.0
                val h = tableModel.getValueAt(i, 3)?.toString()?.toDoubleOrNull() ?: 0.0
                defs.add(AssetDefinition(name, limit, w, h))
            }
            sourceAssetDefs[kindIdx] = defs
            markModified()
        }

        // Apply current edits + refresh spent column
        fun refreshSpent() {
            commitEdit()
            applyTableToDoc()
            val kindIdx = kindComboBox.selectedIndex
            if (kindIdx < 0) return
            suppressTableListener = true
            for (i in 0 until tableModel.rowCount) {
                val name = tableModel.getValueAt(i, 0)?.toString() ?: continue
                tableModel.setValueAt(computeSpent(kindIdx, name), i, 4)
            }
            suppressTableListener = false
        }

        // ── kind combo listener ──────────────────────────────────────────
        // Guard with suppressTableListener so that programmatic combo changes
        // (e.g. inside refreshKinds) never call applyTableToDoc() on an empty table
        // and wipe the model before refreshTable() has a chance to populate it.
        kindComboBox.addActionListener {
            if (!suppressTableListener) {
                commitEdit()
                applyTableToDoc()
                refreshTable()
            }
        }

        // ── table model listener: persist edits live ─────────────────────
        // Fires inside setValueAt() (before removeEditor()), so we must NOT call
        // stopCellEditing() here – applyTableToDoc() only reads the model, which is safe.
        tableModel.addTableModelListener { e ->
            if (!suppressTableListener && e.type == javax.swing.event.TableModelEvent.UPDATE && e.column in 0..3) {
                applyTableToDoc()
            }
        }

        // ── Enter key: commit current cell edit without jumping into next row ─
        val enterKey = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(enterKey, "commitAndDeselect")
        table.actionMap.put("commitAndDeselect", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                // stopCellEditing is safe here: we are NOT inside a model listener callback
                commitEdit()
                table.clearSelection()
            }
        })

        // ── layout ──────────────────────────────────────────────────────
        val topPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        topPanel.add(JLabel("Document:"))
        topPanel.add(docLabel)
        topPanel.add(JLabel("  Kind:"))
        topPanel.add(kindComboBox)
        val refreshBtn = JButton("Refresh")
        refreshBtn.addActionListener { commitEdit(); refreshKinds(); refreshTable() }
        val refreshSpentBtn = JButton("Refresh Spent")
        refreshSpentBtn.addActionListener { refreshSpent() }
        topPanel.add(refreshBtn)
        topPanel.add(refreshSpentBtn)

        val addRemovePanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        val addBtn = JButton("Add Asset")
        addBtn.addActionListener {
            commitEdit()
            tableModel.addRow(arrayOf("New Asset", 0, 0.0, 0.0, 0))
            applyTableToDoc()
        }
        val removeBtn = JButton("Remove Asset")
        removeBtn.addActionListener {
            commitEdit()
            val row = table.selectedRow
            if (row >= 0) { tableModel.removeRow(row); applyTableToDoc() }
        }
        addRemovePanel.add(addBtn)
        addRemovePanel.add(removeBtn)

        val okBtn = JButton("OK")
        okBtn.addActionListener { commitEdit(); applyTableToDoc(); dialog.dispose() }

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(addRemovePanel, BorderLayout.WEST)
        val okPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT))
        okPanel.add(okBtn)
        bottomPanel.add(okPanel, BorderLayout.EAST)

        dialog.add(topPanel, BorderLayout.NORTH)
        dialog.add(JScrollPane(table), BorderLayout.CENTER)
        dialog.add(bottomPanel, BorderLayout.SOUTH)

        dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) { commitEdit(); applyTableToDoc() }
        })

        refreshKinds()
        refreshTable()
        dialog.setLocationRelativeTo(activeWindow)
        dialog.isVisible = true
    }

    /** Modal "Modify Assets" dialog for a single WallLayoutPoint. */
    internal fun showModifyAssetsDialog(
        point: WallLayoutPoint,
        kindIndex: Int,
        floorPlanDoc: FloorPlanDocument,
        parentWindow: JFrame?
    ) {
        val kindName = floorPlanDoc.effectiveKinds.getOrNull(kindIndex)?.name ?: "Kind $kindIndex"
        val dialog = JDialog(parentWindow, "Modify Assets – $kindName", true)
        dialog.layout = BorderLayout()
        dialog.setSize(420, 340)
        dialog.setLocationRelativeTo(parentWindow)

        val defs = floorPlanDoc.effectiveAssetDefinitions.getOrDefault(kindIndex, mutableListOf())

        val tableModel = object : DefaultTableModel(arrayOf("Asset name", "Quantity"), 0) {
            override fun isCellEditable(row: Int, column: Int) = true
        }
        for (a in point.assets) tableModel.addRow(arrayOf(a.assetName, a.quantity))

        val table = JTable(tableModel)
        table.putClientProperty("terminateEditOnFocusLost", true)

        // Asset-name column: combo box with known asset names for this kind
        if (defs.isNotEmpty()) {
            val nameCombo = JComboBox(defs.map { it.name }.toTypedArray())
            table.columnModel.getColumn(0).cellEditor = DefaultCellEditor(nameCombo)
        }

        val btnPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT))
        val addBtn = JButton("Add")
        addBtn.addActionListener {
            val defaultName = defs.firstOrNull()?.name ?: ""
            tableModel.addRow(arrayOf(defaultName, 1))
        }
        val removeBtn = JButton("Remove")
        removeBtn.addActionListener { if (table.selectedRow >= 0) tableModel.removeRow(table.selectedRow) }
        btnPanel.add(addBtn)
        btnPanel.add(removeBtn)

        val okCancelPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT))
        val okBtn = JButton("OK")
        okBtn.addActionListener {
            if (table.isEditing) table.cellEditor?.stopCellEditing()
            point.assets.clear()
            for (i in 0 until tableModel.rowCount) {
                val name = tableModel.getValueAt(i, 0)?.toString() ?: continue
                val qty = tableModel.getValueAt(i, 1)?.toString()?.toIntOrNull() ?: 0
                if (name.isNotEmpty() && qty > 0) point.assets.add(AssetAssignment(name, qty))
            }
            floorPlanDoc.isModified = true
            (floorPlanDoc.window as? EditorWindow)?.updateTitle()
            floorPlanDoc.ambientHouseDoc?.let { houseDoc ->
                houseDoc.isModified = true
                houseDoc.window?.title = "3D House Model - ${houseDoc.currentFile?.name ?: "Untitled"}*"
            }
            repaintAllCanvases()
            dialog.dispose()
        }
        val cancelBtn = JButton("Cancel")
        cancelBtn.addActionListener { dialog.dispose() }
        okCancelPanel.add(okBtn)
        okCancelPanel.add(cancelBtn)

        val southPanel = JPanel(BorderLayout())
        southPanel.add(btnPanel, BorderLayout.WEST)
        southPanel.add(okCancelPanel, BorderLayout.EAST)

        dialog.add(JScrollPane(table), BorderLayout.CENTER)
        dialog.add(southPanel, BorderLayout.SOUTH)
        dialog.isVisible = true
    }

    internal fun updateUndoRedoStates() {
        activeDocument?.let { doc ->
            for (undoMenuItem in undoMenuItems) {
                undoMenuItem.isEnabled = doc.undoStack.isNotEmpty()
                undoMenuItem.isVisible = true
            }
            for (redoMenuItem in redoMenuItems) {
                redoMenuItem.isEnabled = doc.redoStack.isNotEmpty()
                redoMenuItem.isVisible = true
            }
        }
        for (menuBar in menuBars) {
            menuBar.revalidate()
            menuBar.repaint()
        }
    }

    private fun save3D(doc: ThreeDDocument) {
        val file = doc.currentFile
        if (file == null) {
            saveAs3D(doc)
        } else {
            performSave3D(doc, file)
        }
    }

    private fun saveAs3D(doc: ThreeDDocument) {
        val chooser = JFileChooser()
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            if (!file.name.endsWith(".xml")) {
                file = File(file.absolutePath + ".xml")
            }
            performSave3D(doc, file)
        }
    }

    private fun saveFloorElementsToNode(floorDoc: FloorPlanDocument, xmlDoc: org.w3c.dom.Document, parentNode: org.w3c.dom.Element) {
        for (el in floorDoc.elements) {
            val elNode = xmlDoc.createElement("Element")
            elNode.setAttribute("type", el.type.name)
            if (el is PolygonRoom) {
                val verticesStr = el.vertices.joinToString(";") { "${it.x},${it.y}" }
                elNode.setAttribute("vertices", verticesStr)
                elNode.setAttribute("floorThickness", el.floorThickness.toString())
                elNode.setAttribute("zOffset", el.zOffset.toString())
            } else {
                elNode.setAttribute("x", el.x.toString())
                elNode.setAttribute("y", el.y.toString())
                elNode.setAttribute("width", el.width.toString())
                elNode.setAttribute("height", el.height.toString())
                if (el is Wall) {
                    fun addLayout(layout: WallLayout, tagName: String) {
                        val layoutNode = xmlDoc.createElement(tagName)
                        for (p in layout.points) {
                            val pNode = xmlDoc.createElement("Point")
                            pNode.setAttribute("x", p.x.toString())
                            pNode.setAttribute("z", p.z.toString())
                            pNode.setAttribute("kind", p.kind.toString())
                            if (p.name.isNotEmpty()) pNode.setAttribute("name", p.name)
                            for (a in p.assets) {
                                val aNode = xmlDoc.createElement("Assignment")
                                aNode.setAttribute("assetName", a.assetName)
                                aNode.setAttribute("quantity", a.quantity.toString())
                                pNode.appendChild(aNode)
                            }
                            layoutNode.appendChild(pNode)
                        }
                        elNode.appendChild(layoutNode)
                    }
                    addLayout(el.frontLayout, "FrontLayout")
                    addLayout(el.backLayout, "BackLayout")
                }
                if (el is PlanWindow) {
                    elNode.setAttribute("height3D", el.height3D.toString())
                    elNode.setAttribute("aboveFloorHeight", el.sillElevation.toString())
                    elNode.setAttribute("windowPosition", el.windowPosition.name)
                }
                if (el is Door) elNode.setAttribute("height3D", el.verticalHeight.toString())
                if (el is Room) {
                    elNode.setAttribute("floorThickness", el.floorThickness.toString())
                    elNode.setAttribute("zOffset", el.zOffset.toString())
                }
                if (el is Stairs) {
                    elNode.setAttribute("directionAlongX", el.directionAlongX.toString())
                    elNode.setAttribute("totalRaise", el.totalRaise.toString())
                    elNode.setAttribute("zOffset", el.zOffset.toString())
                }
                if (el is UtilitiesConnection) {
                    elNode.setAttribute("startWallIndex", floorDoc.elements.indexOf(el.startWall).toString())
                    elNode.setAttribute("startIsFront", el.startIsFront.toString())
                    elNode.setAttribute("startPointIndex", (if (el.startIsFront) el.startWall.frontLayout else el.startWall.backLayout).points.indexOf(el.startPoint).toString())
                    elNode.setAttribute("endWallIndex", floorDoc.elements.indexOf(el.endWall).toString())
                    elNode.setAttribute("endIsFront", el.endIsFront.toString())
                    elNode.setAttribute("endPointIndex", (if (el.endIsFront) el.endWall.frontLayout else el.endWall.backLayout).points.indexOf(el.endPoint).toString())
                    elNode.setAttribute("kind", el.kind.toString())
                }
            }
            parentNode.appendChild(elNode)
        }
    }

    private fun performSave3D(doc: ThreeDDocument, file: File) {
        try {
            val docFactory = DocumentBuilderFactory.newInstance()
            val docBuilder = docFactory.newDocumentBuilder()
            val xmlDoc = docBuilder.newDocument()

            val rootElement = xmlDoc.createElement("ThreeDModel")
            xmlDoc.appendChild(rootElement)

            // Save house-level kinds
            for (kind in doc.kinds) {
                val kNode = xmlDoc.createElement("Kind")
                kNode.setAttribute("name", kind.name)
                kNode.setAttribute("color", kind.color.rgb.toString())
                kNode.setAttribute("diameter", kind.diameter.toString())
                rootElement.appendChild(kNode)
            }

            // Save house-level asset definitions
            if (doc.assetDefinitions.isNotEmpty()) {
                val assetDefsNode = xmlDoc.createElement("AssetDefinitions")
                for ((kindIdx, defs) in doc.assetDefinitions) {
                    if (defs.isEmpty()) continue
                    val kindAssetsNode = xmlDoc.createElement("KindAssets")
                    kindAssetsNode.setAttribute("kind", kindIdx.toString())
                    for (def in defs) {
                        val defNode = xmlDoc.createElement("AssetDef")
                        defNode.setAttribute("name", def.name)
                        defNode.setAttribute("limit", def.limit.toString())
                        defNode.setAttribute("width", def.physicalWidth.toString())
                        defNode.setAttribute("height", def.physicalHeight.toString())
                        kindAssetsNode.appendChild(defNode)
                    }
                    assetDefsNode.appendChild(kindAssetsNode)
                }
                if (assetDefsNode.hasChildNodes()) rootElement.appendChild(assetDefsNode)
            }

            // Save each floor with embedded elements
            for (floor in doc.floors) {
                val floorNode = xmlDoc.createElement("Floor")
                floorNode.setAttribute("name", floor.name)
                floorNode.setAttribute("height", floor.height.toString())
                floorNode.setAttribute("draw", floor.draw.toString())
                saveFloorElementsToNode(floor.floorDoc, xmlDoc, floorNode)
                rootElement.appendChild(floorNode)
            }

            val transformerFactory = TransformerFactory.newInstance()
            val transformer = transformerFactory.newTransformer()
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            val source = DOMSource(xmlDoc)
            val result = StreamResult(file)
            transformer.transform(source, result)

            doc.currentFile = file
            doc.isModified = false
            doc.window?.title = "3D House Model - ${file.name}"
            // Mark all embedded floor docs as clean
            for (floor in doc.floors) {
                floor.floorDoc.isModified = false
                floor.floorDoc.window?.updateTitle()
            }
            addToRecent(file)
        } catch (e: Exception) {
            e.printStackTrace()
            JOptionPane.showMessageDialog(null, "Error saving 3D XML: ${e.message}")
        }
    }


    private fun save() {
        activeDocument?.let { doc ->
            val houseDoc = doc.ambientHouseDoc
            if (houseDoc != null) {
                save3D(houseDoc)
                doc.isModified = false
                doc.window?.updateTitle()
                return@let
            }
            val file = doc.currentFile
            if (file == null) saveAs() else performSave(file)
        }
        activeWindow?.let { win ->
            if (win is ThreeDWindow) save3D(win.doc)
        }
    }

    private fun saveAs() {
        activeDocument?.let { doc ->
            val houseDoc = doc.ambientHouseDoc
            if (houseDoc != null) {
                saveAs3D(houseDoc)
                doc.isModified = false
                doc.window?.updateTitle()
                return@let
            }
            val chooser = JFileChooser()
            if (chooser.showSaveDialog(activeWindow) == JFileChooser.APPROVE_OPTION) {
                var file = chooser.selectedFile
                if (!file.name.endsWith(".xml")) file = File(file.absolutePath + ".xml")
                performSave(file)
            }
        }
        activeWindow?.let { win ->
            if (win is ThreeDWindow) saveAs3D(win.doc)
        }
    }

    /** Read `<AssetDefinitions>` from [xmlDoc] and populate [doc.assetDefinitions]. */
    private fun loadAssetDefinitions(xmlDoc: org.w3c.dom.Document, doc: FloorPlanDocument) {
        val topNodes = xmlDoc.getElementsByTagName("AssetDefinitions")
        if (topNodes.length == 0) return
        val topEl = topNodes.item(0) as Element
        val kindAssetsNodes = topEl.getElementsByTagName("KindAssets")
        for (i in 0 until kindAssetsNodes.length) {
            val kaEl = kindAssetsNodes.item(i) as Element
            val kindIdx = kaEl.getAttribute("kind").toIntOrNull() ?: continue
            val defs = mutableListOf<AssetDefinition>()
            val defNodes = kaEl.getElementsByTagName("AssetDef")
            for (j in 0 until defNodes.length) {
                val defEl = defNodes.item(j) as Element
                val name = defEl.getAttribute("name")
                val limit = defEl.getAttribute("limit").toIntOrNull() ?: 0
                val w = defEl.getAttribute("width").toDoubleOrNull() ?: 0.0
                val h = defEl.getAttribute("height").toDoubleOrNull() ?: 0.0
                defs.add(AssetDefinition(name, limit, w, h))
            }
            if (defs.isNotEmpty()) doc.assetDefinitions[kindIdx] = defs
        }
    }

    private fun performSave(file: File) {
        activeDocument?.let { doc ->
            try {
                val docFactory = DocumentBuilderFactory.newInstance()
                val docBuilder = docFactory.newDocumentBuilder()
                val xmlDoc = docBuilder.newDocument()
                
                val rootElement = xmlDoc.createElement("FloorPlan")
                xmlDoc.appendChild(rootElement)

                for (kind in doc.kinds) {
                    val kNode = xmlDoc.createElement("Kind")
                    kNode.setAttribute("name", kind.name)
                    kNode.setAttribute("color", kind.color.rgb.toString())
                    kNode.setAttribute("diameter", kind.diameter.toString())
                    rootElement.appendChild(kNode)
                }

                // Save asset definitions per kind
                if (doc.assetDefinitions.isNotEmpty()) {
                    val assetDefsNode = xmlDoc.createElement("AssetDefinitions")
                    for ((kindIdx, defs) in doc.assetDefinitions) {
                        if (defs.isEmpty()) continue
                        val kindAssetsNode = xmlDoc.createElement("KindAssets")
                        kindAssetsNode.setAttribute("kind", kindIdx.toString())
                        for (def in defs) {
                            val defNode = xmlDoc.createElement("AssetDef")
                            defNode.setAttribute("name", def.name)
                            defNode.setAttribute("limit", def.limit.toString())
                            defNode.setAttribute("width", def.physicalWidth.toString())
                            defNode.setAttribute("height", def.physicalHeight.toString())
                            kindAssetsNode.appendChild(defNode)
                        }
                        assetDefsNode.appendChild(kindAssetsNode)
                    }
                    if (assetDefsNode.hasChildNodes()) rootElement.appendChild(assetDefsNode)
                }

                for (el in doc.elements) {
                    val elNode = xmlDoc.createElement("Element")
                    elNode.setAttribute("type", el.type.name)
                    if (el is PolygonRoom) {
                        val verticesStr = el.vertices.joinToString(";") { "${it.x},${it.y}" }
                        elNode.setAttribute("vertices", verticesStr)
                        elNode.setAttribute("floorThickness", el.floorThickness.toString())
                        elNode.setAttribute("zOffset", el.zOffset.toString())
                    } else {
                        elNode.setAttribute("x", el.x.toString())
                        elNode.setAttribute("y", el.y.toString())
                        elNode.setAttribute("width", el.width.toString())
                        elNode.setAttribute("height", el.height.toString())
                        if (el is Wall) {
                            fun addLayout(layout: WallLayout, tagName: String) {
                                val layoutNode = xmlDoc.createElement(tagName)
                                for (p in layout.points) {
                                    val pNode = xmlDoc.createElement("Point")
                                    pNode.setAttribute("x", p.x.toString())
                                    pNode.setAttribute("z", p.z.toString())
                                    pNode.setAttribute("kind", p.kind.toString())
                                    if (p.name.isNotEmpty()) pNode.setAttribute("name", p.name)
                                    // Persist asset assignments
                                    for (a in p.assets) {
                                        val aNode = xmlDoc.createElement("Assignment")
                                        aNode.setAttribute("assetName", a.assetName)
                                        aNode.setAttribute("quantity", a.quantity.toString())
                                        pNode.appendChild(aNode)
                                    }
                                    layoutNode.appendChild(pNode)
                                }
                                elNode.appendChild(layoutNode)
                            }
                            addLayout(el.frontLayout, "FrontLayout")
                            addLayout(el.backLayout, "BackLayout")
                        }
                        if (el is PlanWindow) {
                            elNode.setAttribute("height3D", el.height3D.toString())
                            elNode.setAttribute("aboveFloorHeight", el.sillElevation.toString())
                            elNode.setAttribute("windowPosition", el.windowPosition.name)
                        }
                        if (el is Door) elNode.setAttribute("height3D", el.verticalHeight.toString())
                        if (el is Room) {
                            elNode.setAttribute("floorThickness", el.floorThickness.toString())
                            elNode.setAttribute("zOffset", el.zOffset.toString())
                        }
                        if (el is Stairs) {
                            elNode.setAttribute("directionAlongX", el.directionAlongX.toString())
                            elNode.setAttribute("totalRaise", el.totalRaise.toString())
                            elNode.setAttribute("zOffset", el.zOffset.toString())
                        }
                        if (el is UtilitiesConnection) {
                            elNode.setAttribute("startWallIndex", doc.elements.indexOf(el.startWall).toString())
                            elNode.setAttribute("startIsFront", el.startIsFront.toString())
                            elNode.setAttribute("startPointIndex", (if (el.startIsFront) el.startWall.frontLayout else el.startWall.backLayout).points.indexOf(el.startPoint).toString())
                            elNode.setAttribute("endWallIndex", doc.elements.indexOf(el.endWall).toString())
                            elNode.setAttribute("endIsFront", el.endIsFront.toString())
                            elNode.setAttribute("endPointIndex", (if (el.endIsFront) el.endWall.frontLayout else el.endWall.backLayout).points.indexOf(el.endPoint).toString())
                            elNode.setAttribute("kind", el.kind.toString())
                        }
                    }
                    rootElement.appendChild(elNode)
                }
                
                val transformerFactory = TransformerFactory.newInstance()
                val transformer = transformerFactory.newTransformer()
                transformer.setOutputProperty(OutputKeys.INDENT, "yes")
                val source = DOMSource(xmlDoc)
                val result = StreamResult(file)
                
                transformer.transform(source, result)
                doc.currentFile = file
                doc.isModified = false
                (activeWindow as? EditorWindow)?.updateTitle()
                addToRecent(file)
                if (!openFiles.contains(file.absolutePath)) {
                    openFiles.add(file.absolutePath)
                }
                saveSettings()
            } catch (e: Exception) {
                e.printStackTrace()
                JOptionPane.showMessageDialog(activeWindow, "Error saving: ${e.message}")
            }
        }
    }

    private fun openFromXML() {
        val chooser = JFileChooser()
        chooser.isMultiSelectionEnabled = true
        lastDirectory?.let { chooser.currentDirectory = File(it) }
        if (chooser.showOpenDialog(activeWindow) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFiles.forEach { openFile(it) }
        }
    }

    /** Parse plan elements from all `<Element>` children within a given DOM element (e.g. a `<Floor>` node). */
    private fun parseFloorElements(scope: Element): List<PlanElement> {
        val nList = scope.getElementsByTagName("Element")
        return parseElementNodeList(nList)
    }

    private fun parseFloorElements(xmlDoc: org.w3c.dom.Document): List<PlanElement> {
        val nList = xmlDoc.getElementsByTagName("Element")
        return parseElementNodeList(nList)
    }

    private fun parseElementNodeList(nList: org.w3c.dom.NodeList): List<PlanElement> {
        val newElements = arrayOfNulls<PlanElement>(nList.length)
        data class DeferredConn(val index: Int, val swi: Int, val sif: Boolean, val spi: Int, val ewi: Int, val eif: Boolean, val epi: Int, val kind: Int)
        val deferred = mutableListOf<DeferredConn>()
        
        for (i in 0 until nList.length) {
            val node = nList.item(i)
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val eElement = node as Element
                val type = ElementType.valueOf(eElement.getAttribute("type"))

                if (type == ElementType.UTILITIES_CONNECTION) {
                    val swi = eElement.getAttribute("startWallIndex").toInt()
                    val sif = eElement.getAttribute("startIsFront").toBoolean()
                    val spi = eElement.getAttribute("startPointIndex").toInt()
                    val ewi = eElement.getAttribute("endWallIndex").toInt()
                    val eif = eElement.getAttribute("endIsFront").toBoolean()
                    val epi = eElement.getAttribute("endPointIndex").toInt()
                    val kind = eElement.getAttribute("kind").toInt()
                    deferred.add(DeferredConn(i, swi, sif, spi, ewi, eif, epi, kind))
                    continue
                }

                val el = when (type) {
                    ElementType.WALL -> {
                        val x = eElement.getAttribute("x").toInt()
                        val y = eElement.getAttribute("y").toInt()
                        val w = eElement.getAttribute("width").toInt()
                        val h = eElement.getAttribute("height").toInt()
                        val wall = Wall(x, y, w, h)
                        
                        fun parseLayout(layout: WallLayout, tagName: String) {
                            val layoutNodes = eElement.getElementsByTagName(tagName)
                            if (layoutNodes.length > 0) {
                                val layoutElement = layoutNodes.item(0) as Element
                                val pointNodes = layoutElement.getElementsByTagName("Point")
                                for (j in 0 until pointNodes.length) {
                                    val pElement = pointNodes.item(j) as Element
                                    val px = pElement.getAttribute("x").toDouble()
                                    val pz = pElement.getAttribute("z").toDouble().roundToInt()
                                    val pk = pElement.getAttribute("kind").toInt()
                                    val pn = if (pElement.hasAttribute("name")) pElement.getAttribute("name") else ""
                                    val point = WallLayoutPoint(px, pz, pk, pn)
                                    // Load asset assignments
                                    val assignNodes = pElement.getElementsByTagName("Assignment")
                                    for (k in 0 until assignNodes.length) {
                                        val aEl = assignNodes.item(k) as Element
                                        val aName = aEl.getAttribute("assetName")
                                        val qty = aEl.getAttribute("quantity").toIntOrNull() ?: 0
                                        if (aName.isNotEmpty()) point.assets.add(AssetAssignment(aName, qty))
                                    }
                                    layout.points.add(point)
                                }
                                layout.isAbsolute = true
                            }
                        }
                        parseLayout(wall.frontLayout, "FrontLayout")
                        parseLayout(wall.backLayout, "BackLayout")
                        wall
                    }
                    ElementType.ROOM -> {
                        val x = eElement.getAttribute("x").toInt()
                        val y = eElement.getAttribute("y").toInt()
                        val w = eElement.getAttribute("width").toInt()
                        val h = eElement.getAttribute("height").toInt()
                        val ft = if (eElement.hasAttribute("floorThickness")) eElement.getAttribute("floorThickness").toInt() else 15
                        val zOff = if (eElement.hasAttribute("zOffset")) eElement.getAttribute("zOffset").toInt() else 0
                        Room(x, y, w, h, ft, zOff)
                    }
                    ElementType.WINDOW -> {
                        val x = eElement.getAttribute("x").toInt()
                        val y = eElement.getAttribute("y").toInt()
                        val w = eElement.getAttribute("width").toInt()
                        val h = eElement.getAttribute("height").toInt()
                        val h3d = if (eElement.hasAttribute("height3D")) eElement.getAttribute("height3D").toInt() else 150
                        val afh = if (eElement.hasAttribute("aboveFloorHeight")) eElement.getAttribute("aboveFloorHeight").toInt() else 90
                        val wp = if (eElement.hasAttribute("windowPosition")) {
                            WindowPosition.valueOf(eElement.getAttribute("windowPosition"))
                        } else WindowPosition.XY
                        PlanWindow(x, y, w, h, h3d, afh, wp)
                    }
                    ElementType.DOOR -> {
                        val x = eElement.getAttribute("x").toInt()
                        val y = eElement.getAttribute("y").toInt()
                        val w = eElement.getAttribute("width").toInt()
                        val h = eElement.getAttribute("height").toInt()
                        val h3d = if (eElement.hasAttribute("height3D")) eElement.getAttribute("height3D").toInt() else 210
                        Door(x, y, w, h, h3d)
                    }
                    ElementType.STAIRS -> {
                        val x = eElement.getAttribute("x").toInt()
                        val y = eElement.getAttribute("y").toInt()
                        val w = eElement.getAttribute("width").toInt()
                        val h = eElement.getAttribute("height").toInt()
                        val dirX = if (eElement.hasAttribute("directionAlongX")) eElement.getAttribute("directionAlongX").toBoolean() else true
                        val totRaise = if (eElement.hasAttribute("totalRaise")) eElement.getAttribute("totalRaise").toInt() else 0
                        val zOff = if (eElement.hasAttribute("zOffset")) eElement.getAttribute("zOffset").toInt() else 0
                        Stairs(x, y, w, h, dirX, totRaise, zOff)
                    }
                    ElementType.POLYGON_ROOM -> {
                        val vertices = mutableListOf<Point>()
                        val verticesStr = eElement.getAttribute("vertices")
                        if (verticesStr.isNotEmpty()) {
                            verticesStr.split(";").forEach {
                                val pts = it.split(",")
                                if (pts.size == 2) {
                                    vertices.add(Point(pts[0].toInt(), pts[1].toInt()))
                                }
                            }
                        }
                        val ft = if (eElement.hasAttribute("floorThickness")) eElement.getAttribute("floorThickness").toInt() else 15
                        val zOff = if (eElement.hasAttribute("zOffset")) eElement.getAttribute("zOffset").toInt() else 0
                        val pr = PolygonRoom(vertices, ft, zOff)
                        pr.updateBounds()
                        pr
                    }
                    ElementType.UTILITIES_CONNECTION -> throw IllegalStateException("Should have been handled above")
                }
                newElements[i] = el
            }
        }
        
        for (d in deferred) {
            val startWall = newElements[d.swi] as Wall
            val startPoint = (if (d.sif) startWall.frontLayout else startWall.backLayout).points[d.spi]
            val endWall = newElements[d.ewi] as Wall
            val endPoint = (if (d.eif) endWall.frontLayout else endWall.backLayout).points[d.epi]
            newElements[d.index] = UtilitiesConnection(startPoint, startWall, d.sif, endPoint, endWall, d.eif, d.kind)
        }
        
        return newElements.filterNotNull()
    }

    private fun openFile(file: File, savedState: WindowState? = null) {
        try {
            val docFactory = DocumentBuilderFactory.newInstance()
            val docBuilder = docFactory.newDocumentBuilder()
            val xmlDoc = docBuilder.parse(file)
            xmlDoc.documentElement.normalize()

            if (xmlDoc.documentElement.nodeName == "ThreeDModel") {
                val doc3d = ThreeDDocument(this)

                // Load house-level kinds from root
                val kindNodes = xmlDoc.getElementsByTagName("Kind")
                if (kindNodes.length > 0) {
                    doc3d.kinds.clear()
                    for (ki in 0 until kindNodes.length) {
                        val ke = kindNodes.item(ki) as Element
                        // Only top-level Kind elements (direct children of root)
                        if (ke.parentNode == xmlDoc.documentElement) {
                            val kName = ke.getAttribute("name")
                            val kColor = Color(ke.getAttribute("color").toInt())
                            val kDiameter = if (ke.hasAttribute("diameter")) ke.getAttribute("diameter").toDouble() else 1.0
                            doc3d.kinds.add(WallLayoutKind(kName, kColor, kDiameter))
                        }
                    }
                }

                // Load house-level asset definitions from root AssetDefinitions element
                val assetDefNodes = xmlDoc.documentElement.getElementsByTagName("AssetDefinitions")
                if (assetDefNodes.length > 0) {
                    val topEl = assetDefNodes.item(0) as Element
                    if (topEl.parentNode == xmlDoc.documentElement) {
                        val kindAssetsNodes = topEl.getElementsByTagName("KindAssets")
                        for (i in 0 until kindAssetsNodes.length) {
                            val kaEl = kindAssetsNodes.item(i) as Element
                            val kindIdx = kaEl.getAttribute("kind").toIntOrNull() ?: continue
                            val defs = mutableListOf<AssetDefinition>()
                            val defNodes2 = kaEl.getElementsByTagName("AssetDef")
                            for (j in 0 until defNodes2.length) {
                                val defEl = defNodes2.item(j) as Element
                                val name = defEl.getAttribute("name")
                                val limit = defEl.getAttribute("limit").toIntOrNull() ?: 0
                                val w = defEl.getAttribute("width").toDoubleOrNull() ?: 0.0
                                val h = defEl.getAttribute("height").toDoubleOrNull() ?: 0.0
                                defs.add(AssetDefinition(name, limit, w, h))
                            }
                            if (defs.isNotEmpty()) doc3d.assetDefinitions[kindIdx] = defs
                        }
                    }
                }

                // Load floors - support new embedded format and old path-based format
                val floorNodes = xmlDoc.getElementsByTagName("Floor")
                var loadedAny = false
                for (i in 0 until floorNodes.length) {
                    val floorNode = floorNodes.item(i) as Element
                    val height = floorNode.getAttribute("height").toInt()
                    val draw = if (floorNode.hasAttribute("draw")) floorNode.getAttribute("draw").toBoolean() else true

                    val floorDoc = FloorPlanDocument(this)
                    floorDoc.ambientHouseDoc = doc3d

                    if (floorNode.hasAttribute("path")) {
                        // Old format: load from external file
                        val path = floorNode.getAttribute("path")
                        val floorName = if (floorNode.hasAttribute("name")) floorNode.getAttribute("name") else "Floor ${i + 1}"
                        val floorFile = File(path)
                        if (floorFile.exists()) {
                            val floorXmlDoc = docBuilder.parse(floorFile)
                            floorXmlDoc.documentElement.normalize()
                            floorDoc.elements.addAll(parseFloorElements(floorXmlDoc))
                            floorDoc.currentFile = floorFile
                        } else {
                            JOptionPane.showMessageDialog(null, "Floor plan file not found: $path")
                            continue
                        }
                        doc3d.floors.add(ThreeDDocument.FloorData(floorName, height, draw, floorDoc))
                    } else {
                        // New embedded format: elements are children of the Floor node
                        val floorName = if (floorNode.hasAttribute("name")) floorNode.getAttribute("name") else "Floor ${i + 1}"
                        floorDoc.elements.addAll(parseFloorElements(floorNode))
                        doc3d.floors.add(ThreeDDocument.FloorData(floorName, height, draw, floorDoc))
                    }
                    loadedAny = true
                }

                if (loadedAny) {
                    doc3d.model = buildModel3D(doc3d)
                    doc3d.currentFile = file
                    doc3d.isModified = false
                    threeDDocuments.add(doc3d)
                    val window = ThreeDWindow(this, doc3d)
                    window.jMenuBar = createMenuBar()
                    window.title = "3D House Model - ${file.name}"

                    // Restore window state for 3D document
                    if (savedState != null) {
                        doc3d.scale = savedState.scale
                        doc3d.rotationX = savedState.offsetX // offsetX stores rotationX for 3D docs
                        doc3d.rotationZ = savedState.offsetY // offsetY stores rotationZ for 3D docs
                        doc3d.window?.let { w ->
                            w.setSize(savedState.size.width, savedState.size.height)
                            sidePanelWindow?.let { sideWindow ->
                                w.setLocation(sideWindow.x + savedState.pos.x, sideWindow.y + savedState.pos.y)
                            }
                        }
                    }

                    addToRecent(file)
                }
                return
            }
            
            val newElements = parseFloorElements(xmlDoc)
            
            val doc = FloorPlanDocument(this)
            
            val kindNodes = xmlDoc.getElementsByTagName("Kind")
            if (kindNodes.length > 0) {
                doc.kinds.clear()
                for (i in 0 until kindNodes.length) {
                    val kElement = kindNodes.item(i) as Element
                    val name = kElement.getAttribute("name")
                    val color = Color(kElement.getAttribute("color").toInt())
                    val diameter = if (kElement.hasAttribute("diameter")) kElement.getAttribute("diameter").toDouble() else 1.0
                    doc.kinds.add(WallLayoutKind(name, color, diameter))
                }
            }

            loadAssetDefinitions(xmlDoc, doc)
            doc.elements.addAll(newElements)
            doc.currentFile = file

            if (savedState != null) {
                doc.scale = savedState.scale
                doc.offsetX = savedState.offsetX
                doc.offsetY = savedState.offsetY
            }

            doc.saveState()
            doc.isModified = false
            documents.add(doc)
            if (!openFiles.contains(file.absolutePath)) {
                openFiles.add(file.absolutePath)
            }
            saveSettings()
            
            val window = EditorWindow(this, doc)
            window.jMenuBar = createMenuBar()
            
            if (savedState != null) {
                window.setSize(savedState.size.width, savedState.size.height)
                sidePanelWindow?.let { sideWindow ->
                    window.setLocation(sideWindow.x + savedState.pos.x, sideWindow.y + savedState.pos.y)
                }
                window.updateScaleLabel()
            }

            activeDocument = doc
            activeWindow = window
            
            addToRecent(file)
            sidePanel.updateFieldsForActiveDocument()
            statsPanel.update()
        } catch (e: Exception) {
            e.printStackTrace()
            JOptionPane.showMessageDialog(activeWindow, "Error loading: ${e.message}")
        }
    }

    private fun exportToPNG() {
        activeDocument?.let { doc ->
            if (doc.elements.isEmpty()) {
                JOptionPane.showMessageDialog(activeWindow, "No elements to export.")
                return
            }

            // 1. Calculate bounding box of all elements in model coordinates
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE

            for (el in doc.elements) {
                val bounds = el.getBounds()
                minX = minOf(minX, bounds.x.toDouble())
                minY = minOf(minY, bounds.y.toDouble())
                maxX = maxOf(maxX, (bounds.x + bounds.width).toDouble())
                maxY = maxOf(maxY, (bounds.y + bounds.height).toDouble())
            }

            // Add small margins (e.g. 10cm)
            val margin = 10.0
            minX -= margin
            minY -= margin
            maxX += margin
            maxY += margin

            val modelWidth = maxX - minX
            val modelHeight = maxY - minY

            // 2. Setup hi-res parameters (15 DPI)
            val targetDPI = 2.0
            val pixelsPerCmHiRes = targetDPI / 2.54

            val imgWidth = (modelWidth * pixelsPerCmHiRes).roundToInt()
            val imgHeight = (modelHeight * pixelsPerCmHiRes).roundToInt()

            if (imgWidth <= 0 || imgHeight <= 0 || imgWidth > 30000 || imgHeight > 30000) {
                JOptionPane.showMessageDialog(activeWindow, "Scene is too large or invalid for export (max 30000x30000 pixels).")
                return
            }

            val image = BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB)
            val g2 = image.createGraphics()

            // Save current view state
            val oldOffsetX = doc.offsetX
            val oldOffsetY = doc.offsetY
            val oldScale = doc.scale
            val oldIsExporting = isExporting

            try {
                isExporting = true
                // Configure transform to center on scene and use hi-res scale
                doc.offsetX = -minX
                doc.offsetY = -minY
                doc.scale = pixelsPerCmHiRes / doc.pixelsPerCm

                g2.color = Color.WHITE
                g2.fillRect(0, 0, imgWidth, imgHeight)

                doc.canvas.drawScene(g2, doc.offsetX, doc.offsetY, doc.scale, imgWidth, imgHeight)
            } finally {
                // Restore state
                doc.offsetX = oldOffsetX
                doc.offsetY = oldOffsetY
                doc.scale = oldScale
                isExporting = oldIsExporting
                g2.dispose()
            }
            
            val chooser = JFileChooser()
            if (chooser.showSaveDialog(activeWindow) == JFileChooser.APPROVE_OPTION) {
                var file = chooser.selectedFile
                if (!file.name.endsWith(".png")) {
                    file = File(file.absolutePath + ".png")
                }
                ImageIO.write(image, "PNG", file)
                JOptionPane.showMessageDialog(activeWindow, "Exported successfully!")
            }
        }
    }
}
