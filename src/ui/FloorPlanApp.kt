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
    internal lateinit var popConvertToPolygonMenu: JMenuItem

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
            val floors = mutableListOf<FloorInfo>()
            for (i in 0 until tableModel.rowCount) {
                val docName = tableModel.getValueAt(i, 1) as String
                val height = tableModel.getValueAt(i, 2).toString().toDouble().toInt()
                val doc = documents.find { (it.currentFile?.name ?: "Untitled") == docName }
                if (doc != null) {
                    floors.add(FloorInfo(doc, height))
                }
            }
            if (floors.isNotEmpty()) {
                generate3DModel(floors)
                dialog.dispose()
            } else {
                JOptionPane.showMessageDialog(dialog, "Please select documents for floors.")
            }
        }
        dialog.add(okBtn, BorderLayout.SOUTH)
        dialog.isVisible = true
    }

    private data class FloorInfo(val doc: FloorPlanDocument, val height: Int)

    private fun generate3DModel(floors: List<FloorInfo>) {
        val model3d = model.Model3D()
        
        // Find the largest conjoined room space across all floors
        var largestGroup: List<Room>? = null
        var maxGroupArea = -1.0
        var groupFloorIndex = 0
        var groupZOffset = 0.0

        var runningZ = 0.0
        for ((idx, floor) in floors.withIndex()) {
            val rooms = floor.doc.elements.filterIsInstance<Room>()
            val polygonRooms = floor.doc.elements.filterIsInstance<PolygonRoom>()
            if (rooms.isNotEmpty()) {
                val adjacency = mutableMapOf<Room, MutableSet<Room>>()
                for (i in rooms.indices) {
                    for (j in i + 1 until rooms.size) {
                        val r1 = rooms[i]
                        val r2 = rooms[j]
                        if (areAdjacentRooms(r1, r2)) {
                            adjacency.getOrPut(r1) { mutableSetOf() }.add(r2)
                            adjacency.getOrPut(r2) { mutableSetOf() }.add(r1)
                        }
                    }
                }

                val visited = mutableSetOf<Room>()
                for (room in rooms) {
                    if (room !in visited) {
                        val group = mutableListOf<Room>()
                        val queue: java.util.Queue<Room> = java.util.LinkedList()
                        queue.add(room)
                        visited.add(room)
                        while (queue.isNotEmpty()) {
                            val r = queue.poll()
                            group.add(r)
                            adjacency[r]?.forEach { neighbor ->
                                if (neighbor !in visited) {
                                    visited.add(neighbor)
                                    queue.add(neighbor)
                                }
                            }
                        }
                        // Calculate area of rooms in the component
                        var groupArea = group.sumOf { it.width.toDouble() * it.height.toDouble() }
                        
                        // Add area of all polygon rooms docked to any room in this component
                        for (pr in polygonRooms) {
                            if (group.any { room -> isPolygonRoomDockedToRoom(pr, room) }) {
                                groupArea += pr.getArea()
                            }
                        }
                        
                        if (groupArea > maxGroupArea) {
                            maxGroupArea = groupArea
                            largestGroup = group
                            groupFloorIndex = idx
                            groupZOffset = runningZ
                        }
                    }
                }
            }
            runningZ += floor.height.toDouble()
        }

        var currentZ = 0.0
        val doc3d = ThreeDDocument(this)
        for (floor in floors) {
            val floorPath = floor.doc.currentFile?.absolutePath ?: ""
            doc3d.floors.add(ThreeDDocument.FloorData(floorPath, floor.height))

            val floorHeight = floor.height.toDouble()
            val roomsOnFloor = floor.doc.elements.filterIsInstance<Room>()
            
            // Compute light positions based on dockedness components
            if (roomsOnFloor.isNotEmpty()) {
                // Z coordinate: center height of this floor (including offset from floors below)
                val centerZ = currentZ + floorHeight / 2.0
                
                // Build adjacency map for rooms on this floor
                val adjacency = mutableMapOf<Room, MutableSet<Room>>()
                for (i in roomsOnFloor.indices) {
                    for (j in i + 1 until roomsOnFloor.size) {
                        val r1 = roomsOnFloor[i]
                        val r2 = roomsOnFloor[j]
                        if (areAdjacentRooms(r1, r2)) {
                            adjacency.getOrPut(r1) { mutableSetOf() }.add(r2)
                            adjacency.getOrPut(r2) { mutableSetOf() }.add(r1)
                        }
                    }
                }
                
                // Find all dockedness components (connected room groups)
                val visited = mutableSetOf<Room>()
                for (room in roomsOnFloor) {
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
                        
                        // Find the largest room in this dockedness component
                        val largestRoom = component.maxByOrNull { it.width.toDouble() * it.height.toDouble() }
                        if (largestRoom != null) {
                            // X, Y coordinates: center of the largest room
                            val centerX = largestRoom.x.toDouble() + largestRoom.width.toDouble() / 2.0
                            val centerY = largestRoom.y.toDouble() + largestRoom.height.toDouble() / 2.0
                            
                            // Add ONE light source per dockedness component
                            model3d.lightPositions.add(Vector3D(centerX, centerY, centerZ))
                        }
                    }
                }
            }

            for (el in floor.doc.elements) {
                when (el) {
                    is Room -> {
                        // Create a floor slab for the room
                        val x1 = el.x.toDouble()
                        val y1 = el.y.toDouble()
                        val x2 = (el.x + el.width).toDouble()
                        val y2 = (el.y + el.height).toDouble()
                        val thickness = el.floorThickness.toDouble()
                        
                        // Check which edges are shared with other rooms to avoid Z-fighting on side faces
                        val otherRooms = roomsOnFloor.filter { it !== el }
                        val hasAdjacentTop = otherRooms.any { other ->
                            // Another room shares the top edge (y1) of this room
                            other.y + other.height == el.y && 
                            other.x < el.x + el.width && other.x + other.width > el.x
                        }
                        val hasAdjacentBottom = otherRooms.any { other ->
                            // Another room shares the bottom edge (y2) of this room
                            other.y == el.y + el.height && 
                            other.x < el.x + el.width && other.x + other.width > el.x
                        }
                        val hasAdjacentLeft = otherRooms.any { other ->
                            // Another room shares the left edge (x1) of this room
                            other.x + other.width == el.x && 
                            other.y < el.y + el.height && other.y + other.height > el.y
                        }
                        val hasAdjacentRight = otherRooms.any { other ->
                            // Another room shares the right edge (x2) of this room
                            other.x == el.x + el.width && 
                            other.y < el.y + el.height && other.y + other.height > el.y
                        }
                        
                        // Top face (floor surface at currentZ)
                        model3d.rects.add(Rect3D(
                            Vector3D(x1, y1, currentZ), Vector3D(x2, y1, currentZ),
                            Vector3D(x2, y2, currentZ), Vector3D(x1, y2, currentZ),
                            Color.LIGHT_GRAY
                        ))
                        // Bottom face (slab bottom at currentZ - thickness)
                        model3d.rects.add(Rect3D(
                            Vector3D(x1, y1, currentZ - thickness), Vector3D(x2, y1, currentZ - thickness),
                            Vector3D(x2, y2, currentZ - thickness), Vector3D(x1, y2, currentZ - thickness),
                            Color.LIGHT_GRAY
                        ))
                        // Side faces - only add if not adjacent to another room on that edge
                        if (!hasAdjacentTop) {
                            model3d.rects.add(Rect3D(
                                Vector3D(x1, y1, currentZ - thickness), Vector3D(x2, y1, currentZ - thickness),
                                Vector3D(x2, y1, currentZ), Vector3D(x1, y1, currentZ),
                                Color.LIGHT_GRAY
                            ))
                        }
                        if (!hasAdjacentRight) {
                            model3d.rects.add(Rect3D(
                                Vector3D(x2, y1, currentZ - thickness), Vector3D(x2, y2, currentZ - thickness),
                                Vector3D(x2, y2, currentZ), Vector3D(x2, y1, currentZ),
                                Color.LIGHT_GRAY
                            ))
                        }
                        if (!hasAdjacentBottom) {
                            model3d.rects.add(Rect3D(
                                Vector3D(x2, y2, currentZ - thickness), Vector3D(x1, y2, currentZ - thickness),
                                Vector3D(x1, y2, currentZ), Vector3D(x2, y2, currentZ),
                                Color.LIGHT_GRAY
                            ))
                        }
                        if (!hasAdjacentLeft) {
                            model3d.rects.add(Rect3D(
                                Vector3D(x1, y2, currentZ - thickness), Vector3D(x1, y1, currentZ - thickness),
                                Vector3D(x1, y1, currentZ), Vector3D(x1, y2, currentZ),
                                Color.LIGHT_GRAY
                            ))
                        }
                    }
                    is PolygonRoom -> {
                        // Create a polygonal floor slab for the polygon room
                        val thickness = el.floorThickness.toDouble()
                        val vertices = el.vertices
                        
                        if (vertices.size >= 3) {
                            // Create top and bottom faces using triangle fan from centroid
                            val centroidX = vertices.sumOf { it.x.toDouble() } / vertices.size
                            val centroidY = vertices.sumOf { it.y.toDouble() } / vertices.size
                            
                            for (i in vertices.indices) {
                                val v1 = vertices[i]
                                val v2 = vertices[(i + 1) % vertices.size]
                                
                                // Top face triangle
                                model3d.triangles.add(Triangle3D(
                                    Vector3D(centroidX, centroidY, currentZ),
                                    Vector3D(v1.x.toDouble(), v1.y.toDouble(), currentZ),
                                    Vector3D(v2.x.toDouble(), v2.y.toDouble(), currentZ),
                                    Color.LIGHT_GRAY
                                ))
                                
                                // Bottom face triangle (reversed winding)
                                model3d.triangles.add(Triangle3D(
                                    Vector3D(centroidX, centroidY, currentZ - thickness),
                                    Vector3D(v2.x.toDouble(), v2.y.toDouble(), currentZ - thickness),
                                    Vector3D(v1.x.toDouble(), v1.y.toDouble(), currentZ - thickness),
                                    Color.LIGHT_GRAY
                                ))
                                
                                // Side face (quad as two triangles)
                                model3d.triangles.add(Triangle3D(
                                    Vector3D(v1.x.toDouble(), v1.y.toDouble(), currentZ),
                                    Vector3D(v1.x.toDouble(), v1.y.toDouble(), currentZ - thickness),
                                    Vector3D(v2.x.toDouble(), v2.y.toDouble(), currentZ - thickness),
                                    Color.LIGHT_GRAY
                                ))
                                model3d.triangles.add(Triangle3D(
                                    Vector3D(v1.x.toDouble(), v1.y.toDouble(), currentZ),
                                    Vector3D(v2.x.toDouble(), v2.y.toDouble(), currentZ - thickness),
                                    Vector3D(v2.x.toDouble(), v2.y.toDouble(), currentZ),
                                    Color.LIGHT_GRAY
                                ))
                            }
                        }
                    }
                    is Wall -> {
                        val wallBounds = el.getBounds()
                        val openings = floor.doc.elements.filter { it is PlanWindow || it is Door }
                            .filter { wallBounds.contains(it.getBounds()) }
                        
                        // Check if there's a floor above and if any room on that floor fully contains this wall
                        // If so, reduce wall height by that room's floor thickness
                        val currentFloorIndex = floors.indexOf(floor)
                        var wallTopReduction = 0.0
                        if (currentFloorIndex < floors.size - 1) {
                            val floorAbove = floors[currentFloorIndex + 1]
                            val roomsAbove = floorAbove.doc.elements.filterIsInstance<Room>()
                            // Check if any room on the floor above fully contains the wall
                            for (roomAbove in roomsAbove) {
                                val roomBounds = roomAbove.getBounds()
                                if (roomBounds.contains(wallBounds)) {
                                    // Wall is fully under this room, reduce height by floor thickness
                                    wallTopReduction = roomAbove.floorThickness.toDouble()
                                    break
                                }
                            }
                        }
                        val effectiveWallTop = currentZ + floorHeight - wallTopReduction
                        
                        fun addBox(x1: Double, y1: Double, x2: Double, y2: Double, z1: Double, z2: Double, color: Color) {
                            if (x1 >= x2 || y1 >= y2 || z1 >= z2) return
                            // Bottom
                            model3d.rects.add(Rect3D(Vector3D(x1, y1, z1), Vector3D(x2, y1, z1), Vector3D(x2, y2, z1), Vector3D(x1, y2, z1), color))
                            // Top
                            model3d.rects.add(Rect3D(Vector3D(x1, y1, z2), Vector3D(x2, y1, z2), Vector3D(x2, y2, z2), Vector3D(x1, y2, z2), color))
                            // Front
                            model3d.rects.add(Rect3D(Vector3D(x1, y1, z1), Vector3D(x2, y1, z1), Vector3D(x2, y1, z2), Vector3D(x1, y1, z2), color))
                            // Back
                            model3d.rects.add(Rect3D(Vector3D(x1, y2, z1), Vector3D(x2, y2, z1), Vector3D(x2, y2, z2), Vector3D(x1, y2, z2), color))
                            // Left
                            model3d.rects.add(Rect3D(Vector3D(x1, y1, z1), Vector3D(x1, y2, z1), Vector3D(x1, y2, z2), Vector3D(x1, y1, z2), color))
                            // Right
                            model3d.rects.add(Rect3D(Vector3D(x2, y1, z1), Vector3D(x2, y2, z1), Vector3D(x2, y2, z2), Vector3D(x2, y1, z2), color))
                        }

                        if (openings.isEmpty()) {
                            addBox(el.x.toDouble(), el.y.toDouble(), (el.x + el.width).toDouble(), (el.y + el.height).toDouble(), currentZ, effectiveWallTop, Color.DARK_GRAY)
                        } else {
                            val isVertical = el.width < el.height
                            if (isVertical) {
                                // Wall is along Y axis
                                val x1 = el.x.toDouble()
                                val x2 = (el.x + el.width).toDouble()
                                var lastY = el.y.toDouble()
                                val sortedOpenings = openings.sortedBy { it.y }
                                for (op in sortedOpenings) {
                                    val opY1 = op.y.toDouble()
                                    val opY2 = (op.y + op.height).toDouble()
                                    val opZ1 = currentZ + (if (op is PlanWindow) op.sillElevation.toDouble() else 0.0)
                                    val opZ2 = opZ1 + (if (op is PlanWindow) op.height3D.toDouble() else (op as Door).verticalHeight.toDouble())
                                    
                                    // Part before opening
                                    addBox(x1, lastY, x2, opY1, currentZ, effectiveWallTop, Color.DARK_GRAY)
                                    // Part above opening
                                    addBox(x1, opY1, x2, opY2, opZ2, effectiveWallTop, Color.DARK_GRAY)
                                    // Part below opening
                                    addBox(x1, opY1, x2, opY2, currentZ, opZ1, Color.DARK_GRAY)
                                    
                                    lastY = opY2
                                }
                                // Part after last opening
                                addBox(x1, lastY, x2, (el.y + el.height).toDouble(), currentZ, effectiveWallTop, Color.DARK_GRAY)
                            } else {
                                // Wall is along X axis
                                val y1 = el.y.toDouble()
                                val y2 = (el.y + el.height).toDouble()
                                var lastX = el.x.toDouble()
                                val sortedOpenings = openings.sortedBy { it.x }
                                for (op in sortedOpenings) {
                                    val opX1 = op.x.toDouble()
                                    val opX2 = (op.x + op.width).toDouble()
                                    val opZ1 = currentZ + (if (op is PlanWindow) op.sillElevation.toDouble() else 0.0)
                                    val opZ2 = opZ1 + (if (op is PlanWindow) op.height3D.toDouble() else (op as Door).verticalHeight.toDouble())

                                    // Part before opening
                                    addBox(lastX, y1, opX1, y2, currentZ, effectiveWallTop, Color.DARK_GRAY)
                                    // Part above opening
                                    addBox(opX1, y1, opX2, y2, opZ2, effectiveWallTop, Color.DARK_GRAY)
                                    // Part below opening
                                    addBox(opX1, y1, opX2, y2, currentZ, opZ1, Color.DARK_GRAY)

                                    lastX = opX2
                                }
                                // Part after last opening
                                addBox(lastX, y1, (el.x + el.width).toDouble(), y2, currentZ, effectiveWallTop, Color.DARK_GRAY)
                            }
                        }
                    }
                    is PlanWindow -> {
                        val x1 = el.x.toDouble()
                        val y1 = el.y.toDouble()
                        val x2 = (el.x + el.width).toDouble()
                        val y2 = (el.y + el.height).toDouble()
                        val h3d = el.height3D.toDouble()
                        val afh = el.sillElevation.toDouble()
                        
                        val wz1 = currentZ + afh
                        val wz2 = wz1 + h3d

                        val rooms = floor.doc.elements.filterIsInstance<Room>()
                        val isVertical = el.width < el.height
                        
                        // Semi-transparent blue (lower alpha = more transparent)
                        val windowColor = Color(0, 191, 255, 64)

                        if (isVertical) {
                            // Wall is along Y axis, so outer side is either X-epsilon or X+width+epsilon
                            val centerX = (x1 + x2) / 2.0
                            val centerY = (y1 + y2) / 2.0
                            
                            val hasRoomLeft = rooms.any { it.getBounds().contains(Point((centerX - 5).toInt(), centerY.toInt())) }
                            val hasRoomRight = rooms.any { it.getBounds().contains(Point((centerX + 5).toInt(), centerY.toInt())) }
                            
                            if (!hasRoomLeft) {
                                // Left side is outer
                                model3d.rects.add(Rect3D(Vector3D(x1, y1, wz1), Vector3D(x1, y2, wz1), Vector3D(x1, y2, wz2), Vector3D(x1, y1, wz2), windowColor, isWindow = true))
                            } else if (!hasRoomRight) {
                                // Right side is outer
                                model3d.rects.add(Rect3D(Vector3D(x2, y1, wz1), Vector3D(x2, y2, wz1), Vector3D(x2, y2, wz2), Vector3D(x2, y1, wz2), windowColor, isWindow = true))
                            } else {
                                // Both sides have rooms, or neither? If both, just pick one or draw both?
                                // "Outer is where is no room docked."
                                // If it's an internal window, maybe it shouldn't be drawn or drawn on both sides.
                                // Let's draw on both if both are "outer" (shouldn't happen for a wall between rooms if we use 5 pixels)
                                model3d.rects.add(Rect3D(Vector3D(x1, y1, wz1), Vector3D(x1, y2, wz1), Vector3D(x1, y2, wz2), Vector3D(x1, y1, wz2), windowColor, isWindow = true))
                                model3d.rects.add(Rect3D(Vector3D(x2, y1, wz1), Vector3D(x2, y2, wz1), Vector3D(x2, y2, wz2), Vector3D(x2, y1, wz2), windowColor, isWindow = true))
                            }
                        } else {
                            // Wall is along X axis, so outer side is either Y-epsilon or Y+height+epsilon
                            val centerX = (x1 + x2) / 2.0
                            val centerY = (y1 + y2) / 2.0
                            
                            val hasRoomTop = rooms.any { it.getBounds().contains(Point(centerX.toInt(), (centerY - 5).toInt())) }
                            val hasRoomBottom = rooms.any { it.getBounds().contains(Point(centerX.toInt(), (centerY + 5).toInt())) }
                            
                            if (!hasRoomTop) {
                                // Top side is outer
                                model3d.rects.add(Rect3D(Vector3D(x1, y1, wz1), Vector3D(x2, y1, wz1), Vector3D(x2, y1, wz2), Vector3D(x1, y1, wz2), windowColor, isWindow = true))
                            } else if (!hasRoomBottom) {
                                // Bottom side is outer
                                model3d.rects.add(Rect3D(Vector3D(x1, y2, wz1), Vector3D(x2, y2, wz1), Vector3D(x2, y2, wz2), Vector3D(x1, y2, wz2), windowColor, isWindow = true))
                            } else {
                                model3d.rects.add(Rect3D(Vector3D(x1, y1, wz1), Vector3D(x2, y1, wz1), Vector3D(x2, y1, wz2), Vector3D(x1, y1, wz2), windowColor, isWindow = true))
                                model3d.rects.add(Rect3D(Vector3D(x1, y2, wz1), Vector3D(x2, y2, wz1), Vector3D(x2, y2, wz2), Vector3D(x1, y2, wz2), windowColor, isWindow = true))
                            }
                        }
                    }
                    is Door -> {
                        // Doors are now just openings, no colored bars
                    }
                }
            }
            currentZ += floorHeight
        }

        doc3d.model = model3d
        doc3d.isModified = true
        threeDDocuments.add(doc3d)
        val window = ThreeDWindow(this, doc3d)
        window.jMenuBar = createMenuBar()
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
    }

    internal fun refreshUI() {
        activeDocument?.let { doc ->
            doc.selectedElement = null
            sidePanel.clearFields()
            statsPanel.update()
            doc.canvas.repaint()
        }
    }

    internal fun repaintAllCanvases() {
        documents.forEach { it.canvas.repaint() }
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

    private fun performSave3D(doc: ThreeDDocument, file: File) {
        try {
            val docFactory = DocumentBuilderFactory.newInstance()
            val docBuilder = docFactory.newDocumentBuilder()
            val xmlDoc = docBuilder.newDocument()

            val rootElement = xmlDoc.createElement("ThreeDModel")
            xmlDoc.appendChild(rootElement)

            for (floor in doc.floors) {
                val floorNode = xmlDoc.createElement("Floor")
                floorNode.setAttribute("path", floor.filePath)
                floorNode.setAttribute("height", floor.height.toString())
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
            addToRecent(file)
        } catch (e: Exception) {
            e.printStackTrace()
            JOptionPane.showMessageDialog(null, "Error saving 3D XML: ${e.message}")
        }
    }


    private fun save() {
        activeDocument?.let { doc ->
            val file = doc.currentFile
            if (file == null) {
                saveAs()
            } else {
                performSave(file)
            }
        }
        threeDDocuments.find { it.window == activeWindow }?.let { doc ->
            save3D(doc)
        }
    }

    private fun saveAs() {
        activeDocument?.let { doc ->
            val chooser = JFileChooser()
            if (chooser.showSaveDialog(activeWindow) == JFileChooser.APPROVE_OPTION) {
                var file = chooser.selectedFile
                if (!file.name.endsWith(".xml")) {
                    file = File(file.absolutePath + ".xml")
                }
                performSave(file)
            }
        }
        threeDDocuments.find { it.window == activeWindow }?.let { doc ->
            saveAs3D(doc)
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
                
                for (el in doc.elements) {
                    val elNode = xmlDoc.createElement("Element")
                    elNode.setAttribute("type", el.type.name)
                    if (el is PolygonRoom) {
                        val verticesStr = el.vertices.joinToString(";") { "${it.x},${it.y}" }
                        elNode.setAttribute("vertices", verticesStr)
                        elNode.setAttribute("floorThickness", el.floorThickness.toString())
                    } else {
                        elNode.setAttribute("x", el.x.toString())
                        elNode.setAttribute("y", el.y.toString())
                        elNode.setAttribute("width", el.width.toString())
                        elNode.setAttribute("height", el.height.toString())
                        if (el is PlanWindow) {
                            elNode.setAttribute("height3D", el.height3D.toString())
                            elNode.setAttribute("aboveFloorHeight", el.sillElevation.toString())
                        }
                        if (el is Door) elNode.setAttribute("height3D", el.verticalHeight.toString())
                        if (el is Room) elNode.setAttribute("floorThickness", el.floorThickness.toString())
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

    private fun parseFloorElements(xmlDoc: org.w3c.dom.Document): List<PlanElement> {
        val nList = xmlDoc.getElementsByTagName("Element")
        val newElements = mutableListOf<PlanElement>()
        for (i in 0 until nList.length) {
            val node = nList.item(i)
            if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE) {
                val eElement = node as Element
                val type = ElementType.valueOf(eElement.getAttribute("type"))

                val el = when (type) {
                    ElementType.WALL -> {
                        val x = eElement.getAttribute("x").toInt()
                        val y = eElement.getAttribute("y").toInt()
                        val w = eElement.getAttribute("width").toInt()
                        val h = eElement.getAttribute("height").toInt()
                        Wall(x, y, w, h)
                    }
                    ElementType.ROOM -> {
                        val x = eElement.getAttribute("x").toInt()
                        val y = eElement.getAttribute("y").toInt()
                        val w = eElement.getAttribute("width").toInt()
                        val h = eElement.getAttribute("height").toInt()
                        val ft = if (eElement.hasAttribute("floorThickness")) eElement.getAttribute("floorThickness").toInt() else 15
                        Room(x, y, w, h, ft)
                    }
                    ElementType.WINDOW -> {
                        val x = eElement.getAttribute("x").toInt()
                        val y = eElement.getAttribute("y").toInt()
                        val w = eElement.getAttribute("width").toInt()
                        val h = eElement.getAttribute("height").toInt()
                        val h3d = if (eElement.hasAttribute("height3D")) eElement.getAttribute("height3D").toInt() else 150
                        val afh = if (eElement.hasAttribute("aboveFloorHeight")) eElement.getAttribute("aboveFloorHeight").toInt() else 90
                        PlanWindow(x, y, w, h, h3d, afh)
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
                        Stairs(x, y, w, h)
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
                        val pr = PolygonRoom(vertices, ft)
                        pr.updateBounds()
                        pr
                    }
                }
                newElements.add(el)
            }
        }
        return newElements
    }

    private fun openFile(file: File, savedState: WindowState? = null) {
        try {
            val docFactory = DocumentBuilderFactory.newInstance()
            val docBuilder = docFactory.newDocumentBuilder()
            val xmlDoc = docBuilder.parse(file)
            xmlDoc.documentElement.normalize()

            if (xmlDoc.documentElement.nodeName == "ThreeDModel") {
                val floors = mutableListOf<FloorInfo>()
                val floorNodes = xmlDoc.getElementsByTagName("Floor")
                val floorDataList = mutableListOf<ThreeDDocument.FloorData>()
                for (i in 0 until floorNodes.length) {
                    val floorNode = floorNodes.item(i) as Element
                    val path = floorNode.getAttribute("path")
                    val height = floorNode.getAttribute("height").toInt()
                    floorDataList.add(ThreeDDocument.FloorData(path, height))
                    
                    val floorFile = File(path)
                    if (floorFile.exists()) {
                        val floorXmlDoc = docBuilder.parse(floorFile)
                        floorXmlDoc.documentElement.normalize()
                        val floorElements = parseFloorElements(floorXmlDoc)
                        val floorDoc = FloorPlanDocument(this)
                        floorDoc.elements.addAll(floorElements)
                        floorDoc.currentFile = floorFile
                        floors.add(FloorInfo(floorDoc, height))
                    } else {
                        JOptionPane.showMessageDialog(null, "Floor plan file not found: $path")
                    }
                }
                
                if (floors.isNotEmpty()) {
                    generate3DModel(floors)
                    val doc3d = threeDDocuments.last()
                    doc3d.currentFile = file
                    doc3d.isModified = false
                    doc3d.floors.clear()
                    doc3d.floors.addAll(floorDataList)
                    doc3d.window?.title = "3D House Model - ${file.name}"
                    
                    // Restore window state for 3D document
                    if (savedState != null) {
                        doc3d.scale = savedState.scale
                        doc3d.rotationX = savedState.offsetX // offsetX stores rotationX for 3D docs
                        doc3d.rotationZ = savedState.offsetY // offsetY stores rotationZ for 3D docs
                        doc3d.window?.let { window ->
                            window.setSize(savedState.size.width, savedState.size.height)
                            sidePanelWindow?.let { sideWindow ->
                                window.setLocation(sideWindow.x + savedState.pos.x, sideWindow.y + savedState.pos.y)
                            }
                        }
                    }
                    
                    addToRecent(file)
                }
                return
            }
            
            val newElements = parseFloorElements(xmlDoc)
            
            val doc = FloorPlanDocument(this)
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
