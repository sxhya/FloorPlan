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
                // Cancel any open side-panel spinner edit before hit-testing.
                // terminateEditOnFocusLost is disabled for wall-layout mode, so the
                // spinner does NOT auto-commit when focus moves to this window.
                // Cancelling here keeps the model unchanged so the hit test matches
                // what the user sees on screen.
                doc.app.sidePanel.cancelCurrentEdit()

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
                if (dragPoint != null) doc.app.clearOtherSelections(exceptWallLayout = doc)
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

                // Round to nearest whole centimeter
                dp.x = newX.roundToInt().toDouble()
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

    private fun getDockedPoints(): List<WallLayoutPoint> =
        computeDockedPoints(doc.wall, doc.isFront,
            doc.floorPlanDoc.elements.filterIsInstance<Wall>())

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

        val wallHeight = doc.app.getThreeDDocuments().firstOrNull()
            ?.model?.getBounds()?.let { (it.second.z - it.first.z) } ?: 300.0

        val openings = buildOpeningsForWall(doc.wall, doc.isVertical,
            doc.floorPlanDoc.elements.filter { it is PlanWindow || it is Door }
                .filter { doc.floorPlanDoc.findContainingWall(it.x, it.y, it.width, it.height) === doc.wall })

        for ((kindIdx, points) in pointsByKind) {
            if (points.size < 2) continue
            val kind = doc.floorPlanDoc.kinds.getOrNull(kindIdx)
            g2.color = kind?.color ?: Color.BLACK
            val edges = computeManhattanMST(points)
            for ((p1, p2) in edges) {
                val waypoints = computeOrthogonalPath(
                    p1, p2, openings, doc.wallStart, doc.wallEnd, wallHeight)
                for (i in 0 until waypoints.size - 1) {
                    val (lx1, lz1) = waypoints[i]
                    val (lx2, lz2) = waypoints[i + 1]
                    g2.drawLine(
                        doc.modelToScreen(lx1, doc.offsetX),
                        doc.modelToScreen(lz1, doc.offsetY, true),
                        doc.modelToScreen(lx2, doc.offsetX),
                        doc.modelToScreen(lz2, doc.offsetY, true)
                    )
                }
            }
        }
    }

    companion object {
        /** Convert a list of PlanWindow/Door elements on a wall face into obstacle rectangles
         *  in wall-layout space: x = along-wall coord, y = height (z in model), width, height. */
        fun buildOpeningsForWall(
            wall: Wall,
            isVertical: Boolean,
            openingElements: List<PlanElement>
        ): List<Rectangle2D.Double> = openingElements.map { op ->
            val absPos = if (isVertical) op.y else op.x
            val opWidth = if (isVertical) op.height else op.width
            val opHeight3D = if (op is PlanWindow) op.height3D else (op as Door).verticalHeight
            val sillElev = if (op is PlanWindow) op.sillElevation else 0
            Rectangle2D.Double(absPos.toDouble(), sillElev.toDouble(),
                opWidth.toDouble(), opHeight3D.toDouble())
        }

        /** Points from adjacent walls whose floor-plan position falls on this wall's face. */
        fun computeDockedPoints(
            wall: Wall,
            isFront: Boolean,
            allWalls: List<Wall>
        ): List<WallLayoutPoint> {
            val isVertical = wall.width < wall.height
            val wallSideX = if (isVertical) (if (isFront) wall.x else wall.x + wall.width).toDouble() else 0.0
            val wallSideY = if (!isVertical) (if (isFront) wall.y else wall.y + wall.height).toDouble() else 0.0
            val wallStart = if (isVertical) wall.y.toDouble() else wall.x.toDouble()
            val wallEnd   = if (isVertical) (wall.y + wall.height).toDouble() else (wall.x + wall.width).toDouble()
            val layout = if (isFront) wall.frontLayout else wall.backLayout

            val result = mutableListOf<WallLayoutPoint>()
            for (other in allWalls.filter { it !== wall }) {
                for ((otherIsFront, otherLayout) in listOf(true to other.frontLayout, false to other.backLayout)) {
                    for (p in otherLayout.points) {
                        val fp = other.getFloorPlanCoords(p, otherIsFront)
                        val onSegment = if (isVertical) {
                            abs(fp.x - wallSideX) < 1e-6 && fp.y >= wallStart - 1e-6 && fp.y <= wallEnd + 1e-6
                        } else {
                            abs(fp.y - wallSideY) < 1e-6 && fp.x >= wallStart - 1e-6 && fp.x <= wallEnd + 1e-6
                        }
                        if (onSegment) {
                            val layoutX = if (isVertical) fp.y else fp.x
                            val duplicate = layout.points.any {
                                abs(it.x - layoutX) < 1e-6 && it.z == p.z && it.kind == p.kind
                            }
                            if (!duplicate) result.add(WallLayoutPoint(layoutX, p.z, p.kind))
                        }
                    }
                }
            }
            return result
        }

        /** Prim's MST over wall-layout points using Manhattan distance. */
        fun computeManhattanMST(
            points: List<WallLayoutPoint>
        ): List<Pair<WallLayoutPoint, WallLayoutPoint>> {
            if (points.size < 2) return emptyList()
            val edges = mutableListOf<Pair<WallLayoutPoint, WallLayoutPoint>>()
            val visited = mutableSetOf<WallLayoutPoint>()
            val unvisited = points.toMutableSet()
            visited.add(points[0])
            unvisited.remove(points[0])
            while (unvisited.isNotEmpty()) {
                var minDist = Double.MAX_VALUE
                var best: Pair<WallLayoutPoint, WallLayoutPoint>? = null
                for (v in visited) {
                    for (u in unvisited) {
                        val d = abs(v.x - u.x) + abs(v.z - u.z)
                        if (d < minDist) { minDist = d; best = v to u }
                    }
                }
                if (best != null) {
                    edges.add(best)
                    visited.add(best.second)
                    unvisited.remove(best.second)
                }
            }
            return edges
        }

        /**
         * A* on the Hanan grid between p1 and p2 avoiding openings.
         * Returns an ordered list of (layoutX, layoutZ) waypoints from p1 to p2.
         * openings: obstacles in layout space (x=along-wall, y=height).
         */
        fun computeOrthogonalPath(
            p1: WallLayoutPoint,
            p2: WallLayoutPoint,
            openings: List<Rectangle2D.Double>,
            wallStart: Double,
            wallEnd: Double,
            wallHeight: Double
        ): List<Pair<Double, Double>> {
            val xCoords = mutableSetOf(wallStart, wallEnd, p1.x, p2.x)
            val zCoords = mutableSetOf(0.0, wallHeight, p1.z.toDouble(), p2.z.toDouble())
            for (op in openings) {
                xCoords += op.x; xCoords += op.x + op.width
                zCoords += op.y; zCoords += op.y + op.height
            }
            val sortedX = xCoords.filter { it in wallStart..wallEnd }.sorted()
            val sortedZ = zCoords.filter { it in 0.0..wallHeight }.sorted()

            val si = sortedX.indexOf(p1.x); val sj = sortedZ.indexOf(p1.z.toDouble())
            val ti = sortedX.indexOf(p2.x); val tj = sortedZ.indexOf(p2.z.toDouble())
            if (si < 0 || sj < 0 || ti < 0 || tj < 0) return fallbackPath(p1, p2)

            data class PNode(val state: Triple<Int,Int,Int>, val f: Double, val g: Double)
            val open = PriorityQueue<PNode>(compareBy { it.f })
            val gScore = HashMap<Triple<Int,Int,Int>, Double>().withDefault { Double.MAX_VALUE }
            val cameFrom = HashMap<Triple<Int,Int,Int>, Triple<Int,Int,Int>>()
            fun heur(xi: Int, zi: Int) =
                abs(sortedX[xi] - sortedX[ti]) + abs(sortedZ[zi] - sortedZ[tj])

            val startNode = Triple(si, sj, 0)
            gScore[startNode] = 0.0
            open.add(PNode(startNode, heur(si, sj), 0.0))
            val turnPenalty = 5.0
            var finalNode: Triple<Int,Int,Int>? = null

            while (open.isNotEmpty()) {
                val (cur) = open.poll()
                val (cx, cz, cDir) = cur
                if (cx == ti && cz == tj) { finalNode = cur; break }
                val neighbors = mutableListOf<Pair<Int,Int>>()
                if (cx > 0)             neighbors += (cx - 1) to cz
                if (cx < sortedX.size - 1) neighbors += (cx + 1) to cz
                if (cz > 0)             neighbors += cx to (cz - 1)
                if (cz < sortedZ.size - 1) neighbors += cx to (cz + 1)
                for ((nx, nz) in neighbors) {
                    val nDir = if (nx != cx) 1 else 2
                    if (isSegmentBlocked(cx, cz, nx, nz, sortedX, sortedZ, openings)) continue
                    val moveDist = abs(sortedX[cx] - sortedX[nx]) + abs(sortedZ[cz] - sortedZ[nz])
                    val cost = if (cDir != 0 && cDir != nDir) turnPenalty else 0.0
                    val tentG = gScore.getValue(cur) + moveDist + cost
                    val next = Triple(nx, nz, nDir)
                    if (tentG < gScore.getValue(next)) {
                        cameFrom[next] = cur
                        gScore[next] = tentG
                        open.add(PNode(next, tentG + heur(nx, nz), tentG))
                    }
                }
            }

            if (finalNode == null) return fallbackPath(p1, p2)

            val waypoints = mutableListOf<Pair<Double, Double>>()
            var cur: Triple<Int, Int, Int> = finalNode!!
            while (cameFrom.containsKey(cur)) {
                waypoints.add(sortedX[cur.first] to sortedZ[cur.second])
                cur = cameFrom[cur]!!
            }
            waypoints.add(sortedX[cur.first] to sortedZ[cur.second])
            waypoints.reverse()
            return waypoints
        }

        private fun isSegmentBlocked(
            cx: Int, cz: Int, nx: Int, nz: Int,
            x: List<Double>, z: List<Double>,
            openings: List<Rectangle2D.Double>
        ): Boolean {
            val eps = 0.1
            for (op in openings) {
                if (cz == nz) {
                    val zCoord = z[cz]
                    if (zCoord > op.y + eps && zCoord < op.y + op.height - eps) {
                        val minX = minOf(x[cx], x[nx]); val maxX = maxOf(x[cx], x[nx])
                        if (minX < op.x + op.width - eps && maxX > op.x + eps) return true
                    }
                } else {
                    val xCoord = x[cx]
                    if (xCoord > op.x + eps && xCoord < op.x + op.width - eps) {
                        val minZ = minOf(z[cz], z[nz]); val maxZ = maxOf(z[cz], z[nz])
                        if (minZ < op.y + op.height - eps && maxZ > op.y + eps) return true
                    }
                }
            }
            return false
        }

        private fun fallbackPath(p1: WallLayoutPoint, p2: WallLayoutPoint): List<Pair<Double, Double>> =
            listOf(p1.x to p1.z.toDouble(), p2.x to p1.z.toDouble(), p2.x to p2.z.toDouble())
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
        val perpEdges = getPerpendicularWallEdges()
        val threshold = 1.0 // 1cm tolerance for docking detection
        g2.font = Font("SansSerif", Font.BOLD, 10)

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

            // Show "D" badge if point is snapped to a perpendicular wall edge
            val isDocked = perpEdges.any { abs(it - p.x) < threshold }
            if (isDocked) {
                drawDockedBadge(g2, sx, sy - pointRadius - 2)
            }

            // Draw name label below the point
            if (p.name.isNotEmpty()) {
                val nameColor = (kind?.color ?: Color.BLACK).darker()
                g2.color = nameColor
                val fm = g2.fontMetrics
                g2.drawString(p.name, sx - fm.stringWidth(p.name) / 2, sy + pointRadius + fm.ascent)
            }
        }

        // Draw docked ghost points (from adjacent walls)
        for (p in getDockedPoints()) {
            val sx = doc.modelToScreen(p.x, doc.offsetX)
            val sy = doc.modelToScreen(p.z.toDouble(), doc.offsetY, true)

            val kind = doc.floorPlanDoc.kinds.getOrNull(p.kind)
            val baseColor = kind?.color ?: Color.BLACK
            // Use semi-transparent color for docked ghost points
            g2.color = Color(baseColor.red, baseColor.green, baseColor.blue, 128)

            g2.fillOval(sx - pointRadius, sy - pointRadius, pointRadius * 2, pointRadius * 2)

            // Draw "D" badge above ghost point
            drawDockedBadge(g2, sx, sy - pointRadius - 2)
        }
    }

    private fun drawDockedBadge(g2: Graphics2D, cx: Int, bottomY: Int) {
        val fm = g2.fontMetrics
        val label = "D"
        val lw = fm.stringWidth(label)
        val lh = fm.ascent
        val pad = 2
        val bx = cx - lw / 2 - pad
        val by = bottomY - lh - pad
        val bw = lw + pad * 2
        val bh = lh + pad * 2
        // Background
        g2.color = Color(80, 80, 80, 200)
        g2.fillRoundRect(bx, by, bw, bh, 3, 3)
        // Text
        g2.color = Color.WHITE
        g2.drawString(label, cx - lw / 2, bottomY - pad)
    }
}
