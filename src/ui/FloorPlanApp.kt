package ui

import model.PlanElement
import model.ElementType
import model.Wall
import model.Room
import model.Window as PlanWindow
import model.Door
import model.Stairs
import model.FloorOpening
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.pow

class FloorPlanApp : JFrame("Floor Plan Editor") {
    private val elements = mutableListOf<PlanElement>()
    private var selectedElement: PlanElement? = null
    private var currentFile: File? = null
    
    private enum class AppMode {
        NORMAL, DRAG, RULER
    }
    private var currentMode = AppMode.NORMAL

    private val canvas = CanvasPanel()
    private val sidePanel = SidePanel()
    private val elementStatsPanel = ElementStatisticsPanel()
    private val statsPanel = StatisticsPanel()
    private val scaleLabel = JLabel("100%")
    private var mainMenuBar: JMenuBar? = null
    private var showDimensionLabels = false
    private var isExporting = false
    
    private val normalBtn = JToggleButton("🖱", true)
    private val dragBtn = JToggleButton("✋")
    private val rulerBtn = JToggleButton("📏")
    
    private val MIN_SCALE = 0.005
    private val MAX_SCALE = 0.05

    private val undoStack = mutableListOf<List<PlanElement>>()
    private val redoStack = mutableListOf<List<PlanElement>>()
    private val MAX_HISTORY = 10
    
    private val SETTINGS_FILE = "floorplan_settings.properties"
    private var lastDirectory: String? = null
    private val recentFiles = mutableListOf<String>()
    private val MAX_RECENT = 10

    private val popupMenu = JPopupMenu()
    private val recentMenu = JMenu("Recent Files")
    private lateinit var undoMenuItem: JMenuItem
    private lateinit var redoMenuItem: JMenuItem
    private lateinit var popAddWallMenu: JMenuItem
    private lateinit var popAddRoomMenu: JMenuItem
    private lateinit var popSepGeneral: JSeparator
    private lateinit var popDuplicateMenu: JMenuItem
    private lateinit var popRemoveMenu: JMenuItem
    private lateinit var popSepElements: JSeparator
    private lateinit var popAddWindowMenu: JMenuItem
    private lateinit var popAddDoorMenu: JMenuItem
    private lateinit var popSepRoom: JSeparator
    private lateinit var popAddStairsMenu: JMenuItem
    private lateinit var popAddFloorOpeningMenu: JMenuItem

    private var scale = 0.01 // screen cm / model cm
    private var offsetX = 0.0 // model cm
    private var offsetY = 0.0 // model cm
    private val pixelsPerCm = Toolkit.getDefaultToolkit().screenResolution / 2.54

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        setSize(1000, 700)
        loadSettings()
        
        val sideContainer = JPanel(BorderLayout())
        sideContainer.add(sidePanel, BorderLayout.CENTER)
        
        val bottomPanel = JPanel()
        bottomPanel.layout = BoxLayout(bottomPanel, BoxLayout.Y_AXIS)
        bottomPanel.add(elementStatsPanel)
        bottomPanel.add(statsPanel)
        sideContainer.add(bottomPanel, BorderLayout.SOUTH)
        
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvas, sideContainer)
        splitPane.resizeWeight = 1.0
        splitPane.dividerLocation = 750
        add(splitPane, BorderLayout.CENTER)

        val toolBar = JToolBar()
        
        normalBtn.toolTipText = "Normal Mode"
        dragBtn.toolTipText = "Scene Drag Mode"
        rulerBtn.toolTipText = "Ruler Mode"

        val group = ButtonGroup()
        group.add(normalBtn)
        group.add(dragBtn)
        group.add(rulerBtn)

        normalBtn.addActionListener { 
            currentMode = AppMode.NORMAL 
            canvas.cursor = Cursor.getDefaultCursor()
            canvas.repaint()
        }
        dragBtn.addActionListener { 
            currentMode = AppMode.DRAG 
            canvas.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            canvas.repaint()
        }
        rulerBtn.addActionListener { 
            currentMode = AppMode.RULER 
            canvas.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            canvas.repaint()
        }

        toolBar.add(normalBtn)
        toolBar.add(dragBtn)
        toolBar.add(rulerBtn)
        
        toolBar.addSeparator()
        
        val zoomOutBtn = JButton("🔍-")
        zoomOutBtn.toolTipText = "Zoom Out"
        zoomOutBtn.addActionListener {
            scale = (scale / 1.1).coerceAtLeast(MIN_SCALE)
            updateScaleLabel()
            canvas.repaint()
        }
        toolBar.add(zoomOutBtn)
        
        scaleLabel.border = BorderFactory.createEmptyBorder(0, 5, 0, 5)
        toolBar.add(scaleLabel)
        
        val zoomInBtn = JButton("🔍+")
        zoomInBtn.toolTipText = "Zoom In"
        zoomInBtn.addActionListener {
            scale = (scale * 1.1).coerceAtMost(MAX_SCALE)
            updateScaleLabel()
            canvas.repaint()
        }
        toolBar.add(zoomInBtn)

        add(toolBar, BorderLayout.NORTH)

        // Setup popup menu
        popAddWallMenu = JMenuItem("Add Wall").apply { addActionListener { sidePanel.addElement(ElementType.WALL) } }
        popupMenu.add(popAddWallMenu)

        popAddRoomMenu = JMenuItem("Add Room").apply { addActionListener { sidePanel.addElement(ElementType.ROOM) } }
        popupMenu.add(popAddRoomMenu)

        popAddFloorOpeningMenu = JMenuItem("Add Floor opening").apply { addActionListener { sidePanel.addElement(ElementType.FLOOR_OPENING) } }
        popupMenu.add(popAddFloorOpeningMenu)

        popSepGeneral = JSeparator()
        popupMenu.add(popSepGeneral)

        popDuplicateMenu = JMenuItem("Duplicate").apply { addActionListener { duplicateSelected() } }
        popupMenu.add(popDuplicateMenu)

        popRemoveMenu = JMenuItem("Remove").apply { addActionListener { removeSelected() } }
        popupMenu.add(popRemoveMenu)

        popSepElements = JSeparator()
        popupMenu.add(popSepElements)

        popAddWindowMenu = JMenuItem("Add Window").apply { addActionListener { sidePanel.addElement(ElementType.WINDOW) } }
        popupMenu.add(popAddWindowMenu)

        popAddDoorMenu = JMenuItem("Add Door").apply { addActionListener { sidePanel.addElement(ElementType.DOOR) } }
        popupMenu.add(popAddDoorMenu)

        popSepRoom = JSeparator()
        popupMenu.add(popSepRoom)

        popAddStairsMenu = JMenuItem("Add Stairs").apply { addActionListener { sidePanel.addElement(ElementType.STAIRS) } }
        popupMenu.add(popAddStairsMenu)
        
        // Ensure popups are initialized correctly
        popAddWallMenu.isVisible = true
        popAddRoomMenu.isVisible = true
        popAddFloorOpeningMenu.isVisible = true

        statsPanel.update()

        // Initial test data (now in cm)
        elements.add(Wall(100, 100, 1000, 20))
        elements.add(Wall(100, 100, 20, 1000))
        elements.add(Room(120, 120, 500, 500))
        elements.add(PlanWindow(150, 100, 50, 20))
        elements.add(Door(100, 200, 20, 40))
        saveState()

        updateTitle()

        isVisible = true
        setupMenuBar()
        // Re-apply menu bar for MacOS compatibility after visibility
        SwingUtilities.invokeLater {
            if (mainMenuBar != null) {
                jMenuBar = mainMenuBar
            }
            updateUndoRedoStates()
            updateRecentMenu()
            rootPane.revalidate()
            rootPane.repaint()
        }
    }

    data class IntersectionInfo(val el1: PlanElement, val el2: PlanElement, val rect: Rectangle)

    fun recenterOnElement(el: PlanElement) {
        val availableWidth = canvas.width.toDouble()
        val availableHeight = canvas.height.toDouble()
        
        if (availableWidth <= 0 || availableHeight <= 0) return

        // Center the element
        // (el.x + offsetX) * scale * pixelsPerCm = availableWidth / 2
        // el.x + offsetX = (availableWidth / 2) / (scale * pixelsPerCm)
        // offsetX = (availableWidth / 2) / (scale * pixelsPerCm) - el.x
        
        // Using center of element
        val centerX = el.x + el.width / 2.0
        val centerY = el.y + el.height / 2.0
        
        offsetX = (availableWidth / 2.0) / (scale * pixelsPerCm) - centerX
        offsetY = (availableHeight / 2.0) / (scale * pixelsPerCm) - centerY
        
        canvas.repaint()
    }

    private fun calculateIntersections(): List<IntersectionInfo> {
        val candidates = elements.filter { it is Wall || it is Room }
        val intersections = mutableListOf<IntersectionInfo>()
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val r1 = candidates[i].getBounds()
                val r2 = candidates[j].getBounds()
                val intersection = r1.intersection(r2)
                if (!intersection.isEmpty) {
                    intersections.add(IntersectionInfo(candidates[i], candidates[j], intersection))
                }
            }
        }
        return intersections
    }

    private fun duplicateSelected() {
        selectedElement?.let { el ->
            val shift = (maxOf(el.width, el.height) * 0.05).roundToInt()
            val newEl = when (el) {
                is Wall -> Wall(el.x + shift, el.y + shift, el.width, el.height)
                is Room -> Room(el.x + shift, el.y + shift, el.width, el.height)
                is PlanWindow -> PlanWindow(el.x + shift, el.y + shift, el.width, el.height, el.height3D)
                is Door -> Door(el.x + shift, el.y + shift, el.width, el.height, el.height3D)
                is Stairs -> Stairs(el.x + shift, el.y + shift, el.width, el.height)
                is FloorOpening -> FloorOpening(el.vertices.map { Point(it.x + shift, it.y + shift) }.toMutableList())
                else -> null
            }
            newEl?.let {
                elements.add(it)
                saveState()
                selectedElement = it
                elementStatsPanel.updateElementStats(it)
                sidePanel.updateFields(it)
                statsPanel.update()
                canvas.repaint()
            }
        }
    }

    private fun removeSelected() {
        selectedElement?.let { el ->
            elements.remove(el)
            saveState()
            selectedElement = null
            elementStatsPanel.updateElementStats(null)
            sidePanel.clearFields()
            statsPanel.update()
            canvas.repaint()
        }
    }

    private fun updateTitle() {
        title = if (currentFile != null) {
            "Floor Plan Editor - ${currentFile!!.name}"
        } else {
            "Floor Plan Editor"
        }
    }

    private fun loadSettings() {
        val file = File(SETTINGS_FILE)
        if (file.exists()) {
            val props = java.util.Properties()
            props.load(file.inputStream())
            lastDirectory = props.getProperty("lastDirectory")
            props.getProperty("recentFiles")?.split(";")?.filter { it.isNotEmpty() }?.let {
                recentFiles.addAll(it)
            }
        }
    }

    private fun saveSettings() {
        val props = java.util.Properties()
        if (lastDirectory != null) props.setProperty("lastDirectory", lastDirectory)
        props.setProperty("recentFiles", recentFiles.joinToString(";"))
        props.store(File(SETTINGS_FILE).outputStream(), "FloorPlanApp Settings")
    }

    private fun updateRecentMenu() {
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
        mainMenuBar?.revalidate()
        mainMenuBar?.repaint()
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

    private fun setupMenuBar() {
        val menuBar = JMenuBar()
        
        val fileMenu = JMenu("File")
        val newItem = JMenuItem("New")
        newItem.addActionListener { createNew() }
        fileMenu.add(newItem)
        
        val openItem = JMenuItem("Open")
        openItem.addActionListener { openFromXML() }
        fileMenu.add(openItem)
        
        recentMenu.isVisible = true
        fileMenu.add(recentMenu)
        updateRecentMenu()
        
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
        
        menuBar.add(fileMenu)
        
        val editMenu = JMenu("Edit")
        undoMenuItem = JMenuItem("Undo")
        undoMenuItem.accelerator = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Z, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        undoMenuItem.addActionListener { undo() }
        undoMenuItem.isVisible = true
        editMenu.add(undoMenuItem)
        
        redoMenuItem = JMenuItem("Redo")
        redoMenuItem.accelerator = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_Y, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        redoMenuItem.addActionListener { redo() }
        redoMenuItem.isVisible = true
        editMenu.add(redoMenuItem)
        
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
        val dimLabelsItem = JCheckBoxMenuItem("Toggle dimension labels", showDimensionLabels)
        dimLabelsItem.addActionListener {
            showDimensionLabels = dimLabelsItem.isSelected
            canvas.repaint()
        }
        viewMenu.add(dimLabelsItem)
        menuBar.add(viewMenu)
        
        mainMenuBar = menuBar
        jMenuBar = menuBar
    }

    private fun cloneElements(source: List<PlanElement>): List<PlanElement> {
        val cloned = mutableListOf<PlanElement>()
        for (el in source) {
            val newEl = when (el) {
                is Wall -> Wall(el.x, el.y, el.width, el.height)
                is Room -> Room(el.x, el.y, el.width, el.height)
                is PlanWindow -> PlanWindow(el.x, el.y, el.width, el.height, el.height3D)
                is Door -> Door(el.x, el.y, el.width, el.height, el.height3D)
                is Stairs -> Stairs(el.x, el.y, el.width, el.height)
                is FloorOpening -> FloorOpening(el.vertices.map { Point(it.x, it.y) }.toMutableList())
                else -> null
            }
            newEl?.let { cloned.add(it) }
        }
        return cloned
    }

    private fun saveState() {
        undoStack.add(cloneElements(elements))
        if (undoStack.size > MAX_HISTORY + 1) { // Current state is also in undoStack
            undoStack.removeAt(0)
        }
        redoStack.clear()
        updateUndoRedoStates()
    }

    private fun undo() {
        if (undoStack.size > 1) {
            redoStack.add(undoStack.removeAt(undoStack.size - 1))
            val state = undoStack.last()
            elements.clear()
            elements.addAll(cloneElements(state))
            selectedElement = null
            elementStatsPanel.updateElementStats(null)
            refreshUI()
            updateUndoRedoStates()
        }
    }

    private fun redo() {
        if (redoStack.isNotEmpty()) {
            val state = redoStack.removeAt(redoStack.size - 1)
            undoStack.add(state)
            elements.clear()
            elements.addAll(cloneElements(state))
            selectedElement = null
            elementStatsPanel.updateElementStats(null)
            refreshUI()
            updateUndoRedoStates()
        }
    }

    private fun refreshUI() {
        selectedElement = null
        sidePanel.clearFields()
        statsPanel.update()
        canvas.repaint()
    }

    private fun updateUndoRedoStates() {
        if (::undoMenuItem.isInitialized) {
            undoMenuItem.isEnabled = undoStack.size > 1
            undoMenuItem.isVisible = true
        }
        if (::redoMenuItem.isInitialized) {
            redoMenuItem.isEnabled = redoStack.isNotEmpty()
            redoMenuItem.isVisible = true
        }
        mainMenuBar?.revalidate()
        mainMenuBar?.repaint()
    }

    private fun updateScaleLabel() {
        scaleLabel.text = "${(scale * 10000).roundToInt()}%"
    }

    private fun autoScaleToFit() {
        if (elements.isEmpty()) return
        
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = Double.MIN_VALUE
        var maxY = Double.MIN_VALUE
        
        for (el in elements) {
            minX = minOf(minX, el.x.toDouble())
            minY = minOf(minY, el.y.toDouble())
            maxX = maxOf(maxX, (el.x + el.width).toDouble())
            maxY = maxOf(maxY, (el.y + el.height).toDouble())
        }
        
        val modelWidth = maxX - minX
        val modelHeight = maxY - minY
        
        if (modelWidth == 0.0 || modelHeight == 0.0) return

        val padding = 50.0 // pixels
        val availableWidth = canvas.width - 2 * padding
        val availableHeight = canvas.height - 2 * padding
        
        if (availableWidth <= 0 || availableHeight <= 0) return

        val scaleX = (availableWidth / pixelsPerCm) / modelWidth
        val scaleY = (availableHeight / pixelsPerCm) / modelHeight
        
        scale = minOf(scaleX, scaleY).coerceIn(MIN_SCALE, MAX_SCALE)
        
        // Center the model
        offsetX = -minX + (availableWidth / pixelsPerCm / scale - modelWidth) / 2.0 + padding / pixelsPerCm / scale
        offsetY = -minY + (availableHeight / pixelsPerCm / scale - modelHeight) / 2.0 + padding / pixelsPerCm / scale
        
        updateScaleLabel()
        canvas.repaint()
    }

    private fun modelToScreen(modelCm: Double, offsetCm: Double, customScale: Double = scale): Int {
        return ((modelCm + offsetCm) * customScale * pixelsPerCm).roundToInt()
    }

    private fun screenToModel(screenPx: Int, offsetCm: Double, customScale: Double = scale): Double {
        return (screenPx / (customScale * pixelsPerCm)) - offsetCm
    }

    private fun createNew() {
        elements.clear()
        saveState()
        selectedElement = null
        elementStatsPanel.updateElementStats(null)
        currentFile = null
        sidePanel.clearFields()
        statsPanel.update()
        updateTitle()
        // Reset view
        scale = 0.01
        offsetX = 0.0
        offsetY = 0.0
        updateScaleLabel()
        canvas.repaint()
    }

    private fun save() {
        val file = currentFile
        if (file == null) {
            saveAs()
        } else {
            performSave(file)
        }
    }

    private fun saveAs() {
        val chooser = JFileChooser()
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            if (!file.name.endsWith(".xml")) {
                file = File(file.absolutePath + ".xml")
            }
            performSave(file)
        }
    }

    private fun performSave(file: File) {
        try {
            val docFactory = DocumentBuilderFactory.newInstance()
            val docBuilder = docFactory.newDocumentBuilder()
            val doc = docBuilder.newDocument()
            
            val rootElement = doc.createElement("FloorPlan")
            doc.appendChild(rootElement)
            
            for (el in elements) {
                val elNode = doc.createElement("Element")
                elNode.setAttribute("type", el.type.name)
                if (el !is FloorOpening) {
                    elNode.setAttribute("x", el.x.toString())
                    elNode.setAttribute("y", el.y.toString())
                    elNode.setAttribute("width", el.width.toString())
                    elNode.setAttribute("height", el.height.toString())
                    if (el is PlanWindow) elNode.setAttribute("height3D", el.height3D.toString())
                    if (el is Door) elNode.setAttribute("height3D", el.height3D.toString())
                } else {
                    val verticesStr = el.vertices.joinToString(";") { "${it.x},${it.y}" }
                    elNode.setAttribute("vertices", verticesStr)
                }
                rootElement.appendChild(elNode)
            }
            
            val transformerFactory = TransformerFactory.newInstance()
            val transformer = transformerFactory.newTransformer()
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            val source = DOMSource(doc)
            val result = StreamResult(file)
            
            transformer.transform(source, result)
            currentFile = file
            updateTitle()
            addToRecent(file)
            JOptionPane.showMessageDialog(this, "Saved successfully!")
        } catch (e: Exception) {
            e.printStackTrace()
            JOptionPane.showMessageDialog(this, "Error saving: ${e.message}")
        }
    }

    private fun openFromXML() {
        val chooser = JFileChooser()
        lastDirectory?.let { chooser.currentDirectory = File(it) }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.selectedFile)
        }
    }

    private fun openFile(file: File) {
        try {
            val docFactory = DocumentBuilderFactory.newInstance()
            val docBuilder = docFactory.newDocumentBuilder()
            val doc = docBuilder.parse(file)
            
            doc.documentElement.normalize()
            val nList = doc.getElementsByTagName("Element")
            
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
                            val h3d = if (eElement.hasAttribute("height3D")) eElement.getAttribute("height3D").toInt() else 210
                            PlanWindow(x, y, w, h, h3d)
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
                            // We must ensure bounds are correct after loading vertices
                            es.updateBounds()
                            es
                        }
                    }
                    newElements.add(el)
                }
            }
            
            elements.clear()
            elements.addAll(newElements)
            saveState()
            currentFile = file
            updateTitle()
            addToRecent(file)
            selectedElement = null
            elementStatsPanel.updateElementStats(null)
            sidePanel.clearFields()
            statsPanel.update()
            canvas.repaint()
            JOptionPane.showMessageDialog(this, "Loaded successfully!")
        } catch (e: Exception) {
            e.printStackTrace()
            JOptionPane.showMessageDialog(this, "Error loading: ${e.message}")
        }
    }

    private fun exportToPNG() {
        if (elements.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No elements to export.")
            return
        }

        // 1. Calculate bounding box of all elements in model coordinates
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE

        for (el in elements) {
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
            JOptionPane.showMessageDialog(this, "Scene is too large or invalid for export (max 30000x30000 pixels).")
            return
        }

        val image = BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()

        // Save current view state
        val oldOffsetX = offsetX
        val oldOffsetY = offsetY
        val oldScale = scale
        val oldIsExporting = isExporting

        try {
            isExporting = true
            // Configure transform to center on scene and use hi-res scale
            // We want model coordinate (minX, minY) to map to (0, 0) in the image.
            offsetX = -minX
            offsetY = -minY
            scale = pixelsPerCmHiRes / pixelsPerCm

            g2.color = Color.WHITE
            g2.fillRect(0, 0, imgWidth, imgHeight)

            canvas.drawScene(g2, offsetX, offsetY, scale, imgWidth, imgHeight)
        } finally {
            // Restore state
            offsetX = oldOffsetX
            offsetY = oldOffsetY
            scale = oldScale
            isExporting = oldIsExporting
            g2.dispose()
        }
        
        val chooser = JFileChooser()
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.selectedFile
            if (!file.name.endsWith(".png")) {
                file = File(file.absolutePath + ".png")
            }
            ImageIO.write(image, "PNG", file)
            JOptionPane.showMessageDialog(this, "Exported successfully!")
        }
    }

    private enum class ResizeHandle {
        NONE, N, S, E, W, NW, NE, SW, SE
    }

    fun findContainingWall(x: Int, y: Int, w: Int, h: Int): Wall? {
        val rect = Rectangle(x, y, w, h)
        return elements.filterIsInstance<Wall>().find { it.getBounds().contains(rect) }
    }

    fun findContainingRoom(x: Int, y: Int, w: Int, h: Int): Room? {
        val rect = Rectangle(x, y, w, h)
        return elements.filterIsInstance<Room>().find { it.getBounds().contains(rect) }
    }

    fun findContainingRoomForFloorOpening(es: FloorOpening): Room? {
        return elements.filterIsInstance<Room>().find { it.getBounds().contains(es.getBounds()) }
    }

    private data class Axis(
        val x1: Double, val y1: Double, 
        val x2: Double, val y2: Double, 
        val label: String, 
        val isHorizontal: Boolean
    )

    inner class CanvasPanel : JPanel() {
        private var dragStart: Point? = null
        private var initialElementBounds: Rectangle? = null
        private var initialVertices: List<Point>? = null
        private var elementsToMoveWithWall = mutableListOf<Pair<PlanElement, Point>>()
        private var initialChildrenVertices = mutableListOf<Pair<FloorOpening, List<Point>>>()
        private val STICK_THRESHOLD = 10
        
        private var panStart: Point? = null
        private var initialOffsetX: Double? = null
        private var initialOffsetY: Double? = null

        private var draggingOrigin = false
        private var originDragStart: Point? = null
        private var initialElementsPositions: List<Point>? = null

        private var activeHandle: Any = ResizeHandle.NONE
        private val HANDLE_SIZE = 8 // Screen pixels

        val rulerMarkers = mutableListOf<Point>() // model cm
        var rulerProbePoint: Point? = null // model cm
        var rulerClosed = false
        var rulerProbeEnabled = true
        var isCreatingFloorOpening = false

        private var vertexBeingDraggedIndex = -1

        private fun getHandleUnderMouse(p: Point): Any {
            val el = selectedElement ?: return ResizeHandle.NONE
            
            if (el is FloorOpening) {
                val r = HANDLE_SIZE
                for (i in el.vertices.indices) {
                    val v = el.vertices[i]
                    val sx = modelToScreen(v.x.toDouble(), offsetX)
                    val sy = modelToScreen(v.y.toDouble(), offsetY)
                    if (Rectangle(sx - r, sy - r, 2 * r, 2 * r).contains(p)) {
                        return i
                    }
                }
                return ResizeHandle.NONE
            }

            val sx = modelToScreen(el.x.toDouble(), offsetX)
            val sy = modelToScreen(el.y.toDouble(), offsetY)
            val sw = modelToScreen((el.x + el.width).toDouble(), offsetX) - sx
            val sh = modelToScreen((el.y + el.height).toDouble(), offsetY) - sy

            val r = HANDLE_SIZE
            val rects = mapOf(
                ResizeHandle.NW to Rectangle(sx - r, sy - r, 2 * r, 2 * r),
                ResizeHandle.N to Rectangle(sx + sw / 2 - r, sy - r, 2 * r, 2 * r),
                ResizeHandle.NE to Rectangle(sx + sw - r, sy - r, 2 * r, 2 * r),
                ResizeHandle.E to Rectangle(sx + sw - r, sy + sh / 2 - r, 2 * r, 2 * r),
                ResizeHandle.SE to Rectangle(sx + sw - r, sy + sh - r, 2 * r, 2 * r),
                ResizeHandle.S to Rectangle(sx + sw / 2 - r, sy + sh - r, 2 * r, 2 * r),
                ResizeHandle.SW to Rectangle(sx - r, sy + sh - r, 2 * r, 2 * r),
                ResizeHandle.W to Rectangle(sx - r, sy + sh / 2 - r, 2 * r, 2 * r)
            )

            for ((handle, rect) in rects) {
                if (rect.contains(p)) return handle
            }
            return ResizeHandle.NONE
        }

        init {
            isFocusable = true
            background = Color.WHITE
            val mouseAdapter = object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    if (currentMode == AppMode.NORMAL) {
                        val handle = getHandleUnderMouse(e.point)
                        cursor = when (handle) {
                            ResizeHandle.NW -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
                            ResizeHandle.N -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
                            ResizeHandle.NE -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
                            ResizeHandle.E -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
                            ResizeHandle.SE -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
                            ResizeHandle.S -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
                            ResizeHandle.SW -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)
                            ResizeHandle.W -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
                            is Int -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                            else -> Cursor.getDefaultCursor()
                        }
                    } else if (currentMode == AppMode.RULER) {
                        if (rulerProbeEnabled) {
                            rulerProbePoint = Point(screenToModel(e.x, offsetX).roundToInt(), screenToModel(e.y, offsetY).roundToInt())
                            repaint()
                        }
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    if (currentMode == AppMode.RULER) {
                        rulerProbePoint = null
                        repaint()
                    }
                }


                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    if (e.isPopupTrigger && currentMode == AppMode.NORMAL) {
                        showPopup(e)
                        return
                    }
                    if (currentMode == AppMode.DRAG) {
                        panStart = e.point
                        initialOffsetX = offsetX
                        initialOffsetY = offsetY
                        return
                    }

                    if (currentMode == AppMode.RULER) {
                        if (SwingUtilities.isRightMouseButton(e)) {
                            rulerMarkers.clear()
                            rulerClosed = false
                            rulerProbeEnabled = true
                            isCreatingFloorOpening = false
                            currentMode = AppMode.NORMAL
                            normalBtn.isSelected = true
                        } else if (SwingUtilities.isLeftMouseButton(e)) {
                            if (e.clickCount == 2) {
                                if (rulerMarkers.size == 1) {
                                    rulerMarkers.clear()
                                    rulerClosed = false
                                    rulerProbeEnabled = true
                                } else if (rulerMarkers.size == 2) {
                                    rulerProbeEnabled = false
                                    rulerProbePoint = null
                                } else if (rulerMarkers.size > 2) {
                                    if (isCreatingFloorOpening) {
                                        val room = selectedElement as? Room
                                        val newES = FloorOpening(rulerMarkers.toMutableList())
                                        elements.add(newES)
                                        saveState()
                                        selectedElement = newES
                                        rulerMarkers.clear()
                                        rulerClosed = false
                                        rulerProbeEnabled = true
                                        rulerProbePoint = null
                                        isCreatingFloorOpening = false
                                        currentMode = AppMode.NORMAL
                                        normalBtn.isSelected = true
                                        canvas.cursor = Cursor.getDefaultCursor()
                                        sidePanel.updateFields(newES)
                                        statsPanel.update()
                                    } else {
                                        rulerClosed = true
                                        rulerProbeEnabled = false
                                        rulerProbePoint = null
                                    }
                                }
                            } else if (e.clickCount == 1) {
                                if (!rulerProbeEnabled) {
                                    rulerMarkers.clear()
                                    rulerClosed = false
                                    rulerProbeEnabled = true
                                }
                                val newPoint = Point(screenToModel(e.x, offsetX).roundToInt(), screenToModel(e.y, offsetY).roundToInt())
                                // Only add if not already the last point (avoid duplicate from double click first click)
                                if (rulerMarkers.isEmpty() || rulerMarkers.last() != newPoint) {
                                    rulerMarkers.add(newPoint)
                                }
                            }
                        }
                        repaint()
                        return
                    }

                    // Check if origin is clicked
                    val ox = modelToScreen(0.0, offsetX)
                    val oy = modelToScreen(0.0, offsetY)
                    if (Rectangle(ox - 10, oy - 10, 20, 20).contains(e.point)) {
                        draggingOrigin = true
                        originDragStart = e.point
                        initialOffsetX = offsetX
                        initialOffsetY = offsetY
                        initialElementsPositions = elements.map { Point(it.x, it.y) }
                        return
                    }

                    activeHandle = getHandleUnderMouse(e.point)
                    if (activeHandle != ResizeHandle.NONE) {
                        dragStart = e.point
                        val el = selectedElement!!
                        initialElementBounds = Rectangle(el.x, el.y, el.width, el.height)
                        return
                    }

                    selectedElement = elements.reversed().find { 
                        it.contains(screenToModel(e.x, offsetX).roundToInt(), screenToModel(e.y, offsetY).roundToInt())
                    }
                    if (selectedElement != null) {
                        dragStart = e.point
                        val el = selectedElement!!
                        elementStatsPanel.updateElementStats(el)
                        initialElementBounds = Rectangle(el.x, el.y, el.width, el.height)
                        if (el is FloorOpening) {
                            initialVertices = el.vertices.map { Point(it.x, it.y) }
                        } else {
                            initialVertices = null
                        }
                        sidePanel.updateFields(selectedElement!!)

                        elementsToMoveWithWall.clear()
                        initialChildrenVertices.clear()
                        if (el is Wall || el is Room) {
                            elements.forEach { other ->
                                if (other is PlanWindow || other is Door || other is Stairs || other is FloorOpening) {
                                    if (el.getBounds().contains(other.getBounds())) {
                                        elementsToMoveWithWall.add(other to Point(other.x - el.x, other.y - el.y))
                                        if (other is FloorOpening) {
                                            initialChildrenVertices.add(other to other.vertices.map { Point(it.x, it.y) })
                                        }
                                    }
                                }
                            }
                        }
                        if (e.isPopupTrigger) {
                            showPopup(e)
                        }
                    } else {
                        selectedElement = null
                        elementStatsPanel.updateElementStats(null)
                        sidePanel.clearFields()
                        if (e.isPopupTrigger) {
                            showPopup(e)
                        } else {
                            panStart = e.point
                            initialOffsetX = offsetX
                            initialOffsetY = offsetY
                        }
                    }
                    repaint()
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (currentMode == AppMode.DRAG) {
                        val pStart = panStart
                        val initOX = initialOffsetX
                        val initOY = initialOffsetY
                        if (pStart != null && initOX != null && initOY != null) {
                            val dx = screenToModel(e.x, 0.0) - screenToModel(pStart.x, 0.0)
                            val dy = screenToModel(e.y, 0.0) - screenToModel(pStart.y, 0.0)
                            offsetX = initOX + dx
                            offsetY = initOY + dy
                        }
                        repaint()
                        return
                    }

                    if (currentMode == AppMode.RULER) {
                        rulerProbePoint = Point(screenToModel(e.x, offsetX).roundToInt(), screenToModel(e.y, offsetY).roundToInt())
                        repaint()
                        return
                    }

                    if (draggingOrigin) {
                        val start = originDragStart
                        val initOX = initialOffsetX
                        val initOY = initialOffsetY
                        val initPos = initialElementsPositions
                        if (start != null && initOX != null && initOY != null && initPos != null) {
                            val dxScreen = e.x - start.x
                            val dyScreen = e.y - start.y
                            val dxModel = dxScreen / (scale * pixelsPerCm)
                            val dyModel = dyScreen / (scale * pixelsPerCm)
                            
                            offsetX = initOX + dxModel
                            offsetY = initOY + dyModel
                            
                            elements.forEachIndexed { index, el ->
                                val initial = initPos[index]
                                el.x = (initial.x - dxModel).roundToInt()
                                el.y = (initial.y - dyModel).roundToInt()
                            }
                            selectedElement?.let { sidePanel.updateFields(it) }
                            statsPanel.update()
                        }
                        repaint()
                        return
                    }

                    val start = dragStart
                    val initial = initialElementBounds
                    val element = selectedElement

                    if (element != null && start != null && initial != null) {
                        val dx = (screenToModel(e.x, 0.0) - screenToModel(start.x, 0.0)).roundToInt()
                        val dy = (screenToModel(e.y, 0.0) - screenToModel(start.y, 0.0)).roundToInt()
                        
                        // dx and dy are in model cm

                        if (activeHandle != ResizeHandle.NONE) {
                                if (activeHandle is Int && element is FloorOpening) {
                                    val idx = activeHandle as Int
                                    val mx = screenToModel(e.point.x, offsetX).roundToInt()
                                    val my = screenToModel(e.point.y, offsetY).roundToInt()
                                    
                                    // Sticky edges for polygon vertices
                                    val sx_sticky = getStickyCoord(mx, 0, true)
                                    val sy_sticky = getStickyCoord(my, 0, false)
                                    
                                    val oldX = element.vertices[idx].x
                                    val oldY = element.vertices[idx].y
                                    
                                    element.vertices[idx].x = sx_sticky
                                    element.vertices[idx].y = sy_sticky
                                    element.updateBounds()
                                    
                                    if (findContainingRoomForFloorOpening(element) == null) {
                                        // Try move only X
                                        element.vertices[idx].y = oldY
                                        element.updateBounds()
                                        if (findContainingRoomForFloorOpening(element) == null) {
                                            // Try move only Y
                                            element.vertices[idx].x = oldX
                                            element.vertices[idx].y = sy_sticky
                                            element.updateBounds()
                                            if (findContainingRoomForFloorOpening(element) == null) {
                                                // Snap back fully
                                                element.vertices[idx].y = oldY
                                                element.updateBounds()
                                            }
                                        }
                                    }
                                } else if (activeHandle is ResizeHandle) {
                                var newX = initial.x
                                var newY = initial.y
                                var newW = initial.width
                                var newH = initial.height

                                when (activeHandle) {
                                    ResizeHandle.N -> {
                                        newY = getStickyCoord(initial.y + dy, 0, false)
                                        newH = initial.y + initial.height - newY
                                    }
                                    ResizeHandle.S -> {
                                        newH = getStickyCoord(initial.y + initial.height + dy, 0, false) - newY
                                    }
                                    ResizeHandle.W -> {
                                        newX = getStickyCoord(initial.x + dx, 0, true)
                                        newW = initial.x + initial.width - newX
                                    }
                                    ResizeHandle.E -> {
                                        newW = getStickyCoord(initial.x + initial.width + dx, 0, true) - newX
                                    }
                                    ResizeHandle.NW -> {
                                        newX = getStickyCoord(initial.x + dx, 0, true)
                                        newW = initial.x + initial.width - newX
                                        newY = getStickyCoord(initial.y + dy, 0, false)
                                        newH = initial.y + initial.height - newY
                                    }
                                    ResizeHandle.NE -> {
                                        newW = getStickyCoord(initial.x + initial.width + dx, 0, true) - newX
                                        newY = getStickyCoord(initial.y + dy, 0, false)
                                        newH = initial.y + initial.height - newY
                                    }
                                    ResizeHandle.SW -> {
                                        newX = getStickyCoord(initial.x + dx, 0, true)
                                        newW = initial.x + initial.width - newX
                                        newH = getStickyCoord(initial.y + initial.height + dy, 0, false) - newY
                                    }
                                    ResizeHandle.SE -> {
                                        newW = getStickyCoord(initial.x + initial.width + dx, 0, true) - newX
                                        newH = getStickyCoord(initial.y + initial.height + dy, 0, false) - newY
                                    }
                                    else -> {}
                                }

                                // Minimum size constraint
                                val minSize = 5
                                if (newW < minSize) {
                                    if (activeHandle == ResizeHandle.W || activeHandle == ResizeHandle.NW || activeHandle == ResizeHandle.SW) {
                                        newX = initial.x + initial.width - minSize
                                    }
                                    newW = minSize
                                }
                                if (newH < minSize) {
                                    if (activeHandle == ResizeHandle.N || activeHandle == ResizeHandle.NW || activeHandle == ResizeHandle.NE) {
                                        newY = initial.y + initial.height - minSize
                                    }
                                    newH = minSize
                                }

                                // Constraints for Window and Door (must be inside a Wall)
                                if (element is PlanWindow || element is Door) {
                                    val wall = findContainingWall(newX, newY, newW, newH)
                                    if (wall != null) {
                                        element.x = newX
                                        element.y = newY
                                        element.width = newW
                                        element.height = newH
                                    }
                                } else if (element is Stairs || element is FloorOpening) {
                                    val room = if (element is Stairs) {
                                        findContainingRoom(newX, newY, newW, newH)
                                    } else {
                                        // For FloorOpening, standard rectangular resize handle dragging isn't implemented 
                                        // (only vertex dragging via Int handle).
                                        // If it was, we'd need to check bounds here.
                                        null
                                    }
                                    
                                    if (element is Stairs && room != null) {
                                        element.x = newX
                                        element.y = newY
                                        element.width = newW
                                        element.height = newH
                                    }
                                } else {
                                    element.x = newX
                                    element.y = newY
                                    element.width = newW
                                    element.height = newH

                                    if (element is Wall || element is Room) {
                                    val dx_move = element.x - initial.x
                                    val dy_move = element.y - initial.y
                                    
                                    val toRemove = mutableListOf<PlanElement>()
                                    
                                    elementsToMoveWithWall.forEach { (child, offset) ->
                                        val oldX = child.x
                                        val oldY = child.y
                                        child.x = element.x + offset.x
                                        child.y = element.y + offset.y
                                        
                                        if (element is Wall && (child is PlanWindow || child is Door)) {
                                            if (!element.getBounds().contains(child.getBounds())) {
                                                toRemove.add(child)
                                            }
                                        } else if (element is Room && (child is Stairs || child is FloorOpening)) {
                                            if (!element.getBounds().contains(child.getBounds())) {
                                                toRemove.add(child)
                                            }
                                        }
                                    }
                                    
                                    initialChildrenVertices.forEach { (es, vertices) ->
                                        val dx_v = element.x - initial.x
                                        val dy_v = element.y - initial.y
                                        es.vertices.clear()
                                        vertices.forEach { v ->
                                            es.vertices.add(Point(v.x + dx_v, v.y + dy_v))
                                        }
                                        es.updateBounds()
                                        
                                        if (element is Room) {
                                            if (!element.getBounds().contains(es.getBounds())) {
                                                toRemove.add(es)
                                            }
                                        }
                                    }
                                    
                                    if (toRemove.isNotEmpty()) {
                                        // This is tricky during drag... for now we just don't allow it or move it back
                                        // But dragging a room/wall should ideally shift children.
                                        // If they go out, we could remove them but it's annoying during active drag.
                                        // For now, let's just keep them. The final drop will be checked if needed.
                                        // Actually, the previous implementation for Wall DID remove them in some cases?
                                        // Let's look at what was there.
                                    }

                                    if (element is Wall) {
                                            val isVertical = element.width < element.height
                                            val thickness = if (isVertical) element.width else element.height
                                            
                                            // Update all elements that WERE inside the wall
                                            elementsToMoveWithWall.forEach { (child, offset) ->
                                                if (child is PlanWindow || child is Door) {
                                                    if (isVertical) {
                                                        child.width = thickness
                                                        child.x = element.x
                                                    } else {
                                                        child.height = thickness
                                                        child.y = element.y
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            var newX = (initial.x + dx)
                            var newY = (initial.y + dy)

                            // Sticky edges (in cm)
                            newX = getStickyCoord(newX, element.width, true)
                            newY = getStickyCoord(newY, element.height, false)

                            // Constraints for Window and Door (must be inside a Wall)
                            if (element is PlanWindow || element is Door) {
                                val wall = findContainingWall(newX, newY, element.width, element.height)
                                if (wall != null) {
                                    element.x = newX
                                    element.y = newY
                                } else {
                                    val currentWall = findContainingWall(element.x, element.y, element.width, element.height)
                                    if (currentWall != null) {
                                        var adjusted = false
                                        val movedXInside = findContainingWall(newX, element.y, element.width, element.height)
                                        if (movedXInside != null) {
                                            element.x = newX
                                            adjusted = true
                                        } else {
                                            // Clamp X to wall bounds
                                            if (newX < currentWall.x) element.x = currentWall.x
                                            else if (newX + element.width > currentWall.x + currentWall.width) element.x = currentWall.x + currentWall.width - element.width
                                            // Adjusted is false because we didn't fully move to newX
                                        }
                                        
                                        val movedYInside = findContainingWall(element.x, newY, element.width, element.height)
                                        if (movedYInside != null) {
                                            element.y = newY
                                            adjusted = true
                                        } else {
                                            // Clamp Y to wall bounds
                                            if (newY < currentWall.y) element.y = currentWall.y
                                            else if (newY + element.height > currentWall.y + currentWall.height) element.y = currentWall.y + currentWall.height - element.height
                                        }
                                        
                                        // Adjust dragStart to avoid "delay" when moving back
                                        // We want: screenToModel(e.x) - screenToModel(dragStart.x) == element.x - initial.x
                                        // So: screenToModel(dragStart.x) = screenToModel(e.x) - (element.x - initial.x)
                                        val modelDragStartX = screenToModel(e.x, 0.0) - (element.x - initial.x)
                                        val modelDragStartY = screenToModel(e.y, 0.0) - (element.y - initial.y)
                                        dragStart = Point(modelToScreen(modelDragStartX, 0.0), modelToScreen(modelDragStartY, 0.0))
                                    }
                                }
                            } else {
                                val dx_m = newX - initial.x
                                val dy_m = newY - initial.y
                                element.x = newX
                                element.y = newY
                                
                                if (element is Wall || element is Room) {
                                    val dx_move = (newX - initial.x)
                                    val dy_move = (newY - initial.y)
                                    
                                    element.x = newX
                                    element.y = newY
                                    
                                    elementsToMoveWithWall.forEach { (child, offset) ->
                                        child.x = element.x + offset.x
                                        child.y = element.y + offset.y
                                    }
                                    initialChildrenVertices.forEach { (es, vertices) ->
                                        es.vertices.clear()
                                        vertices.forEach { v ->
                                            es.vertices.add(Point(v.x + dx_move, v.y + dy_move))
                                        }
                                        es.updateBounds()
                                    }
                                }
                                if (element is FloorOpening && initialVertices != null) {
                                    val dx_m = newX - initial.x
                                    val dy_m = newY - initial.y
                                    
                                    val tempVertices = initialVertices!!.map { Point(it.x + dx_m, it.y + dy_m) }.toMutableList()
                                    val tempES = FloorOpening(tempVertices)
                                    if (findContainingRoomForFloorOpening(tempES) != null) {
                                        element.vertices.clear()
                                        element.vertices.addAll(tempVertices)
                                        element.x = newX
                                        element.y = newY
                                        element.updateBounds()
                                    }
                                }
                                
                                // Constraints for EmptySpace and Stairs (must be inside a Room)
                                    if (element is Stairs) {
                                        val room = findContainingRoom(newX, newY, element.width, element.height)
                                        if (room != null) {
                                            element.x = newX
                                            element.y = newY
                                        } else {
                                            val currentRoom = findContainingRoom(element.x, element.y, element.width, element.height)
                                            if (currentRoom != null) {
                                                if (findContainingRoom(newX, element.y, element.width, element.height) != null) {
                                                    element.x = newX
                                                } else {
                                                    if (newX < currentRoom.x) element.x = currentRoom.x
                                                    else if (newX + element.width > currentRoom.x + currentRoom.width) element.x = currentRoom.x + currentRoom.width - element.width
                                                }
                                                if (findContainingRoom(element.x, newY, element.width, element.height) != null) {
                                                    element.y = newY
                                                } else {
                                                    if (newY < currentRoom.y) element.y = currentRoom.y
                                                    else if (newY + element.height > currentRoom.y + currentRoom.height) element.y = currentRoom.y + currentRoom.height - element.height
                                                }
                                                val modelDragStartX = screenToModel(e.x, 0.0) - (element.x - initial.x)
                                                val modelDragStartY = screenToModel(e.y, 0.0) - (element.y - initial.y)
                                                dragStart = Point(modelToScreen(modelDragStartX, 0.0), modelToScreen(modelDragStartY, 0.0))
                                            }
                                        }
                                    } else if (element is FloorOpening && initialVertices != null) {
                                        val dx_m = newX - initial.x
                                        val dy_m = newY - initial.y
                                        
                                        val tempVertices = initialVertices!!.map { Point(it.x + dx_m, it.y + dy_m) }.toMutableList()
                                        val tempES = FloorOpening(tempVertices)
                                        if (findContainingRoomForFloorOpening(tempES) != null) {
                                            element.vertices.clear()
                                            element.vertices.addAll(tempVertices)
                                            element.x = newX
                                            element.y = newY
                                            element.updateBounds()
                                        } else {
                                            // Handle sticking for FloorOpening movement
                                            val currentRoom = findContainingRoomForFloorOpening(element)
                                            if (currentRoom != null) {
                                                // Try X move
                                                val tempVerticesX = initialVertices!!.map { Point(it.x + dx_m, it.y + (element.y - initial.y)) }.toMutableList()
                                                if (findContainingRoomForFloorOpening(FloorOpening(tempVerticesX)) != null) {
                                                    element.vertices.clear()
                                                    element.vertices.addAll(tempVerticesX)
                                                    element.x = newX
                                                }
                                                // Try Y move
                                                val tempVerticesY = initialVertices!!.map { Point(it.x + (element.x - initial.x), it.y + dy_m) }.toMutableList()
                                                if (findContainingRoomForFloorOpening(FloorOpening(tempVerticesY)) != null) {
                                                    element.vertices.clear()
                                                    element.vertices.addAll(tempVerticesY)
                                                    element.y = newY
                                                }
                                                element.updateBounds()
                                                
                                                val modelDragStartX = screenToModel(e.x, 0.0) - (element.x - initial.x)
                                                val modelDragStartY = screenToModel(e.y, 0.0) - (element.y - initial.y)
                                                dragStart = Point(modelToScreen(modelDragStartX, 0.0), modelToScreen(modelDragStartY, 0.0))
                                            }
                                        }
                                    }
                            }
                        }

                        sidePanel.updateFields(element)
                        statsPanel.update()
                    } else {
                        val pStart = panStart
                        val initOX = initialOffsetX
                        val initOY = initialOffsetY
                        if (pStart != null && initOX != null && initOY != null) {
                            val dx = screenToModel(e.x, 0.0) - screenToModel(pStart.x, 0.0)
                            val dy = screenToModel(e.y, 0.0) - screenToModel(pStart.y, 0.0)
                            offsetX = initOX + dx
                            offsetY = initOY + dy
                        }
                    }
                    repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (dragStart != null || activeHandle != ResizeHandle.NONE || draggingOrigin) {
                        saveState()
                    }
                    if (e.isPopupTrigger && currentMode == AppMode.NORMAL) {
                        showPopup(e)
                    }
                    draggingOrigin = false
                    originDragStart = null
                    initialElementsPositions = null
                    dragStart = null
                    initialElementBounds = null
                    panStart = null
                    initialOffsetX = null
                    initialOffsetY = null
                    activeHandle = ResizeHandle.NONE
                }
            }
            addMouseListener(mouseAdapter)
            addMouseMotionListener(mouseAdapter)
            addMouseWheelListener { e ->
                val mousePos = e.point
                
                // Logical point under mouse before zoom
                val mouseModelX = screenToModel(mousePos.x, offsetX)
                val mouseModelY = screenToModel(mousePos.y, offsetY)

                val rotation = e.preciseWheelRotation
                val factor = 1.1.pow(-rotation)
                scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        
                // Adjust offsets to zoom towards mouse position
                offsetX = mousePos.x / (scale * pixelsPerCm) - mouseModelX
                offsetY = mousePos.y / (scale * pixelsPerCm) - mouseModelY
                
                updateScaleLabel()
                repaint()
            }
        }

        private fun showPopup(e: MouseEvent) {
            // Select element under mouse before showing popup
            selectedElement = elements.reversed().find { 
                it.contains(screenToModel(e.x, offsetX).roundToInt(), screenToModel(e.y, offsetY).roundToInt())
            }
            if (selectedElement != null) {
                elementStatsPanel.updateElementStats(selectedElement)
                sidePanel.updateFields(selectedElement!!)
            } else {
                elementStatsPanel.updateElementStats(null)
                sidePanel.clearFields()
            }
            canvas.repaint()

            val hasSelection = selectedElement != null
            val isWall = selectedElement is Wall
            val isRoom = selectedElement is Room

            // If empty space: Add Wall, Add Room, Add Floor Opening
            // If selection: Duplicate, Remove, [Add Window/Door if Wall], [Add Stairs/Floor Opening if Room]

            if (!hasSelection) {
                popAddWallMenu.isVisible = true
                popAddRoomMenu.isVisible = true
                
                // Only show "Add Floor Opening" if clicking over a room
                val roomAtPoint = elements.reversed().filterIsInstance<Room>().find { 
                    it.contains(screenToModel(e.x, offsetX).roundToInt(), screenToModel(e.y, offsetY).roundToInt())
                }
                popAddFloorOpeningMenu.isVisible = roomAtPoint != null
            
                popSepGeneral.isVisible = false
                popDuplicateMenu.isVisible = false
                popRemoveMenu.isVisible = false
                popSepElements.isVisible = false
                popAddWindowMenu.isVisible = false
                popAddDoorMenu.isVisible = false
                popSepRoom.isVisible = false
                popAddStairsMenu.isVisible = false
            } else {
                popAddWallMenu.isVisible = false
                popAddRoomMenu.isVisible = false
                // "Add Floor opening" is visible only if selected element is a Room
                popAddFloorOpeningMenu.isVisible = isRoom
            
                popSepGeneral.isVisible = true
                popDuplicateMenu.isVisible = true
                popRemoveMenu.isVisible = true
            
                popSepElements.isVisible = isWall
                popAddWindowMenu.isVisible = isWall
                popAddDoorMenu.isVisible = isWall
            
                popSepRoom.isVisible = isRoom
                popAddStairsMenu.isVisible = isRoom
            }
        
            popupMenu.show(e.component, e.x, e.y)
        }

        private fun getStickyCoord(coord: Int, size: Int, isX: Boolean): Int {
            var bestCoord = coord
            var minDiff = STICK_THRESHOLD + 1

            for (other in elements) {
                if (other === selectedElement) continue
                
                val otherBounds = other.getBounds()
                
                val positions = if (isX) {
                    listOf(otherBounds.x, otherBounds.x + otherBounds.width, otherBounds.x - size, otherBounds.x + otherBounds.width - size)
                } else {
                    listOf(otherBounds.y, otherBounds.y + otherBounds.height, otherBounds.y - size, otherBounds.y + otherBounds.height - size)
                }

                for (pos in positions) {
                    val diff = abs(coord - pos)
                    if (diff < minDiff) {
                        minDiff = diff
                        bestCoord = pos
                    }
                }
            }
            return bestCoord
        }


        private fun getRoomGroups(): List<List<Room>> {
            val rooms = elements.filterIsInstance<Room>()
            if (rooms.isEmpty()) return emptyList()

            val adjacency = mutableMapOf<Room, MutableSet<Room>>()
            for (i in rooms.indices) {
                for (j in i + 1 until rooms.size) {
                    val r1 = rooms[i]
                    val r2 = rooms[j]
                    if (areAdjacent(r1, r2)) {
                        adjacency.getOrPut(r1) { mutableSetOf() }.add(r2)
                        adjacency.getOrPut(r2) { mutableSetOf() }.add(r1)
                    }
                }
            }

            val groups = mutableListOf<List<Room>>()
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
                    groups.add(group)
                }
            }
            return groups
        }

        private fun areAdjacent(r1: Room, r2: Room): Boolean {
            // Check if they share a border. They are adjacent if their rectangles touch but don't necessarily overlap.
            // intersection of expanded rectangles
            val rect1 = r1.getBounds()
            val rect2 = r2.getBounds()
            
            // Check horizontal adjacency (r1 is left or right of r2)
            if (rect1.y < rect2.y + rect2.height && rect1.y + rect1.height > rect2.y) {
                if (rect1.x == rect2.x + rect2.width || rect2.x == rect1.x + rect1.width) return true
            }
            // Check vertical adjacency (r1 is above or below r2)
            if (rect1.x < rect2.x + rect2.width && rect1.x + rect1.width > rect2.x) {
                if (rect1.y == rect2.y + rect2.height || rect2.y == rect1.y + rect1.height) return true
            }
            
            return false
        }

        private fun getDockedSequences(isHorizontal: Boolean): List<List<Room>> {
            val rooms = elements.filterIsInstance<Room>()
            if (rooms.isEmpty()) return emptyList()

            val adjacency = mutableMapOf<Room, MutableSet<Room>>()
            for (i in rooms.indices) {
                for (j in i + 1 until rooms.size) {
                    val r1 = rooms[i]
                    val r2 = rooms[j]
                    val rect1 = r1.getBounds()
                    val rect2 = r2.getBounds()
                    
                    var isDocked = false
                    if (isHorizontal) {
                        // Docked horizontally: share a vertical edge
                        if (rect1.y < rect2.y + rect2.height && rect1.y + rect1.height > rect2.y) {
                            if (rect1.x == rect2.x + rect2.width || rect2.x == rect1.x + rect1.width) isDocked = true
                        }
                    } else {
                        // Docked vertically: share a horizontal edge
                        if (rect1.x < rect2.x + rect2.width && rect1.x + rect1.width > rect2.x) {
                            if (rect1.y == rect2.y + rect2.height || rect2.y == rect1.y + rect1.height) isDocked = true
                        }
                    }

                    if (isDocked) {
                        adjacency.getOrPut(r1) { mutableSetOf() }.add(r2)
                        adjacency.getOrPut(r2) { mutableSetOf() }.add(r1)
                    }
                }
            }

            val groups = mutableListOf<List<Room>>()
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
                    if (group.size > 1) {
                        groups.add(group)
                    }
                }
            }
            return groups
        }

        private fun getCorrectedRoomArea(room: Room): Double {
            val groups = getRoomGroups()
            val group = groups.find { room in it } ?: listOf(room)
            
            val largestRoom = group.maxByOrNull { it.getArea() }
            if (room !== largestRoom) return 0.0 // Area is only shown for the largest room

            var totalArea = 0.0
            for (r in group) {
                totalArea += r.getArea()
                val rBounds = r.getBounds()
                val nested = elements.filter { (it is Stairs || it is FloorOpening) && rBounds.contains(it.getBounds()) }
                for (el in nested) {
                    totalArea -= el.getArea()
                }
            }
            return totalArea
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            drawScene(g2, offsetX, offsetY, scale, width, height)
        }

        fun drawScene(g2: Graphics2D, offX: Double, offY: Double, sc: Double, w: Int, h: Int) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            if (!isExporting) {
                // Draw axes
                g2.color = Color.LIGHT_GRAY
                val axisX = modelToScreen(0.0, offX, sc)
                val axisY = modelToScreen(0.0, offY, sc)
                g2.drawLine(0, axisY, w, axisY)
                g2.drawLine(axisX, 0, axisX, h)

                // Draw origin handle (cross)
                g2.color = Color.BLUE
                g2.setStroke(BasicStroke(2f))
                g2.drawLine(axisX - 10, axisY, axisX + 10, axisY)
                g2.drawLine(axisX, axisY - 10, axisX, axisY + 10)
                g2.drawOval(axisX - 5, axisY - 5, 10, 10)
                g2.setStroke(BasicStroke(1f))
                
                // Axis labels/ticks
                g2.color = Color.GRAY
                val step = 100 // every 100cm

                // Draw grid lines
                val gridStroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(2f, 2f), 0f)
                val oldStroke = g2.stroke
                g2.stroke = gridStroke
                g2.color = Color(220, 220, 220) // Very light gray for grid

                // X grid lines
                var gridX = (screenToModel(0, offX, sc) / step).roundToInt() * step
                while (modelToScreen(gridX.toDouble(), offX, sc) < w) {
                    val sx = modelToScreen(gridX.toDouble(), offX, sc)
                    g2.drawLine(sx, 0, sx, h)
                    gridX += step
                }

                // Y grid lines
                var gridY = (screenToModel(0, offY, sc) / step).roundToInt() * step
                while (modelToScreen(gridY.toDouble(), offY, sc) < h) {
                    val sy = modelToScreen(gridY.toDouble(), offY, sc)
                    g2.drawLine(0, sy, w, sy)
                    gridY += step
                }
                g2.stroke = oldStroke
                
                // X axis ticks
                g2.color = Color.GRAY
                var startX = (screenToModel(0, offX, sc) / step).roundToInt() * step
                while (modelToScreen(startX.toDouble(), offX, sc) < w) {
                    val sx = modelToScreen(startX.toDouble(), offX, sc)
                    g2.drawLine(sx, axisY - 5, sx, axisY + 5)
                    if (startX != 0) {
                        val label = if (abs(startX) % 100 == 0) "${startX / 100}m" else "${startX}cm"
                        g2.drawString(label, sx + 2, axisY - 2)
                    } else {
                        g2.drawString("0", sx + 2, axisY - 2)
                    }
                    startX += step
                }

                // Y axis ticks
                var startY = (screenToModel(0, offY, sc) / step).roundToInt() * step
                while (modelToScreen(startY.toDouble(), offY, sc) < h) {
                    val sy = modelToScreen(startY.toDouble(), offY, sc)
                    g2.drawLine(axisX - 5, sy, axisX + 5, sy)
                    if (startY != 0) {
                        val label = if (abs(startY) % 100 == 0) "${startY / 100}m" else "${startY}cm"
                        val metrics = g2.fontMetrics
                        val labelWidth = metrics.stringWidth(label)
                        g2.drawString(label, axisX - labelWidth - 7, sy - 2)
                    }
                    startY += step
                }
            }

            // Draw order: Rooms, FloorOpening, Walls, Stairs, Windows/Doors
            val rooms = elements.filterIsInstance<Room>()
            val floorOpenings = elements.filterIsInstance<FloorOpening>()
            val walls = elements.filterIsInstance<Wall>()
            val stairs = elements.filterIsInstance<Stairs>()
            val attachments = elements.filter { it is PlanWindow || it is Door }
            
            rooms.forEach { drawElement(g2, it) }
            floorOpenings.forEach { drawElement(g2, it) }
            walls.forEach { drawElement(g2, it) }
            stairs.forEach { drawElement(g2, it) }
            attachments.forEach { drawElement(g2, it) }

            // Draw overlap regions
            val intersections = calculateIntersections()
            if (intersections.isNotEmpty()) {
                val stripeWidth = 10
                val stripeImage = BufferedImage(stripeWidth, stripeWidth, BufferedImage.TYPE_INT_ARGB)
                val gStripe = stripeImage.createGraphics()
                // Different-looking: use RED/YELLOW instead of BLACK/YELLOW for generic intersections
                gStripe.color = Color.YELLOW
                gStripe.fillRect(0, 0, stripeWidth, stripeWidth)
                gStripe.color = Color.RED
                gStripe.stroke = BasicStroke(2f)
                gStripe.drawLine(0, stripeWidth, stripeWidth, 0)
                gStripe.dispose()

                val anchor = Rectangle(0, 0, stripeWidth, stripeWidth)
                val paint = TexturePaint(stripeImage, anchor)
                val oldPaint = g2.paint
                g2.paint = paint

                for (info in intersections) {
                    val rect = info.rect
                    val sx = modelToScreen(rect.x.toDouble(), offX, sc)
                    val sy = modelToScreen(rect.y.toDouble(), offY, sc)
                    val sw = modelToScreen((rect.x + rect.width).toDouble(), offX, sc) - sx
                    val sh = modelToScreen((rect.y + rect.height).toDouble(), offY, sc) - sy
                    g2.fillRect(sx, sy, sw, sh)
                }
                g2.paint = oldPaint
            }

            // Draw selection markers ALWAYS on top
            selectedElement?.let { el ->
                val sx = modelToScreen(el.x.toDouble(), offX, sc)
                val sy = modelToScreen(el.y.toDouble(), offY, sc)
                val sw = modelToScreen((el.x + el.width).toDouble(), offX, sc) - sx
                val sh = modelToScreen((el.y + el.height).toDouble(), offY, sc) - sy
                drawSelection(g2, sx, sy, sw, sh)
            }

            // Draw ruler
            if (rulerMarkers.isNotEmpty() || (rulerProbePoint != null && rulerProbeEnabled)) {
                g2.color = Color.MAGENTA
                val rulerStroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(5f, 5f), 0f)
                val solidStroke = BasicStroke(2f)
            
                var lastP: Point? = null
                var totalDist = 0.0
            
                for (m in rulerMarkers) {
                    val sx = modelToScreen(m.x.toDouble(), offX, sc)
                    val sy = modelToScreen(m.y.toDouble(), offY, sc)
                
                    g2.fillOval(sx - 4, sy - 4, 8, 8)
                
                    if (lastP != null) {
                        g2.stroke = solidStroke
                        val lsx = modelToScreen(lastP.x.toDouble(), offX, sc)
                        val lsy = modelToScreen(lastP.y.toDouble(), offY, sc)
                        g2.drawLine(lsx, lsy, sx, sy)
                    
                        val dist = lastP.distance(m)
                        totalDist += dist
                    
                        // Draw segment length
                        val label = "%.1f cm".format(dist)
                        g2.drawString(label, (lsx + sx) / 2 + 5, (lsy + sy) / 2 - 5)
                    }
                    lastP = m
                }

                if (rulerClosed && rulerMarkers.size > 2) {
                    val first = rulerMarkers.first()
                    val last = rulerMarkers.last()
                    val fsx = modelToScreen(first.x.toDouble(), offX, sc)
                    val fsy = modelToScreen(first.y.toDouble(), offY, sc)
                    val lsx = modelToScreen(last.x.toDouble(), offX, sc)
                    val lsy = modelToScreen(last.y.toDouble(), offY, sc)
                    
                    g2.stroke = solidStroke
                    g2.drawLine(lsx, lsy, fsx, fsy)
                    
                    val dist = first.distance(last)
                    totalDist += dist
                    val label = "%.1f cm (Total: %.1f cm)".format(dist, totalDist)
                    g2.drawString(label, (lsx + fsx) / 2 + 5, (lsy + fsy) / 2 - 5)
                }
            
                if (rulerProbeEnabled) {
                    rulerProbePoint?.let { probe ->
                        val sx = modelToScreen(probe.x.toDouble(), offX, sc)
                        val sy = modelToScreen(probe.y.toDouble(), offY, sc)
                    
                        if (lastP != null) {
                            g2.stroke = rulerStroke
                            val lsx = modelToScreen(lastP.x.toDouble(), offX, sc)
                            val lsy = modelToScreen(lastP.y.toDouble(), offY, sc)
                            g2.drawLine(lsx, lsy, sx, sy)
                        
                            val dist = lastP.distance(probe)
                            totalDist += dist
                        
                            val label = "%.1f cm (Total: %.1f cm)".format(dist, totalDist)
                            g2.drawString(label, sx + 10, sy + 10)
                        } else {
                            // Just the probe point if no markers yet
                            g2.fillOval(sx - 2, sy - 2, 4, 4)
                            g2.drawString("0.0 cm", sx + 10, sy + 10)
                        }
                    }
                }
                g2.stroke = solidStroke
            }

            // Draw room areas at the end
            val groups = getRoomGroups()
            for (group in groups) {
                val largestRoom = group.maxByOrNull { it.getArea() } ?: continue
                
                var groupNominalArea = 0.0
                var groupCorrectedArea = 0.0
                for (r in group) {
                    groupNominalArea += r.getArea()
                    
                    var rCorrected = r.getArea()
                    val rBounds = r.getBounds()
                    val nested = elements.filter { (it is Stairs || it is FloorOpening) && rBounds.contains(it.getBounds()) }
                    for (el in nested) {
                        rCorrected -= el.getArea()
                    }
                    groupCorrectedArea += rCorrected
                }

                val sx = modelToScreen(largestRoom.x.toDouble(), offX, sc)
                val sy = modelToScreen(largestRoom.y.toDouble(), offY, sc)
                val sw = modelToScreen((largestRoom.x + largestRoom.width).toDouble(), offX, sc) - sx
                val sh = modelToScreen((largestRoom.y + largestRoom.height).toDouble(), offY, sc) - sy

                g2.color = Color.BLACK
                val areaLabel = if (abs(groupNominalArea - groupCorrectedArea) > 0.1) {
                    "%.2f* m²".format(groupCorrectedArea / 10000.0)
                } else {
                    "%.2f m²".format(groupNominalArea / 10000.0)
                }
                
                val metrics = g2.fontMetrics
                val labelWidth = metrics.stringWidth(areaLabel)
                val labelHeight = metrics.ascent
                
                // Centering the label in the largest room of the group
                // Use ascent for better vertical centering (baseline is used in drawString)
                val xPos = sx + (sw - labelWidth) / 2
                val yPos = sy + (sh + labelHeight) / 2
                g2.drawString(areaLabel, xPos, yPos)
            }

            if (showDimensionLabels || isExporting) {
                drawDimensionLabels(g2)
            }
        }

        private fun drawDimensionLabels(g2: Graphics2D) {
            val rooms = elements.filterIsInstance<Room>()
            val margin = 30 // cm margin from room edge
            
            g2.color = Color.BLUE
            g2.setStroke(BasicStroke(1f))
            val metrics = g2.fontMetrics
            
            val roomsInHorizontalSequence = mutableSetOf<Room>()
            val roomsInVerticalSequence = mutableSetOf<Room>()
            
            val horizontalSequences = getDockedSequences(true)
            val verticalSequences = getDockedSequences(false)
            
            for (seq in horizontalSequences) {
                // Find largest common rectangle
                var commonMinY = Int.MIN_VALUE
                var commonMaxY = Int.MAX_VALUE
                var totalMinX = Int.MAX_VALUE
                var totalMaxX = Int.MIN_VALUE
                
                for (r in seq) {
                    commonMinY = maxOf(commonMinY, r.y)
                    commonMaxY = minOf(commonMaxY, r.y + r.height)
                    totalMinX = minOf(totalMinX, r.x)
                    totalMaxX = maxOf(totalMaxX, r.x + r.width)
                }
                
                if (commonMaxY > commonMinY) {
                    // Draw horizontal axis along center of common rectangle
                    val centerY = (commonMinY + commonMaxY) / 2.0
                    val scY = modelToScreen(centerY, offsetY)
                    
                    val sscx1 = modelToScreen(totalMinX.toDouble(), offsetX)
                    val sscx2 = modelToScreen(totalMaxX.toDouble(), offsetX)
                    
                    val totalWidth = totalMaxX - totalMinX
                    val label = "$totalWidth cm"
                    val lw = metrics.stringWidth(label)
                    
                    drawTwoHeadArrowLine(g2, sscx1, scY, sscx2, scY)
                    g2.drawString(label, sscx1 + (sscx2 - sscx1 - lw) / 2, scY - 5)
                    
                    for (r in seq) {
                        roomsInHorizontalSequence.add(r)
                    }
                }
            }
            
            for (seq in verticalSequences) {
                // Find largest common rectangle
                var commonMinX = Int.MIN_VALUE
                var commonMaxX = Int.MAX_VALUE
                var totalMinY = Int.MAX_VALUE
                var totalMaxY = Int.MIN_VALUE
                
                for (r in seq) {
                    commonMinX = maxOf(commonMinX, r.x)
                    commonMaxX = minOf(commonMaxX, r.x + r.width)
                    totalMinY = minOf(totalMinY, r.y)
                    totalMaxY = maxOf(totalMaxY, r.y + r.height)
                }
                
                if (commonMaxX > commonMinX) {
                    // Draw vertical axis along center of common rectangle
                    val centerX = (commonMinX + commonMaxX) / 2.0
                    val scX = modelToScreen(centerX, offsetX)
                    
                    val sscy1 = modelToScreen(totalMinY.toDouble(), offsetY)
                    val sscy2 = modelToScreen(totalMaxY.toDouble(), offsetY)
                    
                    val totalHeight = totalMaxY - totalMinY
                    val label = "$totalHeight cm"
                    val lw = metrics.stringWidth(label)
                    
                    drawTwoHeadArrowLine(g2, scX, sscy1, scX, sscy2)
                    
                    // Draw vertical text at the center
                    val oldTransform = g2.transform
                    g2.translate(scX.toDouble() - 5, (sscy1 + (sscy2 - sscy1) / 2.0 + lw / 2.0))
                    g2.rotate(-Math.PI / 2)
                    g2.drawString(label, 0, 0)
                    g2.transform = oldTransform

                    for (r in seq) {
                        roomsInVerticalSequence.add(r)
                    }
                }
            }
            
            for (room in rooms) {
                // Room bounds in screen pixels
                val rsx = modelToScreen(room.x.toDouble(), offsetX)
                val rsy = modelToScreen(room.y.toDouble(), offsetY)
                val rsw = modelToScreen((room.x + room.width).toDouble(), offsetX) - rsx
                val rsh = modelToScreen((room.y + room.height).toDouble(), offsetY) - rsy
                
                val marginPx = (margin * scale * pixelsPerCm).roundToInt()
                
                // Horizontal dimension line (Width) - INSIDE
                // Skip if part of horizontal sequence
                if (!roomsInHorizontalSequence.contains(room)) {
                    if (rsw > 2 * marginPx) {
                        val hwY = rsy + marginPx
                        val wLabel = "${room.width} cm"
                        val wLabelW = metrics.stringWidth(wLabel)
                        
                        if (rsw > wLabelW + 10) {
                            drawTwoHeadArrowLine(g2, rsx, hwY, rsx + rsw, hwY)
                            g2.drawString(wLabel, rsx + (rsw - wLabelW) / 2, hwY - 5)
                        }
                    }
                }
                
                // Vertical dimension line (Height) - INSIDE
                // Skip if part of vertical sequence
                if (!roomsInVerticalSequence.contains(room)) {
                    if (rsh > 2 * marginPx) {
                        val vhX = rsx + marginPx
                        val hLabel = "${room.height} cm"
                        val hLabelW = metrics.stringWidth(hLabel)
                        
                        if (rsh > hLabelW + 10) {
                            drawTwoHeadArrowLine(g2, vhX, rsy, vhX, rsy + rsh)
                            // Draw vertical text
                            val oldTransform = g2.transform
                            g2.translate(vhX.toDouble() - 5, (rsy + rsh / 2 + hLabelW / 2).toDouble())
                            g2.rotate(-Math.PI / 2)
                            g2.drawString(hLabel, 0, 0)
                            g2.transform = oldTransform
                        }
                    }
                }
            }
            
            // Labels for Windows and Doors
            for (el in elements) {
                if (el is PlanWindow || el is Door) {
                    val wall = findContainingWall(el.x, el.y, el.width, el.height)
                    val effectiveWidth = if (wall != null) {
                        val isVertical = wall.width < wall.height
                        if (isVertical) el.height else el.width
                    } else {
                        maxOf(el.width, el.height)
                    }
                    val h3d = if (el is PlanWindow) el.height3D else (el as Door).height3D
                    val label = "($effectiveWidth x $h3d)"
                    
                    val sx = modelToScreen(el.x.toDouble(), offsetX)
                    val sy = modelToScreen(el.y.toDouble(), offsetY)
                    val sw = modelToScreen((el.x + el.width).toDouble(), offsetX) - sx
                    val sh = modelToScreen((el.y + el.height).toDouble(), offsetY) - sy
                    
                    val lw = metrics.stringWidth(label)
                    val lh = metrics.ascent
                    
                    if (sw >= lw && sh >= lh) {
                        g2.color = Color.BLACK
                        g2.drawString(label, sx + (sw - lw) / 2, sy + (sh + lh) / 2)
                    } else if (sh >= lw && sw >= lh) {
                        // Draw vertically if it fits better
                        g2.color = Color.BLACK
                        val oldTransform = g2.transform
                        g2.translate((sx + sw / 2 + lh / 2).toDouble(), (sy + sh / 2 + lw / 2).toDouble())
                        g2.rotate(-Math.PI / 2)
                        g2.drawString(label, 0, 0)
                        g2.transform = oldTransform
                    }
                }
            }
        }

        private fun drawTwoHeadArrowLine(g: Graphics2D, x1: Int, y1: Int, x2: Int, y2: Int) {
            g.drawLine(x1, y1, x2, y2)
            val arrowSize = 5
            if (x1 == x2) { // Vertical
                g.drawLine(x1, y1, x1 - arrowSize, y1 + arrowSize)
                g.drawLine(x1, y1, x1 + arrowSize, y1 + arrowSize)
                g.drawLine(x2, y2, x2 - arrowSize, y2 - arrowSize)
                g.drawLine(x2, y2, x2 + arrowSize, y2 - arrowSize)
            } else { // Horizontal
                g.drawLine(x1, y1, x1 + arrowSize, y1 - arrowSize)
                g.drawLine(x1, y1, x1 + arrowSize, y1 + arrowSize)
                g.drawLine(x2, y2, x2 - arrowSize, y2 - arrowSize)
                g.drawLine(x2, y2, x2 - arrowSize, y2 + arrowSize)
            }
        }

        private fun drawElement(g2: Graphics2D, el: PlanElement) {
            val sx = modelToScreen(el.x.toDouble(), offsetX)
            val sy = modelToScreen(el.y.toDouble(), offsetY)
            val sw = modelToScreen((el.x + el.width).toDouble(), offsetX) - sx
            val sh = modelToScreen((el.y + el.height).toDouble(), offsetY) - sy

            when (el.type) {
                ElementType.WALL -> g2.color = Color.DARK_GRAY
                ElementType.ROOM -> g2.color = Color.LIGHT_GRAY
                ElementType.WINDOW -> g2.color = Color.CYAN
                ElementType.DOOR -> g2.color = Color.ORANGE
                ElementType.FLOOR_OPENING -> {
                    g2.color = Color(230, 230, 230) // Light gray
                    val poly = Polygon()
                    (el as FloorOpening).vertices.forEach {
                        poly.addPoint(modelToScreen(it.x.toDouble(), offsetX), modelToScreen(it.y.toDouble(), offsetY))
                    }
                    g2.fillPolygon(poly)
                    return
                }
                ElementType.STAIRS -> {
                    val oldColor = g2.color
                    g2.color = Color(100, 100, 100, 50)
                    g2.fillRect(sx, sy, sw, sh)
                    
                    g2.color = Color.BLACK
                    val lineThickness = 4
                    g2.stroke = BasicStroke(lineThickness.toFloat())
                    
                    // Normal stair depth is 25cm
                    val normalStairDepthCm = 25.0
                    val stepPx = (normalStairDepthCm * scale * pixelsPerCm).roundToInt()
                    
                    if (el.width > el.height) {
                        // Horizontal stairs - vertical lines
                        var lx = sx + stepPx
                        while (lx < sx + sw) {
                            g2.drawLine(lx, sy, lx, sy + sh)
                            lx += stepPx
                        }
                    } else {
                        // Vertical stairs - horizontal lines
                        var ly = sy + stepPx
                        while (ly < sy + sh) {
                            g2.drawLine(sx, ly, sx + sw, ly)
                            ly += stepPx
                        }
                    }
                    g2.stroke = BasicStroke(1f)
                    g2.color = oldColor
                    // Skip regular fill
                    return
                }
            }

            g2.fillRect(sx, sy, sw, sh)
        }

        private fun drawSelection(g2: Graphics2D, sx: Int, sy: Int, sw: Int, sh: Int) {
            g2.color = Color.RED
            g2.setStroke(BasicStroke(2f))
            if (selectedElement is FloorOpening) {
                val poly = Polygon()
                (selectedElement as FloorOpening).vertices.forEach {
                    poly.addPoint(modelToScreen(it.x.toDouble(), offsetX), modelToScreen(it.y.toDouble(), offsetY))
                }
                g2.drawPolygon(poly)
                
                g2.color = Color.WHITE
                val r = HANDLE_SIZE / 2
                (selectedElement as FloorOpening).vertices.forEachIndexed { index, it ->
                    val vsx = modelToScreen(it.x.toDouble(), offsetX)
                    val vsy = modelToScreen(it.y.toDouble(), offsetY)
                    
                    g2.color = Color.WHITE
                    g2.fillRect(vsx - r, vsy - r, 2 * r, 2 * r)
                    g2.color = Color.RED
                    g2.drawRect(vsx - r, vsy - r, 2 * r, 2 * r)
                    
                    // Draw marker number with a small background for better visibility
                    val label = (index + 1).toString()
                    val metrics = g2.fontMetrics
                    val lw = metrics.stringWidth(label)
                    val lh = metrics.ascent
                    
                    val lx = vsx + r + 2
                    val ly = vsy + r + 2
                    
                    g2.color = Color.WHITE
                    g2.fillRect(lx - 1, ly - lh, lw + 2, lh + 2)
                    g2.color = Color.BLACK
                    g2.drawString(label, lx, ly)
                }
            } else {
                g2.drawRect(sx, sy, sw, sh)

                // Draw resize handles
                g2.color = Color.WHITE
                val r = HANDLE_SIZE / 2
                val handles = listOf(
                    Point(sx, sy), Point(sx + sw / 2, sy), Point(sx + sw, sy),
                    Point(sx + sw, sy + sh / 2), Point(sx + sw, sy + sh),
                    Point(sx + sw / 2, sy + sh), Point(sx, sy + sh), Point(sx, sy + sh / 2)
                )
                for (hp in handles) {
                    g2.color = Color.WHITE
                    g2.fillRect(hp.x - r, hp.y - r, 2 * r, 2 * r)
                    g2.color = Color.RED
                    g2.drawRect(hp.x - r, hp.y - r, 2 * r, 2 * r)
                }
            }
            g2.setStroke(BasicStroke(1f))
        }
    }

    inner class ElementStatisticsPanel : JPanel() {
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
                val wall = findContainingWall(el.x, el.y, el.width, el.height)
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

    inner class StatisticsPanel : JPanel() {
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
                        recenterOnElement(problematicElements.first())
                        selectedElement = problematicElements.first()
                        elementStatsPanel.updateElementStats(selectedElement)
                        sidePanel.updateFields(selectedElement!!)
                        canvas.repaint()
                    }
                }
            })
        }

        fun update() {
            val walls = elements.filterIsInstance<Wall>()
            val rooms = elements.filterIsInstance<Room>()
            val windows = elements.filterIsInstance<PlanWindow>()
            val doors = elements.filterIsInstance<Door>()
            val unusable = elements.filter { it is Stairs }
            val emptySpaces = elements.filterIsInstance<FloorOpening>()
            
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

            var totalWindowOpeningVolume = 0.0
            var totalDoorOpeningVolume = 0.0
            var totalWindowArea = 0.0
            for (win in windows) {
                val wall = findContainingWall(win.x, win.y, win.width, win.height)
                val effectiveWidth = if (wall != null) {
                    val isVertical = wall.width < wall.height
                    if (isVertical) win.height else win.width
                } else {
                    maxOf(win.width, win.height)
                }
                totalWindowArea += effectiveWidth.toDouble() * win.height3D
                totalWindowOpeningVolume += win.getArea() * win.height3D
            }
            windowAreaLabel.text = "Window Area/Vol: %.2f m²/ %.2f m³".format(
                totalWindowArea / 10000.0,
                totalWindowOpeningVolume / 1000000.0
            )

            var totalDoorArea = 0.0
            for (door in doors) {
                val wall = findContainingWall(door.x, door.y, door.width, door.height)
                val effectiveWidth = if (wall != null) {
                    val isVertical = wall.width < wall.height
                    if (isVertical) door.height else door.width
                } else {
                    maxOf(door.width, door.height)
                }
                totalDoorArea += effectiveWidth.toDouble() * door.height3D
                totalDoorOpeningVolume += door.getArea() * door.height3D
            }
            doorAreaLabel.text = "Door Area/Vol: %.2f m² / %.2f m³".format(
                totalDoorArea / 10000.0,
                        totalDoorOpeningVolume / 1000000.0)

            val totalOpeningVolume = totalWindowOpeningVolume + totalDoorOpeningVolume
            openingVolumeLabel.text = "Opening Volume: %.2f m³".format(
                totalOpeningVolume / 1000000.0)

            val intersections = calculateIntersections()
            val intersectionDescriptions = mutableSetOf<String>()
            for (info in intersections) {
                val type1 = info.el1.type.name.lowercase().replaceFirstChar { it.uppercase() }
                val type2 = info.el2.type.name.lowercase().replaceFirstChar { it.uppercase() }
                intersectionDescriptions.add("$type1 intersects with $type2")
                problematicElements.add(info.el1)
                problematicElements.add(info.el2)
            }

            var misplacedCount = 0
            for (el in elements) {
                if (el is PlanWindow || el is Door) {
                    if (findContainingWall(el.x, el.y, el.width, el.height) == null) {
                        misplacedCount++
                        problematicElements.add(el)
                    }
                }
                if (el is Stairs || el is FloorOpening) {
                    val room = if (el is Stairs) findContainingRoom(el.x, el.y, el.width, el.height)
                               else findContainingRoomForFloorOpening(el as FloorOpening)
                    if (room == null) {
                        misplacedCount++
                        problematicElements.add(el)
                    }
                }
            }

            if (intersectionDescriptions.isNotEmpty() || misplacedCount > 0) {
                val sb = StringBuilder("<html><b>WARNING: Potential issues!</b><br>")
                for (desc in intersectionDescriptions) {
                    sb.append("- ").append(desc).append("<br>")
                }
                if (misplacedCount > 0) {
                    sb.append("- $misplacedCount misplaced elements<br>")
                }
                sb.append("<i>Click to center on problem</i></html>")
                warningLabel.text = sb.toString()
                warningLabel.isVisible = true
            } else {
                warningLabel.text = ""
                warningLabel.isVisible = false
            }
        }
    }


    inner class SidePanel : JPanel() {
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
                    val es = selectedElement as? FloorOpening ?: return@addActionListener
                    saveState()
                    val v = es.vertices[row]
                    es.vertices.add(row + 1, Point(v.x + 10, v.y + 10))
                    es.updateBounds()
                    updateFields(es)
                    canvas.repaint()
                    statsPanel.update()
                }
            }
            polygonPopup.add(duplicateMarkerItem)

            val removeMarkerItem = JMenuItem("Remove Marker")
            removeMarkerItem.addActionListener {
                val row = polygonTable.selectedRow
                if (row != -1) {
                    val es = selectedElement as? FloorOpening ?: return@addActionListener
                    if (es.vertices.size > 3) {
                        saveState()
                        es.vertices.removeAt(row)
                        es.updateBounds()
                        updateFields(es)
                        canvas.repaint()
                        statsPanel.update()
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
                    val es = selectedElement as? FloorOpening ?: return@addTableModelListener
                    val row = e.firstRow
                    val col = e.column
                    val value = polygonTableModel.getValueAt(row, col).toString().toIntOrNull()
                    if (value != null) {
                        saveState()
                        if (col == 1) es.vertices[row].x = value
                        if (col == 2) es.vertices[row].y = value
                        es.updateBounds()
                        canvas.repaint()
                    }
                }
            }

            add(mainFieldsPanel, BorderLayout.CENTER)
        }

        fun addElement(type: ElementType) {
            val centerX = screenToModel(canvas.width / 2, offsetX).roundToInt()
            val centerY = screenToModel(canvas.height / 2, offsetY).roundToInt()
            
            val el = when(type) {
                ElementType.WALL -> Wall(centerX - 50, centerY - 10, 100, 20)
                ElementType.ROOM -> Room(centerX - 50, centerY - 50, 100, 100)
                ElementType.STAIRS -> {
                    val room = selectedElement as? Room
                    if (room != null) {
                        Stairs(room.x + 10, room.y + 10, 80, 40)
                    } else {
                        Stairs(centerX - 40, centerY - 20, 80, 40)
                    }
                }
                ElementType.FLOOR_OPENING -> {
                    // This can now be triggered from empty space popup
                    currentMode = AppMode.RULER
                    canvas.isCreatingFloorOpening = true
                    canvas.rulerMarkers.clear()
                    canvas.rulerClosed = false
                    canvas.rulerProbeEnabled = true
                    canvas.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
                    rulerBtn.isSelected = true
                    return
                }
                ElementType.WINDOW, ElementType.DOOR -> {
                    val wall = selectedElement as? Wall
                    if (wall == null) {
                        JOptionPane.showMessageDialog(this, "Select a wall first")
                        return
                    }
                    val isVertical = wall.width < wall.height
                    val thickness = if (isVertical) wall.width else wall.height
                    
                    val children = elements.filter { (it is PlanWindow || it is Door) && wall.getBounds().contains(it.getBounds()) }
                    
                    val wallStart = if (isVertical) wall.y else wall.x
                    val wallEnd = if (isVertical) wall.y + wall.height else wall.x + wall.width
                    
                    val occupiedIntervals = children.map { child ->
                        if (isVertical) child.y to (child.y + child.height)
                        else child.x to (child.x + child.width)
                    }.sortedBy { it.first }
                    
                    val freeIntervals = mutableListOf<Pair<Int, Int>>()
                    var current = wallStart
                    for (interval in occupiedIntervals) {
                        if (interval.first > current) {
                            freeIntervals.add(current to interval.first)
                        }
                        current = maxOf(current, interval.second)
                    }
                    if (current < wallEnd) {
                        freeIntervals.add(current to wallEnd)
                    }
                    
                    val bestInterval = freeIntervals.maxByOrNull { it.second - it.first }
                    if (bestInterval == null || (bestInterval.second - bestInterval.first) < 10) {
                        JOptionPane.showMessageDialog(this, "No space left in the wall")
                        return
                    }
                    
                    val intervalCenter = (bestInterval.first + bestInterval.second) / 2
                    val elementSize = if (type == ElementType.WINDOW) 50 else 40
                    val halfSize = elementSize / 2
                    
                    // Adjust size if it doesn't fit in the interval
                    val finalSize = minOf(elementSize, bestInterval.second - bestInterval.first)
                    val startPos = intervalCenter - finalSize / 2
                    
                    if (type == ElementType.WINDOW) {
                        if (isVertical) PlanWindow(wall.x, startPos, thickness, finalSize)
                        else PlanWindow(startPos, wall.y, finalSize, thickness)
                    } else {
                        if (isVertical) Door(wall.x, startPos, thickness, finalSize)
                        else Door(startPos, wall.y, finalSize, thickness)
                    }
                }
            }
            elements.add(el)
            saveState()
            selectedElement = el
            elementStatsPanel.updateElementStats(el)
            updateFields(el)
            statsPanel.update()
            canvas.repaint()
        }

        fun updateFields(el: PlanElement) {
            isUpdatingFields = true
            removeAll()
            if (el is FloorOpening) {
                polygonTableModel.rowCount = 0
                el.vertices.forEachIndexed { index, v ->
                    polygonTableModel.addRow(arrayOf(index + 1, v.x, v.y))
                }
                add(polygonPanel, BorderLayout.CENTER)
            } else {
                dimensionTableModel.rowCount = 0
                dimensionTableModel.addRow(arrayOf("X:", el.x))
                dimensionTableModel.addRow(arrayOf("Y:", el.y))
                dimensionTableModel.addRow(arrayOf("Width:", el.width))
                dimensionTableModel.addRow(arrayOf("Height:", el.height))
                dimensionTableModel.addRow(arrayOf("X2:", el.x + el.width))
                dimensionTableModel.addRow(arrayOf("Y2:", el.y + el.height))
                if (el is PlanWindow) {
                    dimensionTableModel.addRow(arrayOf("3D Height:", el.height3D))
                } else if (el is Door) {
                    dimensionTableModel.addRow(arrayOf("3D Height:", el.height3D))
                }
                add(mainFieldsPanel, BorderLayout.CENTER)
            }
            revalidate()
            repaint()
            isUpdatingFields = false
        }

        fun clearFields() {
            isUpdatingFields = true
            dimensionTableModel.rowCount = 0
            polygonTableModel.rowCount = 0
            isUpdatingFields = false
        }

        private fun applyManualChanges(source: String) {
            if (isUpdatingFields) return
            val el = selectedElement ?: return
            
            try {
                val data = mutableMapOf<String, Int>()
                for (i in 0 until dimensionTableModel.rowCount) {
                    val key = dimensionTableModel.getValueAt(i, 0).toString().replace(":", "").trim()
                    val value = dimensionTableModel.getValueAt(i, 1).toString().toIntOrNull() ?: continue
                    data[key] = value
                }

                val nx = data["X"] ?: el.x
                val ny = data["Y"] ?: el.y
                val nw = data["Width"] ?: el.width
                val nh = data["Height"] ?: el.height
                val nx2 = data["X2"] ?: (el.x + el.width)
                val ny2 = data["Y2"] ?: (el.y + el.height)
                val nh3d = data["3D Height"] ?: 210
                
                var finalX = el.x
                var finalY = el.y
                var finalW = el.width
                var finalH = el.height

                when (source) {
                    "X" -> finalX = nx
                    "Y" -> finalY = ny
                    "X2" -> {
                        finalX = nx2 - el.width
                    }
                    "Y2" -> {
                        finalY = ny2 - el.height
                    }
                    "Width" -> finalW = nw
                    "Height" -> finalH = nh
                    "3D Height" -> {
                        if (el is PlanWindow) el.height3D = nh3d
                        if (el is Door) el.height3D = nh3d
                        saveState()
                        statsPanel.update()
                        return
                    }
                }

                if (finalX == el.x && finalY == el.y && finalW == el.width && finalH == el.height) return

                saveState()
                val oldBounds = el.getBounds()
                if (el is Wall || el is Room) {
                    val dx = finalX - el.x
                    val dy = finalY - el.y
                    
                    val containedElements = if (el is Wall) {
                        elements.filter { it is PlanWindow || it is Door }
                            .filter { oldBounds.contains(it.getBounds()) }
                    } else {
                        elements.filter { it is Stairs || it is FloorOpening }
                            .filter { oldBounds.contains(it.getBounds()) }
                    }
                    
                    val toRemove = mutableListOf<PlanElement>()
                    val newBounds = Rectangle(finalX, finalY, finalW, finalH)
                    
                    for (child in containedElements) {
                        if (child is FloorOpening) {
                            val newVertices = child.vertices.map { Point(it.x + dx, it.y + dy) }.toMutableList()
                            val tempES = FloorOpening(newVertices)
                            if (newBounds.contains(tempES.getBounds())) {
                                child.vertices.clear()
                                child.vertices.addAll(newVertices)
                                child.updateBounds()
                            } else {
                                toRemove.add(child)
                            }
                        } else {
                            val oldCX = child.x
                            val oldCY = child.y
                            child.x += dx
                            child.y += dy

                            if (el is Wall) {
                                val isVerticalBefore = el.width < el.height
                                val isVerticalAfter = finalW < finalH
                                
                                if (isVerticalBefore && isVerticalAfter) {
                                    child.width = finalW
                                    child.x = finalX
                                } else if (!isVerticalBefore && !isVerticalAfter) {
                                    child.height = finalH
                                    child.y = finalY
                                }
                            }

                            if (!newBounds.contains(child.getBounds())) {
                                toRemove.add(child)
                            }
                        }
                    }

                    if (toRemove.isNotEmpty()) {
                        val msg = if (el is Wall) "Some window/door elements will be removed because they no longer fit. Continue?"
                                  else "Some stairs/floor opening elements will be removed because they no longer fit. Continue?"
                        val confirm = JOptionPane.showConfirmDialog(
                            this,
                            msg,
                            "Warning",
                            JOptionPane.YES_NO_OPTION
                        )
                        if (confirm != JOptionPane.YES_OPTION) {
                            updateFields(el)
                            return
                        }
                        elements.removeAll(toRemove)
                    }
                }

                el.x = finalX
                el.y = finalY
                el.width = finalW
                el.height = finalH
                
                elementStatsPanel.updateElementStats(el)
                updateFields(el)
                statsPanel.update()
                canvas.repaint()
            } catch (e: Exception) {
                // Ignore invalid input while typing
            }
        }
    }
}
