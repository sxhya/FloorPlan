package ui

import model.PlanElement
import model.ElementType
import model.Wall
import model.Room
import model.Window as PlanWindow
import model.Door
import model.Stairs
import model.FloorOpening
import model.AppMode
import model.ResizeHandle
import model.IntersectionInfo
import ui.components.CanvasPanel
import ui.components.SidePanel
import ui.components.ElementStatisticsPanel
import ui.components.StatisticsPanel
import ui.components.AppIcons
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
    internal val elements = mutableListOf<PlanElement>()
    internal var selectedElement: PlanElement? = null
    private var currentFile: File? = null
    
    internal var currentMode = AppMode.NORMAL

    internal val canvas = CanvasPanel(this)
    internal val sidePanel = SidePanel(this)
    internal val elementStatsPanel = ElementStatisticsPanel(this)
    internal val statsPanel = StatisticsPanel(this)
    private val scaleLabel = JLabel("100%")
    private var mainMenuBar: JMenuBar? = null
    internal var showDimensionLabels = false
    internal var isExporting = false
    
    internal val normalBtn = JToggleButton(AppIcons.MOUSE_POINTER, true)
    internal val dragBtn = JToggleButton(AppIcons.HAND)
    internal val rulerBtn = JToggleButton(AppIcons.RULER)
    
    internal val MIN_SCALE = 0.005
    internal val MAX_SCALE = 0.05
    
    private val undoStack = mutableListOf<List<PlanElement>>()
    private val redoStack = mutableListOf<List<PlanElement>>()
    private val MAX_HISTORY = 10
    
    private val SETTINGS_FILE = "floorplan_settings.properties"
    private var lastDirectory: String? = null
    private val recentFiles = mutableListOf<String>()
    private val MAX_RECENT = 10

    internal val popupMenu = JPopupMenu()
    internal val recentMenu = JMenu("Recent Files")
    internal lateinit var undoMenuItem: JMenuItem
    internal lateinit var redoMenuItem: JMenuItem
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

    internal var scale = 0.01 // screen cm / model cm
    internal var offsetX = 0.0 // model cm
    internal var offsetY = 0.0 // model cm
    internal val pixelsPerCm = Toolkit.getDefaultToolkit().screenResolution / 2.54

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

    internal fun recenterOnElement(el: PlanElement) {
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

    internal fun calculateIntersections(): List<IntersectionInfo> {
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

    internal fun duplicateSelected() {
        selectedElement?.let { el ->
            val shift = (maxOf(el.width, el.height) * 0.05).roundToInt()
            val newEl = when (el) {
                is Wall -> Wall(el.x + shift, el.y + shift, el.width, el.height)
                is Room -> Room(el.x + shift, el.y + shift, el.width, el.height)
                is PlanWindow -> PlanWindow(el.x + shift, el.y + shift, el.width, el.height, el.height3D, el.aboveFloorHeight)
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

    internal fun removeSelected() {
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
        val fitSceneItem = JMenuItem("Fit scene")
        fitSceneItem.addActionListener { autoScaleToFit() }
        viewMenu.add(fitSceneItem)
        viewMenu.addSeparator()
        
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
                is PlanWindow -> PlanWindow(el.x, el.y, el.width, el.height, el.height3D, el.aboveFloorHeight)
                is Door -> Door(el.x, el.y, el.width, el.height, el.height3D)
                is Stairs -> Stairs(el.x, el.y, el.width, el.height)
                is FloorOpening -> FloorOpening(el.vertices.map { Point(it.x, it.y) }.toMutableList())
                else -> null
            }
            newEl?.let { cloned.add(it) }
        }
        return cloned
    }

    internal fun saveState() {
        undoStack.add(cloneElements(elements))
        if (undoStack.size > MAX_HISTORY + 1) { // Current state is also in undoStack
            undoStack.removeAt(0)
        }
        redoStack.clear()
        updateUndoRedoStates()
    }

    internal fun undo() {
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

    internal fun redo() {
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

    internal fun refreshUI() {
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

    internal fun updateScaleLabel() {
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

    internal fun modelToScreen(modelCm: Double, offsetCm: Double, customScale: Double = scale): Int {
        return ((modelCm + offsetCm) * customScale * pixelsPerCm).roundToInt()
    }

    internal fun screenToModel(screenPx: Int, offsetCm: Double, customScale: Double = scale): Double {
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
                    if (el is PlanWindow) {
                        elNode.setAttribute("height3D", el.height3D.toString())
                        elNode.setAttribute("aboveFloorHeight", el.aboveFloorHeight.toString())
                    }
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

    internal fun findContainingWall(x: Int, y: Int, w: Int, h: Int): Wall? {
        val rect = Rectangle(x, y, w, h)
        return elements.filterIsInstance<Wall>().find { it.getBounds().contains(rect) }
    }

    internal fun findContainingRoom(x: Int, y: Int, w: Int, h: Int): Room? {
        val rect = Rectangle(x, y, w, h)
        return elements.filterIsInstance<Room>().find { it.getBounds().contains(rect) }
    }

    internal fun findContainingRoomForFloorOpening(es: FloorOpening): Room? {
        return elements.filterIsInstance<Room>().find { it.getBounds().contains(es.getBounds()) }
    }

}
