package ui

import model.*
import java.io.Serializable
import java.awt.Toolkit
import kotlin.math.roundToInt
import ui.components.CanvasPanel

class WallLayoutDocument(
    val app: FloorPlanApp,
    val floorPlanDoc: FloorPlanDocument,
    val wall: Wall,
    val layout: WallLayout,
    val isFront: Boolean
) {
    var window: WallLayoutWindow? = null
    var scale = 0.05 // screen cm / model cm
    var offsetX = 0.0 // model cm
    var offsetY = 0.0 // model cm

    val MIN_SCALE = 0.005
    val MAX_SCALE = 0.1
    
    var isModified = false
    val pixelsPerCm = Toolkit.getDefaultToolkit().screenResolution / 2.54

    fun autoScaleToFit() {
        val canvas = window?.canvas ?: return
        if (canvas.width <= 0 || canvas.height <= 0) return

        val wallWidth = maxOf(wall.width, wall.height).toDouble()
        val wallHeight = app.getThreeDDocuments().firstOrNull()?.model?.getBounds()?.let { (it.second.z - it.first.z) } ?: 300.0

        val margin = 40.0 // pixels
        val availableWidth = canvas.width - 2 * margin
        val availableHeight = canvas.height - 2 * margin

        val scaleX = availableWidth / (wallWidth * pixelsPerCm)
        val scaleY = availableHeight / (wallHeight * pixelsPerCm)
        
        scale = minOf(scaleX, scaleY).coerceIn(MIN_SCALE, MAX_SCALE)

        offsetX = (canvas.width / 2.0) / (scale * pixelsPerCm) - wallWidth / 2.0
        offsetY = (canvas.height / 2.0) / (scale * pixelsPerCm) + wallHeight / 2.0
        
        canvas.repaint()
    }
    
    val undoStack = mutableListOf<List<WallLayoutPoint>>()
    val redoStack = mutableListOf<List<WallLayoutPoint>>()
    val MAX_HISTORY = 10

    var selectedPoint: WallLayoutPoint? = null

    fun modelToScreen(modelVal: Double, offsetVal: Double, isZ: Boolean = false): Int {
        return if (isZ) {
            // Z=0 is floor, grows upwards. Screen Y=0 is top, grows downwards.
            // Screen Y = (offsetY - modelZ) * scale * pixelsPerCm
            ((offsetVal - modelVal) * scale * pixelsPerCm).roundToInt()
        } else {
            ((modelVal + offsetVal) * scale * pixelsPerCm).roundToInt()
        }
    }

    fun screenToModel(screenPx: Int, offsetVal: Double, isZ: Boolean = false): Double {
        return if (isZ) {
            // screenPx = (offsetVal - modelZ) * scale * pixelsPerCm
            // screenPx / (scale * pixelsPerCm) = offsetVal - modelZ
            // modelZ = offsetVal - screenPx / (scale * pixelsPerCm)
            offsetVal - (screenPx / (scale * pixelsPerCm))
        } else {
            (screenPx / (scale * pixelsPerCm)) - offsetVal
        }
    }

    fun saveState() {
        undoStack.add(layout.points.map { it.copy() })
        if (undoStack.size > MAX_HISTORY + 1) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
        isModified = true
        window?.updateTitle()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.add(layout.points.map { it.copy() })
            val state = undoStack.removeAt(undoStack.size - 1)
            layout.points.clear()
            layout.points.addAll(state.map { it.copy() })
            selectedPoint = null
            isModified = true
            window?.updateTitle()
            window?.canvas?.repaint()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.add(layout.points.map { it.copy() })
            val state = redoStack.removeAt(redoStack.size - 1)
            layout.points.clear()
            layout.points.addAll(state.map { it.copy() })
            selectedPoint = null
            isModified = true
            window?.updateTitle()
            window?.canvas?.repaint()
        }
    }
}
