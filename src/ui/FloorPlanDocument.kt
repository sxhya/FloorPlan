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
        val candidates = elements.filter { it is Wall || it is Room || it is PolygonRoom }
        val intersections = mutableListOf<IntersectionInfo>()
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                val el1 = candidates[i]
                val el2 = candidates[j]
                
                // Get polygons for both elements
                val poly1 = getElementPolygon(el1)
                val poly2 = getElementPolygon(el2)
                
                // Check if polygons intersect
                val intersectionPoly = computePolygonIntersection(poly1, poly2)
                if (intersectionPoly.isNotEmpty()) {
                    // Only count intersections with nonzero area
                    val area = computePolygonArea(intersectionPoly)
                    if (area > 0.0) {
                        // Use bounding box of intersection for display
                        val bounds = getPolygonBounds(intersectionPoly)
                        intersections.add(IntersectionInfo(el1, el2, bounds))
                    }
                }
            }
        }
        return intersections
    }
    
    private fun getElementPolygon(el: PlanElement): List<Point> {
        return when (el) {
            is PolygonRoom -> el.vertices.toList()
            else -> {
                // Convert rectangular element to 4-vertex polygon
                val b = el.getBounds()
                listOf(
                    Point(b.x, b.y),
                    Point(b.x + b.width, b.y),
                    Point(b.x + b.width, b.y + b.height),
                    Point(b.x, b.y + b.height)
                )
            }
        }
    }
    
    private fun getPolygonBounds(poly: List<Point>): Rectangle {
        if (poly.isEmpty()) return Rectangle()
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (p in poly) {
            minX = minOf(minX, p.x)
            minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x)
            maxY = maxOf(maxY, p.y)
        }
        return Rectangle(minX, minY, maxX - minX, maxY - minY)
    }
    
    private fun computePolygonArea(poly: List<Point>): Double {
        if (poly.size < 3) return 0.0
        var area = 0.0
        for (i in poly.indices) {
            val j = (i + 1) % poly.size
            area += poly[i].x.toDouble() * poly[j].y.toDouble()
            area -= poly[j].x.toDouble() * poly[i].y.toDouble()
        }
        return kotlin.math.abs(area) / 2.0
    }
    
    private fun computePolygonIntersection(poly1: List<Point>, poly2: List<Point>): List<Point> {
        // Sutherland-Hodgman algorithm for polygon clipping
        // Requires both polygons to have consistent winding order (counter-clockwise)
        if (poly1.size < 3 || poly2.size < 3) return emptyList()
        
        // Ensure both polygons are in counter-clockwise order
        val ccwPoly1 = ensureCounterClockwise(poly1)
        val ccwPoly2 = ensureCounterClockwise(poly2)
        
        var outputList = ccwPoly1.toMutableList()
        
        for (i in ccwPoly2.indices) {
            if (outputList.isEmpty()) break
            
            val edgeStart = ccwPoly2[i]
            val edgeEnd = ccwPoly2[(i + 1) % ccwPoly2.size]
            
            val inputList = outputList.toList()
            outputList.clear()
            
            if (inputList.isEmpty()) break
            
            var prevPoint = inputList.last()
            for (currentPoint in inputList) {
                if (isInsideEdge(currentPoint, edgeStart, edgeEnd)) {
                    if (!isInsideEdge(prevPoint, edgeStart, edgeEnd)) {
                        val intersection = lineIntersection(prevPoint, currentPoint, edgeStart, edgeEnd)
                        if (intersection != null) outputList.add(intersection)
                    }
                    outputList.add(currentPoint)
                } else if (isInsideEdge(prevPoint, edgeStart, edgeEnd)) {
                    val intersection = lineIntersection(prevPoint, currentPoint, edgeStart, edgeEnd)
                    if (intersection != null) outputList.add(intersection)
                }
                prevPoint = currentPoint
            }
        }
        
        return outputList
    }
    
    private fun ensureCounterClockwise(poly: List<Point>): List<Point> {
        // Calculate signed area to determine winding order
        // Positive = counter-clockwise, Negative = clockwise
        var signedArea = 0.0
        for (i in poly.indices) {
            val j = (i + 1) % poly.size
            signedArea += poly[i].x.toDouble() * poly[j].y.toDouble()
            signedArea -= poly[j].x.toDouble() * poly[i].y.toDouble()
        }
        // If clockwise (negative area), reverse the polygon
        return if (signedArea < 0) poly.reversed() else poly
    }
    
    private fun isInsideEdge(p: Point, edgeStart: Point, edgeEnd: Point): Boolean {
        // Check if point is on the left side of the edge (inside the polygon for CCW winding)
        return (edgeEnd.x - edgeStart.x) * (p.y - edgeStart.y) - (edgeEnd.y - edgeStart.y) * (p.x - edgeStart.x) >= 0
    }
    
    private fun lineIntersection(p1: Point, p2: Point, p3: Point, p4: Point): Point? {
        val d1x = (p2.x - p1.x).toDouble()
        val d1y = (p2.y - p1.y).toDouble()
        val d2x = (p4.x - p3.x).toDouble()
        val d2y = (p4.y - p3.y).toDouble()
        
        val cross = d1x * d2y - d1y * d2x
        if (kotlin.math.abs(cross) < 1e-10) return null
        
        val dx = (p3.x - p1.x).toDouble()
        val dy = (p3.y - p1.y).toDouble()
        
        val t = (dx * d2y - dy * d2x) / cross
        
        return Point(
            (p1.x + t * d1x).roundToInt(),
            (p1.y + t * d1y).roundToInt()
        )
    }

    fun findContainingWall(x: Int, y: Int, w: Int, h: Int): Wall? {
        val rect = Rectangle(x, y, w, h)
        return elements.filterIsInstance<Wall>().find { it.getBounds().contains(rect) }
    }

    fun findContainingRoom(x: Int, y: Int, w: Int, h: Int): Room? {
        val rect = Rectangle(x, y, w, h)
        return elements.filterIsInstance<Room>().find { it.getBounds().contains(rect) }
    }

    private fun cloneElements(source: List<PlanElement>): List<PlanElement> {
        val cloned = mutableListOf<PlanElement>()
        for (el in source) {
            val newEl = when (el) {
                is Wall -> Wall(el.x, el.y, el.width, el.height)
                is Room -> Room(el.x, el.y, el.width, el.height, el.floorThickness, el.zOffset)
                is Window -> Window(el.x, el.y, el.width, el.height, el.height3D, el.sillElevation, el.windowPosition)
                is Door -> Door(el.x, el.y, el.width, el.height, el.verticalHeight)
                is Stairs -> Stairs(el.x, el.y, el.width, el.height, el.directionAlongX, el.totalRaise, el.zOffset)
                is PolygonRoom -> PolygonRoom(el.vertices.map { Point(it.x, it.y) }.toMutableList(), el.floorThickness, el.zOffset)
                else -> null
            }
            newEl?.let { cloned.add(it) }
        }
        return cloned
    }
}
