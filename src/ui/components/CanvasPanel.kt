package ui.components

import model.*
import model.Window as PlanWindow
import ui.FloorPlanApp
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.*
import kotlin.math.*

class CanvasPanel(private val app: FloorPlanApp) : JPanel() {
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
            val el = app.selectedElement ?: return ResizeHandle.NONE
            
            if (el is FloorOpening) {
                val r = HANDLE_SIZE
                for (i in el.vertices.indices) {
                    val v = el.vertices[i]
                    val sx = app.modelToScreen(v.x.toDouble(), app.offsetX)
                    val sy = app.modelToScreen(v.y.toDouble(), app.offsetY)
                    if (Rectangle(sx - r, sy - r, 2 * r, 2 * r).contains(p)) {
                        return i
                    }
                }
                return ResizeHandle.NONE
            }

            val sx = app.modelToScreen(el.x.toDouble(), app.offsetX)
            val sy = app.modelToScreen(el.y.toDouble(), app.offsetY)
            val sw = app.modelToScreen((el.x + el.width).toDouble(), app.offsetX) - sx
            val sh = app.modelToScreen((el.y + el.height).toDouble(), app.offsetY) - sy

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
                    if (app.currentMode == AppMode.NORMAL) {
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
                    } else if (app.currentMode == AppMode.RULER) {
                        if (rulerProbeEnabled) {
                            rulerProbePoint = Point(app.screenToModel(e.x, app.offsetX).roundToInt(), app.screenToModel(e.y, app.offsetY).roundToInt())
                            repaint()
                        }
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    if (app.currentMode == AppMode.RULER) {
                        rulerProbePoint = null
                        repaint()
                    }
                }


                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    if (e.isPopupTrigger && app.currentMode == AppMode.NORMAL) {
                        showPopup(e)
                        return
                    }
                    if (app.currentMode == AppMode.DRAG) {
                        panStart = e.point
                        initialOffsetX = app.offsetX
                        initialOffsetY = app.offsetY
                        return
                    }

                    if (app.currentMode == AppMode.RULER) {
                        if (SwingUtilities.isRightMouseButton(e)) {
                            rulerMarkers.clear()
                            rulerClosed = false
                            rulerProbeEnabled = true
                            isCreatingFloorOpening = false
                            app.currentMode = AppMode.NORMAL
                            app.normalBtn.isSelected = true
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
                                        val room = app.selectedElement as? Room
                                        val newES = FloorOpening(rulerMarkers.toMutableList())
                                        app.elements.add(newES)
                                        app.saveState()
                                        app.selectedElement = newES
                                        rulerMarkers.clear()
                                        rulerClosed = false
                                        rulerProbeEnabled = true
                                        rulerProbePoint = null
                                        isCreatingFloorOpening = false
                                        app.currentMode = AppMode.NORMAL
                                        app.normalBtn.isSelected = true
                                        app.canvas.cursor = Cursor.getDefaultCursor()
                                        app.sidePanel.updateFields(newES)
                                        app.statsPanel.update()
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
                                val newPoint = Point(app.screenToModel(e.x, app.offsetX).roundToInt(), app.screenToModel(e.y, app.offsetY).roundToInt())
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
                    val ox = app.modelToScreen(0.0, app.offsetX)
                    val oy = app.modelToScreen(0.0, app.offsetY)
                    if (Rectangle(ox - 10, oy - 10, 20, 20).contains(e.point)) {
                        draggingOrigin = true
                        originDragStart = e.point
                        initialOffsetX = app.offsetX
                        initialOffsetY = app.offsetY
                        initialElementsPositions = app.elements.map { Point(it.x, it.y) }
                        return
                    }

                    activeHandle = getHandleUnderMouse(e.point)
                    if (activeHandle != ResizeHandle.NONE) {
                        dragStart = e.point
                        val el = app.selectedElement!!
                        initialElementBounds = Rectangle(el.x, el.y, el.width, el.height)
                        return
                    }

                    app.selectedElement = app.elements.reversed().find { 
                        it.contains(app.screenToModel(e.x, app.offsetX).roundToInt(), app.screenToModel(e.y, app.offsetY).roundToInt())
                    }
                    if (app.selectedElement != null) {
                        dragStart = e.point
                        val el = app.selectedElement!!
                        app.elementStatsPanel.updateElementStats(el)
                        initialElementBounds = Rectangle(el.x, el.y, el.width, el.height)
                        if (el is FloorOpening) {
                            initialVertices = el.vertices.map { Point(it.x, it.y) }
                        } else {
                            initialVertices = null
                        }
                        app.sidePanel.updateFields(app.selectedElement!!)

                        elementsToMoveWithWall.clear()
                        initialChildrenVertices.clear()
                        if (el is Wall || el is Room) {
                            app.elements.forEach { other ->
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
                        app.selectedElement = null
                        app.elementStatsPanel.updateElementStats(null)
                        app.sidePanel.clearFields()
                        if (e.isPopupTrigger) {
                            showPopup(e)
                        } else {
                            panStart = e.point
                            initialOffsetX = app.offsetX
                            initialOffsetY = app.offsetY
                        }
                    }
                    repaint()
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (app.currentMode == AppMode.DRAG) {
                        val pStart = panStart
                        val initOX = initialOffsetX
                        val initOY = initialOffsetY
                        if (pStart != null && initOX != null && initOY != null) {
                            val dx = app.screenToModel(e.x, 0.0) - app.screenToModel(pStart.x, 0.0)
                            val dy = app.screenToModel(e.y, 0.0) - app.screenToModel(pStart.y, 0.0)
                            app.offsetX = initOX + dx
                            app.offsetY = initOY + dy
                        }
                        repaint()
                        return
                    }

                    if (app.currentMode == AppMode.RULER) {
                        rulerProbePoint = Point(app.screenToModel(e.x, app.offsetX).roundToInt(), app.screenToModel(e.y, app.offsetY).roundToInt())
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
                            val dxModel = dxScreen / (app.scale * app.pixelsPerCm)
                            val dyModel = dyScreen / (app.scale * app.pixelsPerCm)
                            
                            app.offsetX = initOX + dxModel
                            app.offsetY = initOY + dyModel
                            
                            app.elements.forEachIndexed { index, el ->
                                val initial = initPos[index]
                                el.x = (initial.x - dxModel).roundToInt()
                                el.y = (initial.y - dyModel).roundToInt()
                            }
                            app.selectedElement?.let { app.sidePanel.updateFields(it) }
                            app.statsPanel.update()
                        }
                        repaint()
                        return
                    }

                    val start = dragStart
                    val initial = initialElementBounds
                    val element = app.selectedElement

                    if (element != null && start != null && initial != null) {
                        val dx = (app.screenToModel(e.x, 0.0) - app.screenToModel(start.x, 0.0)).roundToInt()
                        val dy = (app.screenToModel(e.y, 0.0) - app.screenToModel(start.y, 0.0)).roundToInt()
                        
                        // dx and dy are in model cm

                        if (activeHandle != ResizeHandle.NONE) {
                                if (activeHandle is Int && element is FloorOpening) {
                                    val idx = activeHandle as Int
                                    val mx = app.screenToModel(e.point.x, app.offsetX).roundToInt()
                                    val my = app.screenToModel(e.point.y, app.offsetY).roundToInt()
                                    
                                    // Sticky edges for polygon vertices
                                    val sx_sticky = getStickyCoord(mx, 0, true)
                                    val sy_sticky = getStickyCoord(my, 0, false)
                                    
                                    val oldX = element.vertices[idx].x
                                    val oldY = element.vertices[idx].y
                                    
                                    element.vertices[idx].x = sx_sticky
                                    element.vertices[idx].y = sy_sticky
                                    element.updateBounds()
                                    
                                    if (app.findContainingRoomForFloorOpening(element) == null) {
                                        // Try move only X
                                        element.vertices[idx].y = oldY
                                        element.updateBounds()
                                        if (app.findContainingRoomForFloorOpening(element) == null) {
                                            // Try move only Y
                                            element.vertices[idx].x = oldX
                                            element.vertices[idx].y = sy_sticky
                                            element.updateBounds()
                                            if (app.findContainingRoomForFloorOpening(element) == null) {
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
                                    val wall = app.findContainingWall(newX, newY, newW, newH)
                                    if (wall != null) {
                                        element.x = newX
                                        element.y = newY
                                        element.width = newW
                                        element.height = newH
                                    }
                                } else if (element is Stairs || element is FloorOpening) {
                                    val room = if (element is Stairs) {
                                        app.findContainingRoom(newX, newY, newW, newH)
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
                                            
                                            // Update all app.elements that WERE inside the wall
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
                                val wall = app.findContainingWall(newX, newY, element.width, element.height)
                                if (wall != null) {
                                    element.x = newX
                                    element.y = newY
                                } else {
                                    val currentWall = app.findContainingWall(element.x, element.y, element.width, element.height)
                                    if (currentWall != null) {
                                        var adjusted = false
                                        val movedXInside = app.findContainingWall(newX, element.y, element.width, element.height)
                                        if (movedXInside != null) {
                                            element.x = newX
                                            adjusted = true
                                        } else {
                                            // Clamp X to wall bounds
                                            if (newX < currentWall.x) element.x = currentWall.x
                                            else if (newX + element.width > currentWall.x + currentWall.width) element.x = currentWall.x + currentWall.width - element.width
                                            // Adjusted is false because we didn't fully move to newX
                                        }
                                        
                                        val movedYInside = app.findContainingWall(element.x, newY, element.width, element.height)
                                        if (movedYInside != null) {
                                            element.y = newY
                                            adjusted = true
                                        } else {
                                            // Clamp Y to wall bounds
                                            if (newY < currentWall.y) element.y = currentWall.y
                                            else if (newY + element.height > currentWall.y + currentWall.height) element.y = currentWall.y + currentWall.height - element.height
                                        }
                                        
                                        // Adjust dragStart to avoid "delay" when moving back
                                        // We want: app.screenToModel(e.x) - app.screenToModel(dragStart.x) == element.x - initial.x
                                        // So: app.screenToModel(dragStart.x) = app.screenToModel(e.x) - (element.x - initial.x)
                                        val modelDragStartX = app.screenToModel(e.x, 0.0) - (element.x - initial.x)
                                        val modelDragStartY = app.screenToModel(e.y, 0.0) - (element.y - initial.y)
                                        dragStart = Point(app.modelToScreen(modelDragStartX, 0.0), app.modelToScreen(modelDragStartY, 0.0))
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
                                    if (app.findContainingRoomForFloorOpening(tempES) != null) {
                                        element.vertices.clear()
                                        element.vertices.addAll(tempVertices)
                                        element.x = newX
                                        element.y = newY
                                        element.updateBounds()
                                    }
                                }
                                
                                // Constraints for EmptySpace and Stairs (must be inside a Room)
                                    if (element is Stairs) {
                                        val room = app.findContainingRoom(newX, newY, element.width, element.height)
                                        if (room != null) {
                                            element.x = newX
                                            element.y = newY
                                        } else {
                                            val currentRoom = app.findContainingRoom(element.x, element.y, element.width, element.height)
                                            if (currentRoom != null) {
                                                if (app.findContainingRoom(newX, element.y, element.width, element.height) != null) {
                                                    element.x = newX
                                                } else {
                                                    if (newX < currentRoom.x) element.x = currentRoom.x
                                                    else if (newX + element.width > currentRoom.x + currentRoom.width) element.x = currentRoom.x + currentRoom.width - element.width
                                                }
                                                if (app.findContainingRoom(element.x, newY, element.width, element.height) != null) {
                                                    element.y = newY
                                                } else {
                                                    if (newY < currentRoom.y) element.y = currentRoom.y
                                                    else if (newY + element.height > currentRoom.y + currentRoom.height) element.y = currentRoom.y + currentRoom.height - element.height
                                                }
                                                val modelDragStartX = app.screenToModel(e.x, 0.0) - (element.x - initial.x)
                                                val modelDragStartY = app.screenToModel(e.y, 0.0) - (element.y - initial.y)
                                                dragStart = Point(app.modelToScreen(modelDragStartX, 0.0), app.modelToScreen(modelDragStartY, 0.0))
                                            }
                                        }
                                    } else if (element is FloorOpening && initialVertices != null) {
                                        val dx_m = newX - initial.x
                                        val dy_m = newY - initial.y
                                        
                                        val tempVertices = initialVertices!!.map { Point(it.x + dx_m, it.y + dy_m) }.toMutableList()
                                        val tempES = FloorOpening(tempVertices)
                                        if (app.findContainingRoomForFloorOpening(tempES) != null) {
                                            element.vertices.clear()
                                            element.vertices.addAll(tempVertices)
                                            element.x = newX
                                            element.y = newY
                                            element.updateBounds()
                                        } else {
                                            // Handle sticking for FloorOpening movement
                                            val currentRoom = app.findContainingRoomForFloorOpening(element)
                                            if (currentRoom != null) {
                                                // Try X move
                                                val tempVerticesX = initialVertices!!.map { Point(it.x + dx_m, it.y + (element.y - initial.y)) }.toMutableList()
                                                if (app.findContainingRoomForFloorOpening(FloorOpening(tempVerticesX)) != null) {
                                                    element.vertices.clear()
                                                    element.vertices.addAll(tempVerticesX)
                                                    element.x = newX
                                                }
                                                // Try Y move
                                                val tempVerticesY = initialVertices!!.map { Point(it.x + (element.x - initial.x), it.y + dy_m) }.toMutableList()
                                                if (app.findContainingRoomForFloorOpening(FloorOpening(tempVerticesY)) != null) {
                                                    element.vertices.clear()
                                                    element.vertices.addAll(tempVerticesY)
                                                    element.y = newY
                                                }
                                                element.updateBounds()
                                                
                                                val modelDragStartX = app.screenToModel(e.x, 0.0) - (element.x - initial.x)
                                                val modelDragStartY = app.screenToModel(e.y, 0.0) - (element.y - initial.y)
                                                dragStart = Point(app.modelToScreen(modelDragStartX, 0.0), app.modelToScreen(modelDragStartY, 0.0))
                                            }
                                        }
                                    }
                            }
                        }

                        app.sidePanel.updateFields(element)
                        app.statsPanel.update()
                    } else {
                        val pStart = panStart
                        val initOX = initialOffsetX
                        val initOY = initialOffsetY
                        if (pStart != null && initOX != null && initOY != null) {
                            val dx = app.screenToModel(e.x, 0.0) - app.screenToModel(pStart.x, 0.0)
                            val dy = app.screenToModel(e.y, 0.0) - app.screenToModel(pStart.y, 0.0)
                            app.offsetX = initOX + dx
                            app.offsetY = initOY + dy
                        }
                    }
                    repaint()
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (dragStart != null || activeHandle != ResizeHandle.NONE || draggingOrigin) {
                        app.saveState()
                    }
                    if (e.isPopupTrigger && app.currentMode == AppMode.NORMAL) {
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
                val mouseModelX = app.screenToModel(mousePos.x, app.offsetX)
                val mouseModelY = app.screenToModel(mousePos.y, app.offsetY)

                val rotation = e.preciseWheelRotation
                val factor = 1.1.pow(-rotation)
                app.scale = (app.scale * factor).coerceIn(app.MIN_SCALE, app.MAX_SCALE)
        
                // Adjust offsets to zoom towards mouse position
                app.offsetX = mousePos.x / (app.scale * app.pixelsPerCm) - mouseModelX
                app.offsetY = mousePos.y / (app.scale * app.pixelsPerCm) - mouseModelY
                
                app.updateScaleLabel()
                repaint()
            }
        }

        private fun showPopup(e: MouseEvent) {
            // Select element under mouse before showing popup
            app.selectedElement = app.elements.reversed().find { 
                it.contains(app.screenToModel(e.x, app.offsetX).roundToInt(), app.screenToModel(e.y, app.offsetY).roundToInt())
            }
            if (app.selectedElement != null) {
                app.elementStatsPanel.updateElementStats(app.selectedElement)
                app.sidePanel.updateFields(app.selectedElement!!)
            } else {
                app.elementStatsPanel.updateElementStats(null)
                app.sidePanel.clearFields()
            }
            app.canvas.repaint()

            val hasSelection = app.selectedElement != null
            val isWall = app.selectedElement is Wall
            val isRoom = app.selectedElement is Room

            // If empty space: Add Wall, Add Room, Add Floor Opening
            // If selection: Duplicate, Remove, [Add Window/Door if Wall], [Add Stairs/Floor Opening if Room]

            if (!hasSelection) {
                app.popAddWallMenu.isVisible = true
                app.popAddRoomMenu.isVisible = true
                
                // Only show "Add Floor Opening" if clicking over a room
                val roomAtPoint = app.elements.reversed().filterIsInstance<Room>().find { 
                    it.contains(app.screenToModel(e.x, app.offsetX).roundToInt(), app.screenToModel(e.y, app.offsetY).roundToInt())
                }
                app.popAddFloorOpeningMenu.isVisible = roomAtPoint != null
            
                app.popSepGeneral.isVisible = false
                app.popDuplicateMenu.isVisible = false
                app.popRemoveMenu.isVisible = false
                app.popSepElements.isVisible = false
                app.popAddWindowMenu.isVisible = false
                app.popAddDoorMenu.isVisible = false
                app.popSepRoom.isVisible = false
                app.popAddStairsMenu.isVisible = false
            } else {
                app.popAddWallMenu.isVisible = false
                app.popAddRoomMenu.isVisible = false
                // "Add Floor opening" is visible only if selected element is a Room
                app.popAddFloorOpeningMenu.isVisible = isRoom
            
                app.popSepGeneral.isVisible = true
                app.popDuplicateMenu.isVisible = true
                app.popRemoveMenu.isVisible = true
            
                app.popSepElements.isVisible = isWall
                app.popAddWindowMenu.isVisible = isWall
                app.popAddDoorMenu.isVisible = isWall
            
                app.popSepRoom.isVisible = isRoom
                app.popAddStairsMenu.isVisible = isRoom
            }
        
            app.popupMenu.show(e.component, e.x, e.y)
        }

        private fun getStickyCoord(coord: Int, size: Int, isX: Boolean): Int {
            var bestCoord = coord
            var minDiff = STICK_THRESHOLD + 1

            for (other in app.elements) {
                if (other === app.selectedElement) continue
                
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
            val rooms = app.elements.filterIsInstance<Room>()
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
            val rooms = app.elements.filterIsInstance<Room>()
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
                val nested = app.elements.filter { (it is Stairs || it is FloorOpening) && rBounds.contains(it.getBounds()) }
                for (el in nested) {
                    totalArea -= el.getArea()
                }
            }
            return totalArea
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g as Graphics2D
            drawScene(g2, app.offsetX, app.offsetY, app.scale, width, height)
        }

        fun drawScene(g2: Graphics2D, offX: Double, offY: Double, sc: Double, w: Int, h: Int) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            if (!app.isExporting) {
                // Draw axes
                g2.color = Color.LIGHT_GRAY
                val axisX = app.modelToScreen(0.0, offX, sc)
                val axisY = app.modelToScreen(0.0, offY, sc)
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
                var gridX = (app.screenToModel(0, offX, sc) / step).roundToInt() * step
                while (app.modelToScreen(gridX.toDouble(), offX, sc) < w) {
                    val sx = app.modelToScreen(gridX.toDouble(), offX, sc)
                    g2.drawLine(sx, 0, sx, h)
                    gridX += step
                }

                // Y grid lines
                var gridY = (app.screenToModel(0, offY, sc) / step).roundToInt() * step
                while (app.modelToScreen(gridY.toDouble(), offY, sc) < h) {
                    val sy = app.modelToScreen(gridY.toDouble(), offY, sc)
                    g2.drawLine(0, sy, w, sy)
                    gridY += step
                }
                g2.stroke = oldStroke
                
                // X axis ticks
                g2.color = Color.GRAY
                var startX = (app.screenToModel(0, offX, sc) / step).roundToInt() * step
                while (app.modelToScreen(startX.toDouble(), offX, sc) < w) {
                    val sx = app.modelToScreen(startX.toDouble(), offX, sc)
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
                var startY = (app.screenToModel(0, offY, sc) / step).roundToInt() * step
                while (app.modelToScreen(startY.toDouble(), offY, sc) < h) {
                    val sy = app.modelToScreen(startY.toDouble(), offY, sc)
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
            val rooms = app.elements.filterIsInstance<Room>()
            val floorOpenings = app.elements.filterIsInstance<FloorOpening>()
            val walls = app.elements.filterIsInstance<Wall>()
            val stairs = app.elements.filterIsInstance<Stairs>()
            val attachments = app.elements.filter { it is PlanWindow || it is Door }
            
            rooms.forEach { drawElement(g2, it) }
            floorOpenings.forEach { drawElement(g2, it) }
            walls.forEach { drawElement(g2, it) }
            stairs.forEach { drawElement(g2, it) }
            attachments.forEach { drawElement(g2, it) }

            // Draw overlap regions
            val intersections = app.calculateIntersections()
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
                    val sx = app.modelToScreen(rect.x.toDouble(), offX, sc)
                    val sy = app.modelToScreen(rect.y.toDouble(), offY, sc)
                    val sw = app.modelToScreen((rect.x + rect.width).toDouble(), offX, sc) - sx
                    val sh = app.modelToScreen((rect.y + rect.height).toDouble(), offY, sc) - sy
                    g2.fillRect(sx, sy, sw, sh)
                }
                g2.paint = oldPaint
            }

            // Draw selection markers ALWAYS on top
            app.selectedElement?.let { el ->
                val sx = app.modelToScreen(el.x.toDouble(), offX, sc)
                val sy = app.modelToScreen(el.y.toDouble(), offY, sc)
                val sw = app.modelToScreen((el.x + el.width).toDouble(), offX, sc) - sx
                val sh = app.modelToScreen((el.y + el.height).toDouble(), offY, sc) - sy
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
                    val sx = app.modelToScreen(m.x.toDouble(), offX, sc)
                    val sy = app.modelToScreen(m.y.toDouble(), offY, sc)
                
                    g2.fillOval(sx - 4, sy - 4, 8, 8)
                
                    if (lastP != null) {
                        g2.stroke = solidStroke
                        val lsx = app.modelToScreen(lastP.x.toDouble(), offX, sc)
                        val lsy = app.modelToScreen(lastP.y.toDouble(), offY, sc)
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
                    val fsx = app.modelToScreen(first.x.toDouble(), offX, sc)
                    val fsy = app.modelToScreen(first.y.toDouble(), offY, sc)
                    val lsx = app.modelToScreen(last.x.toDouble(), offX, sc)
                    val lsy = app.modelToScreen(last.y.toDouble(), offY, sc)
                    
                    g2.stroke = solidStroke
                    g2.drawLine(lsx, lsy, fsx, fsy)
                    
                    val dist = first.distance(last)
                    totalDist += dist
                    val label = "%.1f cm (Total: %.1f cm)".format(dist, totalDist)
                    g2.drawString(label, (lsx + fsx) / 2 + 5, (lsy + fsy) / 2 - 5)
                }
            
                if (rulerProbeEnabled) {
                    rulerProbePoint?.let { probe ->
                        val sx = app.modelToScreen(probe.x.toDouble(), offX, sc)
                        val sy = app.modelToScreen(probe.y.toDouble(), offY, sc)
                    
                        if (lastP != null) {
                            g2.stroke = rulerStroke
                            val lsx = app.modelToScreen(lastP.x.toDouble(), offX, sc)
                            val lsy = app.modelToScreen(lastP.y.toDouble(), offY, sc)
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
                    val nested = app.elements.filter { (it is Stairs || it is FloorOpening) && rBounds.contains(it.getBounds()) }
                    for (el in nested) {
                        rCorrected -= el.getArea()
                    }
                    groupCorrectedArea += rCorrected
                }

                val sx = app.modelToScreen(largestRoom.x.toDouble(), offX, sc)
                val sy = app.modelToScreen(largestRoom.y.toDouble(), offY, sc)
                val sw = app.modelToScreen((largestRoom.x + largestRoom.width).toDouble(), offX, sc) - sx
                val sh = app.modelToScreen((largestRoom.y + largestRoom.height).toDouble(), offY, sc) - sy

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

            if (app.showDimensionLabels || app.isExporting) {
                drawDimensionLabels(g2)
            }
        }

        private fun drawDimensionLabels(g2: Graphics2D) {
            val rooms = app.elements.filterIsInstance<Room>()
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
                    val scY = app.modelToScreen(centerY, app.offsetY)
                    
                    val sscx1 = app.modelToScreen(totalMinX.toDouble(), app.offsetX)
                    val sscx2 = app.modelToScreen(totalMaxX.toDouble(), app.offsetX)
                    
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
                    val scX = app.modelToScreen(centerX, app.offsetX)
                    
                    val sscy1 = app.modelToScreen(totalMinY.toDouble(), app.offsetY)
                    val sscy2 = app.modelToScreen(totalMaxY.toDouble(), app.offsetY)
                    
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
                val rsx = app.modelToScreen(room.x.toDouble(), app.offsetX)
                val rsy = app.modelToScreen(room.y.toDouble(), app.offsetY)
                val rsw = app.modelToScreen((room.x + room.width).toDouble(), app.offsetX) - rsx
                val rsh = app.modelToScreen((room.y + room.height).toDouble(), app.offsetY) - rsy
                
                val marginPx = (margin * app.scale * app.pixelsPerCm).roundToInt()
                
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
            for (el in app.elements) {
                if (el is PlanWindow || el is Door) {
                    val wall = app.findContainingWall(el.x, el.y, el.width, el.height)
                    val effectiveWidth = if (wall != null) {
                        val isVertical = wall.width < wall.height
                        if (isVertical) el.height else el.width
                    } else {
                        maxOf(el.width, el.height)
                    }
                    val h3d = if (el is PlanWindow) el.height3D else (el as Door).height3D
                    val label = "($effectiveWidth x $h3d)"
                    
                    val sx = app.modelToScreen(el.x.toDouble(), app.offsetX)
                    val sy = app.modelToScreen(el.y.toDouble(), app.offsetY)
                    val sw = app.modelToScreen((el.x + el.width).toDouble(), app.offsetX) - sx
                    val sh = app.modelToScreen((el.y + el.height).toDouble(), app.offsetY) - sy
                    
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
            val sx = app.modelToScreen(el.x.toDouble(), app.offsetX)
            val sy = app.modelToScreen(el.y.toDouble(), app.offsetY)
            val sw = app.modelToScreen((el.x + el.width).toDouble(), app.offsetX) - sx
            val sh = app.modelToScreen((el.y + el.height).toDouble(), app.offsetY) - sy

            when (el.type) {
                ElementType.WALL -> g2.color = Color.DARK_GRAY
                ElementType.ROOM -> g2.color = Color.LIGHT_GRAY
                ElementType.WINDOW -> g2.color = Color.CYAN
                ElementType.DOOR -> g2.color = Color.ORANGE
                ElementType.FLOOR_OPENING -> {
                    g2.color = Color(230, 230, 230) // Light gray
                    val poly = Polygon()
                    (el as FloorOpening).vertices.forEach {
                        poly.addPoint(app.modelToScreen(it.x.toDouble(), app.offsetX), app.modelToScreen(it.y.toDouble(), app.offsetY))
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
                    val stepPx = (normalStairDepthCm * app.scale * app.pixelsPerCm).roundToInt()
                    
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
            if (app.selectedElement is FloorOpening) {
                val poly = Polygon()
                (app.selectedElement as FloorOpening).vertices.forEach {
                    poly.addPoint(app.modelToScreen(it.x.toDouble(), app.offsetX), app.modelToScreen(it.y.toDouble(), app.offsetY))
                }
                g2.drawPolygon(poly)
                
                g2.color = Color.WHITE
                val r = HANDLE_SIZE / 2
                (app.selectedElement as FloorOpening).vertices.forEachIndexed { index, it ->
                    val vsx = app.modelToScreen(it.x.toDouble(), app.offsetX)
                    val vsy = app.modelToScreen(it.y.toDouble(), app.offsetY)
                    
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
