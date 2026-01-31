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

    val isInverted = !isFront
    val wallStart: Double
    val wallEnd: Double
    val isVertical: Boolean

    init {
        isVertical = wall.width < wall.height
        if (isVertical) {
            wallStart = wall.y.toDouble()
            wallEnd = (wall.y + wall.height).toDouble()
        } else {
            wallStart = wall.x.toDouble()
            wallEnd = (wall.x + wall.width).toDouble()
        }

        // Migrate points from relative to absolute if needed
        if (!layout.isAbsolute) {
            if (wallStart != 0.0) {
                for (p in layout.points) {
                    p.x += wallStart
                }
            }
            layout.isAbsolute = true
            isModified = true
        }
    }

    fun autoScaleToFit() {
        val canvas = window?.canvas ?: return
        if (canvas.width <= 0 || canvas.height <= 0) return

        val wallWidth = (wallEnd - wallStart)
        val wallHeight = app.getThreeDDocuments().firstOrNull()?.model?.getBounds()?.let { (it.second.z - it.first.z) } ?: 300.0

        val margin = 40.0 // pixels
        val availableWidth = canvas.width - 2 * margin
        val availableHeight = canvas.height - 2 * margin

        val scaleX = availableWidth / (wallWidth * pixelsPerCm)
        val scaleY = availableHeight / (wallHeight * pixelsPerCm)
        
        scale = minOf(scaleX, scaleY).coerceIn(MIN_SCALE, MAX_SCALE)

        val wallCenterX = (wallStart + wallEnd) / 2.0
        val wallCenterZ = wallHeight / 2.0

        if (isInverted) {
            offsetX = wallCenterX + (canvas.width / 2.0) / (scale * pixelsPerCm)
        } else {
            offsetX = wallCenterX - (canvas.width / 2.0) / (scale * pixelsPerCm)
        }
        offsetY = wallCenterZ + (canvas.height / 2.0) / (scale * pixelsPerCm)
        
        canvas.repaint()
    }

    fun zoom(factor: Double, pivotX: Double, pivotY: Double) {
        val modelPivotX = screenToModel(pivotX.toInt(), offsetX)
        val modelPivotZ = screenToModel(pivotY.toInt(), offsetY, true)

        val oldScale = scale
        scale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)

        if (scale != oldScale) {
            if (isInverted) {
                offsetX = modelPivotX + pivotX / (scale * pixelsPerCm)
            } else {
                offsetX = modelPivotX - pivotX / (scale * pixelsPerCm)
            }
            offsetY = modelPivotZ + pivotY / (scale * pixelsPerCm)
        }
    }

    fun pan(dxScreen: Double, dyScreen: Double) {
        val dxModel = dxScreen / (scale * pixelsPerCm)
        val dyModel = dyScreen / (scale * pixelsPerCm)

        if (isInverted) {
            offsetX += dxModel
        } else {
            offsetX -= dxModel
        }
        offsetY += dyModel
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
            if (isInverted) {
                // Inverted: screenX = (offsetVal - modelX) * scale * pixelsPerCm
                ((offsetVal - modelVal) * scale * pixelsPerCm).roundToInt()
            } else {
                // Normal: screenX = (modelX - offsetVal) * scale * pixelsPerCm
                ((modelVal - offsetVal) * scale * pixelsPerCm).roundToInt()
            }
        }
    }

    fun screenToModel(screenPx: Int, offsetVal: Double, isZ: Boolean = false): Double {
        return if (isZ) {
            // screenPx = (offsetVal - modelZ) * scale * pixelsPerCm
            // modelZ = offsetVal - screenPx / (scale * pixelsPerCm)
            offsetVal - (screenPx / (scale * pixelsPerCm))
        } else {
            if (isInverted) {
                // screenPx = (offsetVal - modelX) * scale * pixelsPerCm
                // modelX = offsetVal - screenPx / (scale * pixelsPerCm)
                offsetVal - (screenPx / (scale * pixelsPerCm))
            } else {
                // screenPx = (modelX - offsetVal) * scale * pixelsPerCm
                // modelX = screenPx / (scale * pixelsPerCm) + offsetVal
                (screenPx / (scale * pixelsPerCm)) + offsetVal
            }
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
