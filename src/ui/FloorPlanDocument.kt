package ui

import model.PlanElement
import model.AppMode
import ui.components.CanvasPanel
import java.io.File
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import model.*
import kotlin.math.roundToInt

class FloorPlanDocument(val app: FloorPlanApp) {
    var window: EditorWindow? = null
    val elements = mutableListOf<PlanElement>()
    var selectedElement: PlanElement? = null
    var currentFile: File? = null
    
    var currentMode = AppMode.NORMAL
    var scale = 0.01 // screen cm / model cm
    var offsetX = 0.0 // model cm
    var offsetY = 0.0 // model cm
    var showDimensionLabels = false
    
    var isModified = false
    
    val undoStack = mutableListOf<List<PlanElement>>()
    val redoStack = mutableListOf<List<PlanElement>>()
    val MAX_HISTORY = 10
    val pixelsPerCm = Toolkit.getDefaultToolkit().screenResolution / 2.54

    val MIN_SCALE = 0.005
    val MAX_SCALE = 0.05
    
    val canvas = CanvasPanel(this)

    fun modelToScreen(modelCm: Double, offsetCm: Double, customScale: Double = scale): Int {
        return ((modelCm + offsetCm) * customScale * pixelsPerCm).roundToInt()
    }

    fun screenToModel(screenPx: Int, offsetCm: Double, customScale: Double = scale): Double {
        return (screenPx / (customScale * pixelsPerCm)) - offsetCm
    }

    fun saveState() {
        undoStack.add(cloneElements(elements))
        if (undoStack.size > MAX_HISTORY + 1) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
        isModified = true
        window?.updateTitle()
        app.updateUndoRedoStates()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(cloneElements(elements))
            val state = undoStack.removeAt(undoStack.size - 1)
            elements.clear()
            elements.addAll(cloneElements(state))
            selectedElement = null
            isModified = true
            window?.updateTitle()
            app.refreshUI()
            app.updateUndoRedoStates()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(cloneElements(elements))
            val state = redoStack.removeAt(redoStack.size - 1)
            elements.clear()
            elements.addAll(cloneElements(state))
            selectedElement = null
            isModified = true
            window?.updateTitle()
            app.refreshUI()
            app.updateUndoRedoStates()
        }
    }

    fun recenterOnElement(el: PlanElement) {
        val availableWidth = canvas.width.toDouble()
        val availableHeight = canvas.height.toDouble()
        
        if (availableWidth <= 0 || availableHeight <= 0) return

        val centerX = el.x + el.width / 2.0
        val centerY = el.y + el.height / 2.0
        
        offsetX = (availableWidth / 2.0) / (scale * pixelsPerCm) - centerX
        offsetY = (availableHeight / 2.0) / (scale * pixelsPerCm) - centerY
        
        canvas.repaint()
    }

    fun autoScaleToFit() {
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
        
        window?.updateScaleLabel()
        canvas.repaint()
    }

    fun calculateIntersections(): List<IntersectionInfo> {
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

    private fun cloneElements(source: List<PlanElement>): List<PlanElement> {
        val cloned = mutableListOf<PlanElement>()
        for (el in source) {
            val newEl = when (el) {
                is Wall -> Wall(el.x, el.y, el.width, el.height)
                is Room -> Room(el.x, el.y, el.width, el.height)
                is Window -> Window(el.x, el.y, el.width, el.height, el.height3D, el.sillElevation)
                is Door -> Door(el.x, el.y, el.width, el.height, el.verticalHeight)
                is Stairs -> Stairs(el.x, el.y, el.width, el.height)
                is FloorOpening -> FloorOpening(el.vertices.map { Point(it.x, it.y) }.toMutableList())
                else -> null
            }
            newEl?.let { cloned.add(it) }
        }
        return cloned
    }
}
