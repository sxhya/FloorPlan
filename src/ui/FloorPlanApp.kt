package ui

import model.PlanElement
import model.ElementType
import model.Wall
import model.Room
import model.Window as PlanWindow
import model.Door
import model.Stairs
import model.FloorOpening
import ui.components.SidePanel
import ui.components.ElementStatisticsPanel
import ui.components.StatisticsPanel
import java.awt.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
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
    internal var activeWindow: EditorWindow? = null
    
    internal val sidePanel = SidePanel(this)
    internal val elementStatsPanel = ElementStatisticsPanel(this)
    internal val statsPanel = StatisticsPanel(this)
    
    private var sidePanelWindow: SidePanelWindow? = null
    
    internal var isExporting = false
    
    private val SETTINGS_FILE = "floorplan_settings.properties"
    private var lastDirectory: String? = null
    private val recentFiles = mutableListOf<String>()
    private val openFiles = mutableListOf<String>()
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

    init {
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
        doc.window?.dispose()
        doc.currentFile?.let { openFiles.remove(it.absolutePath) }
        if (saveImmediately) saveSettings()
    }

    fun quitApp() {
        val docsToSave = documents.toList()
        val docsToClose = documents.toList()
        for (doc in docsToClose) {
            closeDocument(doc, saveImmediately = false)
        }
        if (documents.isEmpty()) {
            saveSettings(docsToSave)
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
                    is FloorOpening -> FloorOpening(el.vertices.map { Point(it.x + shift, it.y + shift) }.toMutableList())
                    else -> null
                }
                newEl?.let {
                    doc.saveState()
                    doc.elements.add(it)
                    doc.selectedElement = it
                    elementStatsPanel.updateElementStats(it)
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
                elementStatsPanel.updateElementStats(null)
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

    private fun saveSettings(docsToPersist: List<FloorPlanDocument>? = null) {
        val props = java.util.Properties()
        if (lastDirectory != null) props.setProperty("lastDirectory", lastDirectory)
        props.setProperty("recentFiles", recentFiles.joinToString(";"))
        
        // Save currently open documents that have a file with their relative positions
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
        }.distinct()
        props.setProperty("openFiles", currentOpen.joinToString(";"))
        
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
        openItem.addActionListener { openFromXML() }
        fileMenu.add(openItem)
        
        val recentMenu = JMenu("Recent Files")
        recentMenu.isVisible = true
        fileMenu.add(recentMenu)
        recentMenus.add(recentMenu)
        
        fileMenu.addSeparator()
        
        val saveItem = JMenuItem("Save")
        saveItem.addActionListener { save() }
        fileMenu.add(saveItem)
        
        val saveAsItem = JMenuItem("Save As...")
        saveAsItem.addActionListener { saveAs() }
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
        
        menuBars.add(menuBar)
        updateRecentMenu()
        return menuBar
    }

    private fun setupPopupMenu() {
        popAddWallMenu = JMenuItem("Add Wall")
        popAddWallMenu.addActionListener { sidePanel.addElement(ElementType.WALL) }
        popupMenu.add(popAddWallMenu)

        popAddRoomMenu = JMenuItem("Add Room")
        popAddRoomMenu.addActionListener { sidePanel.addElement(ElementType.ROOM) }
        popupMenu.add(popAddRoomMenu)

        popAddFloorOpeningMenu = JMenuItem("Add Floor Opening")
        popAddFloorOpeningMenu.addActionListener { sidePanel.addElement(ElementType.FLOOR_OPENING) }
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

    private fun save() {
        activeDocument?.let { doc ->
            val file = doc.currentFile
            if (file == null) {
                saveAs()
            } else {
                performSave(file)
            }
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
                    if (el !is FloorOpening) {
                        elNode.setAttribute("x", el.x.toString())
                        elNode.setAttribute("y", el.y.toString())
                        elNode.setAttribute("width", el.width.toString())
                        elNode.setAttribute("height", el.height.toString())
                        if (el is PlanWindow) {
                            elNode.setAttribute("height3D", el.height3D.toString())
                            elNode.setAttribute("aboveFloorHeight", el.sillElevation.toString())
                        }
                        if (el is Door) elNode.setAttribute("height3D", el.verticalHeight.toString())
                    } else {
                        val verticesStr = el.vertices.joinToString(";") { "${it.x},${it.y}" }
                        elNode.setAttribute("vertices", verticesStr)
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
                activeWindow?.updateTitle()
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
        lastDirectory?.let { chooser.currentDirectory = File(it) }
        if (chooser.showOpenDialog(activeWindow) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.selectedFile)
        }
    }

    private fun openFile(file: File, savedState: WindowState? = null) {
        try {
            val docFactory = DocumentBuilderFactory.newInstance()
            val docBuilder = docFactory.newDocumentBuilder()
            val xmlDoc = docBuilder.parse(file)
            
            xmlDoc.documentElement.normalize()
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
                            Room(x, y, w, h)
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
                        ElementType.FLOOR_OPENING -> {
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
                            val es = FloorOpening(vertices)
                            es.updateBounds()
                            es
                        }
                    }
                    newElements.add(el)
                }
            }
            
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
