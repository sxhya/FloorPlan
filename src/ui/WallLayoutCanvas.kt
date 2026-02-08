package ui

import java.awt.geom.Rectangle2D
import java.util.PriorityQueue
import model.Window as PlanWindow
import model.*
import java.awt.*
import java.awt.event.*
import javax.swing.*
import kotlin.math.*

class WallLayoutCanvas(val doc: WallLayoutDocument) : JPanel() {
    private var dragPoint: WallLayoutPoint? = null
    private var isPanning = false
    private var lastMouseX = 0
    private var lastMouseY = 0
    private val pointRadius = 6

    init {
        background = Color.WHITE
        
        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showPopup(e)
                    return
                }
                
                val modelX = doc.screenToModel(e.x, doc.offsetX)
                val modelZ = doc.screenToModel(e.y, doc.offsetY, true)
                
                dragPoint = doc.layout.points.find { p ->
                    val sx = doc.modelToScreen(p.x, doc.offsetX)
                    val sy = doc.modelToScreen(p.z.toDouble(), doc.offsetY, true)
                    abs(sx - e.x) <= pointRadius && abs(sy - e.y) <= pointRadius
                }
                
                doc.selectedPoint = dragPoint
                isPanning = (dragPoint == null)
                doc.app.sidePanel.updateFieldsForActiveDocument()
                lastMouseX = e.x
                lastMouseY = e.y
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (dragPoint != null) {
                    doc.saveState()
                }
                dragPoint = null
                isPanning = false
            }

            override fun mouseDragged(e: MouseEvent) {
                if (isPanning) {
                    val dxScreen = (e.x - lastMouseX).toDouble()
                    val dyScreen = (e.y - lastMouseY).toDouble()
                    
                    doc.pan(dxScreen, dyScreen)
                    
                    lastMouseX = e.x
                    lastMouseY = e.y
                    repaint()
                    return
                }

                val dp = dragPoint ?: return
                
                val dx = (e.x - lastMouseX) / (doc.scale * doc.pixelsPerCm)
                val dz = -(e.y - lastMouseY) / (doc.scale * doc.pixelsPerCm)
                
                var newX = if (doc.isInverted) dp.x - dx else dp.x + dx
                var newZ = dp.z + dz
                
                // Constraints
                val wallHeight = doc.app.getThreeDDocuments().firstOrNull()?.model?.getBounds()?.let { (it.second.z - it.first.z) } ?: 300.0
                newX = newX.coerceIn(doc.wallStart, doc.wallEnd)
                newZ = newZ.coerceIn(0.0, wallHeight)
                
                // Sticky behavior
                val threshold = 5.0 / doc.scale / doc.pixelsPerCm
                for (p in doc.layout.points) {
                    if (p === dp) continue
                    if (abs(p.x - newX) < threshold) newX = p.x
                    if (abs(p.z.toDouble() - newZ) < threshold) newZ = p.z.toDouble()
                }

                val perpEdges = getPerpendicularWallEdges()
                for (edgeX in perpEdges) {
                    if (abs(edgeX - newX) < threshold) newX = edgeX
                }
                
                dp.x = newX
                dp.z = newZ.roundToInt()
                
                lastMouseX = e.x
                lastMouseY = e.y
                doc.app.repaintAllCanvases()
                doc.app.sidePanel.updateFieldsForActiveDocument()
            }
        }
        
        addMouseListener(mouseAdapter)
        addMouseMotionListener(mouseAdapter)

        addMouseWheelListener { e ->
            val centerX = this.width / 2.0
            val centerY = this.height / 2.0
            
            val rotation = e.preciseWheelRotation
            val factor = 1.1.pow(-rotation)
            doc.zoom(factor, centerX, centerY)

            repaint()
        }
    }

    private fun showPopup(e: MouseEvent) {
        val menu = JPopupMenu()
        val addPointItem = JMenuItem("Add point")
        addPointItem.addActionListener {
            val wallWidth = (doc.wallEnd - doc.wallStart)
            val wallHeight = doc.app.getThreeDDocuments().firstOrNull()?.model?.getBounds()?.let { (it.second.z - it.first.z) } ?: 300.0
            
            val modelX = doc.screenToModel(e.x, doc.offsetX).coerceIn(doc.wallStart, doc.wallEnd)
            val modelZ = doc.screenToModel(e.y, doc.offsetY, true).coerceIn(0.0, wallHeight).roundToInt()
            doc.layout.points.add(WallLayoutPoint(modelX, modelZ, 0))
            doc.saveState()
            repaint()
        }
        menu.add(addPointItem)

        val pointUnderMouse = doc.layout.points.find { p ->
            val sx = doc.modelToScreen(p.x, doc.offsetX)
            val sy = doc.modelToScreen(p.z.toDouble(), doc.offsetY, true)
            abs(sx - e.x) <= pointRadius && abs(sy - e.y) <= pointRadius
        }

        if (pointUnderMouse != null) {
            val removePointItem = JMenuItem("Delete point")
            removePointItem.addActionListener {
                doc.layout.points.remove(pointUnderMouse)
                if (doc.selectedPoint === pointUnderMouse) doc.selectedPoint = null
                doc.saveState()
                repaint()
            }
            menu.add(removePointItem)
        }

        menu.show(this, e.x, e.y)
    }

    private fun getPerpendicularWallEdges(): List<Double> {
        val edges = mutableListOf<Double>()
        val isVertical = doc.isVertical
        val otherWalls = doc.floorPlanDoc.elements.filterIsInstance<Wall>().filter { it !== doc.wall }
        val wallBounds = doc.wall.getBounds()

        for (other in otherWalls) {
            val otherBounds = other.getBounds()
            val otherIsVertical = other.width < other.height
            if (isVertical != otherIsVertical) {
                if (isVertical) {
                    if (otherBounds.y < wallBounds.y + wallBounds.height && otherBounds.y + otherBounds.height > wallBounds.y) {
                        val docksToEditedSide = if (doc.isFront) {
                            otherBounds.x + otherBounds.width == wallBounds.x
                        } else {
                            otherBounds.x == wallBounds.x + wallBounds.width
                        }
                        if (docksToEditedSide) {
                            val overlapStart = maxOf(wallBounds.y, otherBounds.y)
                            val overlapEnd = minOf(wallBounds.y + wallBounds.height, otherBounds.y + otherBounds.height)
                            edges.add(overlapStart.toDouble())
                            edges.add(overlapEnd.toDouble())
                        }
                    }
                } else {
                    if (otherBounds.x < wallBounds.x + wallBounds.width && otherBounds.x + otherBounds.width > wallBounds.x) {
                        val docksToEditedSide = if (doc.isFront) {
                            otherBounds.y + otherBounds.height == wallBounds.y
                        } else {
                            otherBounds.y == wallBounds.y + wallBounds.height
                        }
                        if (docksToEditedSide) {
                            val overlapStart = maxOf(wallBounds.x, otherBounds.x)
                            val overlapEnd = minOf(wallBounds.x + wallBounds.width, otherBounds.x + otherBounds.width)
                            edges.add(overlapStart.toDouble())
                            edges.add(overlapEnd.toDouble())
                        }
                    }
                }
            }
        }
        return edges
    }

    private fun getDockedPoints(): List<WallLayoutPoint> {
        val dockedPoints = mutableListOf<WallLayoutPoint>()
        val wall = doc.wall
        val isFront = doc.isFront
        val isVertical = doc.isVertical
        
        // Define the line segment of the current wall side in floor plan
        val wallSideX = if (isVertical) (if (isFront) wall.x else wall.x + wall.width).toDouble() else 0.0
        val wallSideY = if (!isVertical) (if (isFront) wall.y else wall.y + wall.height).toDouble() else 0.0
        
        val wallStart = if (isVertical) wall.y.toDouble() else wall.x.toDouble()
        val wallEnd = if (isVertical) (wall.y + wall.height).toDouble() else (wall.x + wall.width).toDouble()

        val otherWalls = doc.floorPlanDoc.elements.filterIsInstance<Wall>().filter { it !== wall }

        for (other in otherWalls) {
            val layouts = listOf(true to other.frontLayout, false to other.backLayout)
            for ((otherIsFront, layout) in layouts) {
                for (p in layout.points) {
                    val fp = other.getFloorPlanCoords(p, otherIsFront)
                    
                    // Check if this floor plan point lies on the current wall's face segment
                    val onSegment = if (isVertical) {
                        abs(fp.x - wallSideX) < 1e-6 && fp.y >= wallStart - 1e-6 && fp.y <= wallEnd + 1e-6
                    } else {
                        abs(fp.y - wallSideY) < 1e-6 && fp.x >= wallStart - 1e-6 && fp.x <= wallEnd + 1e-6
                    }

                    if (onSegment) {
                        // The coordinate along the current wall's layout is fp.y if vertical, fp.x if horizontal
                        val layoutX = if (isVertical) fp.y else fp.x
                        
                        // Avoid duplicates if the point is already in doc.layout.points
                        val alreadyExists = doc.layout.points.any { 
                            abs(it.x - layoutX) < 1e-6 && it.z == p.z && it.kind == p.kind 
                        }
                        
                        if (!alreadyExists) {
                            dockedPoints.add(WallLayoutPoint(layoutX, p.z, p.kind))
                        }
                    }
                }
            }
        }
        return dockedPoints
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        
        drawGrid(g2)
        drawWallBackground(g2)
        drawRuler(g2)
        drawAxes(g2)
        drawConnections(g2)
        drawPoints(g2)
    }

    private fun drawConnections(g2: Graphics2D) {
        val allPoints = doc.layout.points + getDockedPoints()
        val pointsByKind = allPoints.groupBy { it.kind }
        if (pointsByKind.isEmpty()) return

        val wallHeight = doc.app.getThreeDDocuments().firstOrNull()?.model?.getBounds()?.let { (it.second.z - it.first.z) } ?: 300.0

        val openings = doc.floorPlanDoc.elements.filter { it is PlanWindow || it is Door }
            .filter { doc.floorPlanDoc.findContainingWall(it.x, it.y, it.width, it.height) === doc.wall }
            .map { op ->
                val isVertical = doc.isVertical
                val absPos = if (isVertical) op.y else op.x
                val opWidth = if (isVertical) op.height else op.width
                val opHeight3D = if (op is PlanWindow) op.height3D else (op as Door).verticalHeight
                val sillElevation = if (op is PlanWindow) op.sillElevation else 0
                Rectangle2D.Double(absPos.toDouble(), sillElevation.toDouble(), opWidth.toDouble(), opHeight3D.toDouble())
            }

        for ((kindIdx, points) in pointsByKind) {
            if (points.size < 2) continue
            
            val kind = doc.floorPlanDoc.kinds.getOrNull(kindIdx)
            g2.color = kind?.color ?: Color.BLACK
            
            val edges = calculateManhattanMST(points)
            for (edge in edges) {
                drawObstacleAvoidingPath(g2, edge.first, edge.second, openings, doc.wallStart, doc.wallEnd, wallHeight)
            }
        }
    }

    private fun drawObstacleAvoidingPath(
        g2: Graphics2D,
        p1: WallLayoutPoint,
        p2: WallLayoutPoint,
        openings: List<Rectangle2D.Double>,
        wallStart: Double,
        wallEnd: Double,
        wallHeight: Double
    ) {
        // Collect all relevant X and Z coordinates for the Hanan grid
        val xCoords = mutableSetOf(wallStart, wallEnd, p1.x, p2.x)
        val zCoords = mutableSetOf(0.0, wallHeight, p1.z.toDouble(), p2.z.toDouble())
        
        for (op in openings) {
            xCoords.add(op.x)
            xCoords.add(op.x + op.width)
            zCoords.add(op.y)
            zCoords.add(op.y + op.height)
        }
        
        val sortedX = xCoords.filter { it in wallStart..wallEnd }.sorted()
        val sortedZ = zCoords.filter { it in 0.0..wallHeight }.sorted()
        
        // A* algorithm on the Hanan grid
        val startPos = Pair(sortedX.indexOf(p1.x), sortedZ.indexOf(p1.z.toDouble()))
        val targetPos = Pair(sortedX.indexOf(p2.x), sortedZ.indexOf(p2.z.toDouble()))
        
        if (startPos.first == -1 || startPos.second == -1 || targetPos.first == -1 || targetPos.second == -1) {
            // Fallback to simple path if something goes wrong
            drawOrthogonalPath(g2, p1, p2)
            return
        }

        // Direction: 0 = None, 1 = Horizontal, 2 = Vertical
        val openSet = PriorityQueue<PathNode>(compareBy { it.fScore })
        // Key: (xIndex, zIndex, direction)
        val gScore = mutableMapOf<Triple<Int, Int, Int>, Double>().withDefault { Double.MAX_VALUE }
        val cameFrom = mutableMapOf<Triple<Int, Int, Int>, Triple<Int, Int, Int>>()

        val startNode = Triple(startPos.first, startPos.second, 0)
        gScore[startNode] = 0.0
        openSet.add(PathNode(startNode, heuristic(startPos, targetPos, sortedX, sortedZ), 0.0))

        val turnPenalty = 5.0 // Penalty for each 90-degree turn in cm

        var bestFinalNode: Triple<Int, Int, Int>? = null

        while (openSet.isNotEmpty()) {
            val current = openSet.poll().state
            val (currX, currZ, currDir) = current

            if (currX == targetPos.first && currZ == targetPos.second) {
                bestFinalNode = current
                break
            }

            for (neighborPos in getNeighbors(Pair(currX, currZ), sortedX.size, sortedZ.size)) {
                // Determine direction to neighbor
                val nextDir = if (neighborPos.first != currX) 1 else 2
                
                if (isEdgeBlocked(Pair(currX, currZ), neighborPos, sortedX, sortedZ, openings)) continue

                val moveDist = dist(Pair(currX, currZ), neighborPos, sortedX, sortedZ)
                val cost = if (currDir != 0 && currDir != nextDir) turnPenalty else 0.0
                val tentativeGScore = gScore.getValue(current) + moveDist + cost

                val nextState = Triple(neighborPos.first, neighborPos.second, nextDir)

                if (tentativeGScore < gScore.getValue(nextState)) {
                    cameFrom[nextState] = current
                    gScore[nextState] = tentativeGScore
                    val h = heuristic(neighborPos, targetPos, sortedX, sortedZ)
                    openSet.add(PathNode(nextState, tentativeGScore + h, tentativeGScore))
                }
            }
        }

        if (bestFinalNode != null) {
            drawPath(g2, cameFrom, bestFinalNode, sortedX, sortedZ)
        } else {
            // No path found, fallback
            drawOrthogonalPath(g2, p1, p2)
        }
    }

    private data class PathNode(val state: Triple<Int, Int, Int>, val fScore: Double, val gScore: Double)

    private fun heuristic(a: Pair<Int, Int>, b: Pair<Int, Int>, x: List<Double>, z: List<Double>): Double {
        return abs(x[a.first] - x[b.first]) + abs(z[a.second] - z[b.second])
    }

    private fun dist(a: Pair<Int, Int>, b: Pair<Int, Int>, x: List<Double>, z: List<Double>): Double {
        return abs(x[a.first] - x[b.first]) + abs(z[a.second] - z[b.second])
    }

    private fun getNeighbors(pos: Pair<Int, Int>, maxX: Int, maxZ: Int): List<Pair<Int, Int>> {
        val neighbors = mutableListOf<Pair<Int, Int>>()
        if (pos.first > 0) neighbors.add(Pair(pos.first - 1, pos.second))
        if (pos.first < maxX - 1) neighbors.add(Pair(pos.first + 1, pos.second))
        if (pos.second > 0) neighbors.add(Pair(pos.first, pos.second - 1))
        if (pos.second < maxZ - 1) neighbors.add(Pair(pos.first, pos.second + 1))
        return neighbors
    }

    private fun isEdgeBlocked(
        a: Pair<Int, Int>,
        b: Pair<Int, Int>,
        x: List<Double>,
        z: List<Double>,
        openings: List<Rectangle2D.Double>
    ): Boolean {
        // Small epsilon to allow lines on the edge of openings
        val eps = 0.1
        for (op in openings) {
            // If it's a horizontal edge
            if (a.second == b.second) {
                val zCoord = z[a.second]
                if (zCoord > op.y + eps && zCoord < op.y + op.height - eps) {
                    val minX = minOf(x[a.first], x[b.first])
                    val maxX = maxOf(x[a.first], x[b.first])
                    if (minX < op.x + op.width - eps && maxX > op.x + eps) return true
                }
            } else { // Vertical edge
                val xCoord = x[a.first]
                if (xCoord > op.x + eps && xCoord < op.x + op.width - eps) {
                    val minZ = minOf(z[a.second], z[b.second])
                    val maxZ = maxOf(z[a.second], z[b.second])
                    if (minZ < op.y + op.height - eps && maxZ > op.y + eps) return true
                }
            }
        }
        return false
    }

    private fun drawPath(
        g2: Graphics2D,
        cameFrom: Map<Triple<Int, Int, Int>, Triple<Int, Int, Int>>,
        targetState: Triple<Int, Int, Int>,
        x: List<Double>,
        z: List<Double>
    ) {
        var current = targetState
        while (cameFrom.containsKey(current)) {
            val prev = cameFrom[current]!!
            
            val sx1 = doc.modelToScreen(x[current.first], doc.offsetX)
            val sz1 = doc.modelToScreen(z[current.second], doc.offsetY, true)
            val sx2 = doc.modelToScreen(x[prev.first], doc.offsetX)
            val sz2 = doc.modelToScreen(z[prev.second], doc.offsetY, true)
            g2.drawLine(sx1, sz1, sx2, sz2)
            current = prev
        }
    }

    private fun calculateManhattanMST(points: List<WallLayoutPoint>): List<Pair<WallLayoutPoint, WallLayoutPoint>> {
        if (points.size < 2) return emptyList()
        
        val edges = mutableListOf<Pair<WallLayoutPoint, WallLayoutPoint>>()
        val visited = mutableSetOf<WallLayoutPoint>()
        val unvisited = points.toMutableSet()
        
        visited.add(points[0])
        unvisited.remove(points[0])
        
        while (unvisited.isNotEmpty()) {
            var minDistance = Double.MAX_VALUE
            var bestEdge: Pair<WallLayoutPoint, WallLayoutPoint>? = null
            
            for (v in visited) {
                for (u in unvisited) {
                    val dist = abs(v.x - u.x) + abs(v.z - u.z)
                    if (dist < minDistance) {
                        minDistance = dist
                        bestEdge = Pair(v, u)
                    }
                }
            }
            
            if (bestEdge != null) {
                edges.add(bestEdge)
                visited.add(bestEdge.second)
                unvisited.remove(bestEdge.second)
            }
        }
        return edges
    }

    private fun drawOrthogonalPath(g2: Graphics2D, p1: WallLayoutPoint, p2: WallLayoutPoint) {
        val x1 = doc.modelToScreen(p1.x, doc.offsetX)
        val z1 = doc.modelToScreen(p1.z.toDouble(), doc.offsetY, true)
        val x2 = doc.modelToScreen(p2.x, doc.offsetX)
        val z2 = doc.modelToScreen(p2.z.toDouble(), doc.offsetY, true)
        
        // Orthogonal path from (x1, z1) to (x2, z2)
        // Two options: (x1, z1) -> (x2, z1) -> (x2, z2) OR (x1, z1) -> (x1, z2) -> (x2, z2)
        // We can just pick one, or try to be smart. Let's just pick one for now.
        g2.drawLine(x1, z1, x2, z1)
        g2.drawLine(x2, z1, x2, z2)
    }

    private fun drawAxes(g2: Graphics2D) {
        val axisX = doc.modelToScreen(0.0, doc.offsetX)
        val axisY = doc.modelToScreen(0.0, doc.offsetY, true) // This corresponds to Z=0 in model
        
        val w = width
        val h = height

        // Draw axes lines (like in floor plan)
        g2.color = Color.LIGHT_GRAY
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

        // X axis ticks
        val m0 = doc.screenToModel(0, doc.offsetX)
        val mw = doc.screenToModel(w, doc.offsetX)
        val minM = minOf(m0, mw)
        val maxM = maxOf(m0, mw)
        
        var startX = (floor(minM / step) * step).roundToInt()
        while (startX <= maxM) {
            val sx = doc.modelToScreen(startX.toDouble(), doc.offsetX)
            if (sx in 0..w) {
                g2.drawLine(sx, axisY - 5, sx, axisY + 5)
                val label = if (startX == 0) "0" else if (abs(startX) % 100 == 0) "${startX / 100}m" else "${startX}cm"
                g2.drawString(label, sx + 2, axisY - 2)
            }
            startX += step
        }

        // Z axis ticks (vertical axis, like Y in floor plan)
        val mz0 = doc.screenToModel(0, doc.offsetY, true)
        val mzh = doc.screenToModel(h, doc.offsetY, true)
        val minZ = minOf(mz0, mzh)
        val maxZ = maxOf(mz0, mzh)

        var startZ = (floor(minZ / step) * step).roundToInt()
        while (startZ <= maxZ) {
            val sy = doc.modelToScreen(startZ.toDouble(), doc.offsetY, true)
            if (sy in 0..h) {
                g2.drawLine(axisX - 5, sy, axisX + 5, sy)
                if (startZ != 0) {
                    val label = if (abs(startZ) % 100 == 0) "${startZ / 100}m" else "${startZ}cm"
                    val metrics = g2.fontMetrics
                    val labelWidth = metrics.stringWidth(label)
                    g2.drawString(label, axisX - labelWidth - 7, sy - 2)
                }
            }
            startZ += step
        }
    }

    private fun drawGrid(g2: Graphics2D) {
        val gridSize = 100.0 // 1 meter grid
        val stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0f, floatArrayOf(2f, 2f), 0f)
        g2.stroke = stroke
        g2.color = Color(220, 220, 220)

        val w = width
        val h = height

        val m0 = doc.screenToModel(0, doc.offsetX)
        val mw = doc.screenToModel(w, doc.offsetX)
        val minM = minOf(m0, mw)
        val maxM = maxOf(m0, mw)

        var x = (floor(minM / gridSize) * gridSize)
        while (x <= maxM) {
            val sx = doc.modelToScreen(x, doc.offsetX)
            if (sx in 0..w) {
                g2.drawLine(sx, 0, sx, h)
            }
            x += gridSize
        }

        val mz0 = doc.screenToModel(0, doc.offsetY, true)
        val mzh = doc.screenToModel(h, doc.offsetY, true)
        val minZ = minOf(mz0, mzh)
        val maxZ = maxOf(mz0, mzh)

        var y = (floor(minZ / gridSize) * gridSize)
        while (y <= maxZ) {
            val sy = doc.modelToScreen(y, doc.offsetY, true)
            if (sy in 0..h) {
                g2.drawLine(0, sy, w, sy)
            }
            y += gridSize
        }
        g2.stroke = BasicStroke(1f)
    }

    private fun drawWallBackground(g2: Graphics2D) {
        val wallHeight = doc.app.getThreeDDocuments().firstOrNull()?.model?.getBounds()?.let { (it.second.z - it.first.z) } ?: 300.0
        
        val x1 = doc.modelToScreen(doc.wallStart, doc.offsetX)
        val x2 = doc.modelToScreen(doc.wallEnd, doc.offsetX)
        val z1 = doc.modelToScreen(0.0, doc.offsetY, true)
        val z2 = doc.modelToScreen(wallHeight, doc.offsetY, true)
        
        val rx = minOf(x1, x2)
        val rz = minOf(z1, z2)
        val rw = abs(x1 - x2)
        val rh = abs(z1 - z2)
        
        g2.color = Color.LIGHT_GRAY
        g2.fillRect(rx, rz, rw, rh)

        drawOpenings(g2, wallHeight)
        drawPerpendicularWalls(g2, wallHeight)

        g2.color = Color.GRAY
        g2.drawRect(rx, rz, rw, rh)
    }

    private fun drawOpenings(g2: Graphics2D, wallHeight: Double) {
        val isVertical = doc.isVertical
        val openings = doc.floorPlanDoc.elements.filter { it is PlanWindow || it is Door }
        
        for (op in openings) {
            val opWall = doc.floorPlanDoc.findContainingWall(op.x, op.y, op.width, op.height)
            if (opWall === doc.wall) {
                val absPos = if (isVertical) op.y else op.x
                val opWidth = if (isVertical) op.height else op.width
                val opHeight3D = if (op is PlanWindow) op.height3D else (op as Door).verticalHeight
                val sillElevation = if (op is PlanWindow) op.sillElevation else 0
                
                val x1 = doc.modelToScreen(absPos.toDouble(), doc.offsetX)
                val x2 = doc.modelToScreen((absPos + opWidth).toDouble(), doc.offsetX)
                val topZ = (sillElevation + opHeight3D).toDouble()
                val z1 = doc.modelToScreen(sillElevation.toDouble(), doc.offsetY, true)
                val z2 = doc.modelToScreen(topZ, doc.offsetY, true)

                val rx = minOf(x1, x2)
                val rz = minOf(z1, z2)
                val rw = abs(x1 - x2)
                val rh = abs(z1 - z2)
                
                g2.color = Color.WHITE
                g2.fillRect(rx, rz, rw, rh)
                g2.color = Color.GRAY
                g2.drawRect(rx, rz, rw, rh)
            }
        }
    }

    private fun drawPerpendicularWalls(g2: Graphics2D, wallHeight: Double) {
        val isVertical = doc.isVertical
        val otherWalls = doc.floorPlanDoc.elements.filterIsInstance<Wall>().filter { it !== doc.wall }
        
        val wallBounds = doc.wall.getBounds()
        
        for (other in otherWalls) {
            val otherBounds = other.getBounds()
            // Check if other wall is perpendicular and docks to the edited side
            val otherIsVertical = other.width < other.height
            if (isVertical != otherIsVertical) {
                // Check for docking
                if (isVertical) {
                    // Current wall is vertical (along Y)
                    // Perpendicular wall is horizontal (along X)
                    if (otherBounds.y < wallBounds.y + wallBounds.height && otherBounds.y + otherBounds.height > wallBounds.y) {
                        // There is Y overlap
                        
                        val docksToEditedSide = if (doc.isFront) {
                            // Assume front is left (smaller X)
                            otherBounds.x + otherBounds.width == wallBounds.x
                        } else {
                            // Assume back is right (larger X)
                            otherBounds.x == wallBounds.x + wallBounds.width
                        }
                        
                        if (docksToEditedSide) {
                            val overlapStart = maxOf(wallBounds.y, otherBounds.y)
                            val overlapEnd = minOf(wallBounds.y + wallBounds.height, otherBounds.y + otherBounds.height)
                            val absPos = (overlapStart + overlapEnd) / 2.0
                            val thickness = (overlapEnd - overlapStart).toDouble()
                            drawPerpWallSection(g2, absPos, wallHeight, thickness)
                        }
                    }
                } else {
                    // Current wall is horizontal (along X)
                    // Perpendicular wall is vertical (along Y)
                    if (otherBounds.x < wallBounds.x + wallBounds.width && otherBounds.x + otherBounds.width > wallBounds.x) {
                        // There is X overlap
                        val docksToEditedSide = if (doc.isFront) {
                            // Assume front is top (smaller Y)
                            otherBounds.y + otherBounds.height == wallBounds.y
                        } else {
                            // Assume back is bottom (larger Y)
                            otherBounds.y == wallBounds.y + wallBounds.height
                        }

                        if (docksToEditedSide) {
                            val overlapStart = maxOf(wallBounds.x, otherBounds.x)
                            val overlapEnd = minOf(wallBounds.x + wallBounds.width, otherBounds.x + otherBounds.width)
                            val absPos = (overlapStart + overlapEnd) / 2.0
                            val thickness = (overlapEnd - overlapStart).toDouble()
                            drawPerpWallSection(g2, absPos, wallHeight, thickness)
                        }
                    }
                }
            }
        }
    }

    private fun drawPerpWallSection(g2: Graphics2D, absPos: Double, wallHeight: Double, thickness: Double) {
        val x1 = doc.modelToScreen(absPos - thickness / 2.0, doc.offsetX)
        val x2 = doc.modelToScreen(absPos + thickness / 2.0, doc.offsetX)
        val z1 = doc.modelToScreen(0.0, doc.offsetY, true)
        val z2 = doc.modelToScreen(wallHeight, doc.offsetY, true)
        
        val rx = minOf(x1, x2)
        val rz = minOf(z1, z2)
        val rw = abs(x1 - x2)
        val rh = abs(z1 - z2)
        
        g2.color = Color.GRAY
        g2.fillRect(rx, rz, rw, rh)
        g2.color = Color.DARK_GRAY
        g2.drawRect(rx, rz, rw, rh)
    }

    private fun drawRuler(g2: Graphics2D) {
        val wallWidth = (doc.wallEnd - doc.wallStart)
        val xStart = doc.modelToScreen(doc.wallStart, doc.offsetX)
        val xEnd = doc.modelToScreen(doc.wallEnd, doc.offsetX)
        val y = doc.modelToScreen(-10.0, doc.offsetY, true) // Ruler below floor (Z=-10)
        
        g2.color = Color.BLACK
        g2.drawLine(xStart, y, xEnd, y)
        g2.drawLine(xStart, y - 5, xStart, y + 5)
        g2.drawLine(xEnd, y - 5, xEnd, y + 5)
        
        val label = "${wallWidth.roundToInt()} cm"
        val fm = g2.fontMetrics
        g2.drawString(label, (xStart + xEnd) / 2 - fm.stringWidth(label) / 2, y + 20)
    }

    private fun drawPoints(g2: Graphics2D) {
        for (p in doc.layout.points) {
            val sx = doc.modelToScreen(p.x, doc.offsetX)
            val sy = doc.modelToScreen(p.z.toDouble(), doc.offsetY, true)
            
            val kind = doc.floorPlanDoc.kinds.getOrNull(p.kind)
            g2.color = kind?.color ?: Color.BLACK
            
            g2.fillOval(sx - pointRadius, sy - pointRadius, pointRadius * 2, pointRadius * 2)
            
            if (p === doc.selectedPoint) {
                g2.color = Color.BLACK
                g2.stroke = BasicStroke(2f)
                g2.drawOval(sx - pointRadius, sy - pointRadius, pointRadius * 2, pointRadius * 2)
                g2.stroke = BasicStroke(1f)
            }
        }

        // Draw docked points
        val dockedPoints = getDockedPoints()
        g2.font = Font("SansSerif", Font.PLAIN, 10)
        for (p in dockedPoints) {
            val sx = doc.modelToScreen(p.x, doc.offsetX)
            val sy = doc.modelToScreen(p.z.toDouble(), doc.offsetY, true)

            val kind = doc.floorPlanDoc.kinds.getOrNull(p.kind)
            val baseColor = kind?.color ?: Color.BLACK
            // Use semi-transparent color for docked points
            g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, 128)
            
            g2.fillOval(sx - pointRadius, sy - pointRadius, pointRadius * 2, pointRadius * 2)
            
            // Draw label "docked"
            g2.color = Color.GRAY
            val label = "docked"
            val fm = g2.fontMetrics
            g2.drawString(label, sx - fm.stringWidth(label) / 2, sy - pointRadius - 5)
        }
    }
}
