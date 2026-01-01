package ui.components

import model.Rect3D
import model.Vector3D
import ui.ThreeDDocument
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import kotlin.math.*

class ThreeDPanel(private val doc: ThreeDDocument) : JPanel() {
    private var lastMouseX = 0
    private var lastMouseY = 0
    private var isDragging = false
    private var dragMode = DragMode.ROTATE
    
    enum class DragMode { ROTATE }

    fun setDragMode(mode: DragMode) {
        dragMode = mode
    }

    init {
        background = Color.BLACK
        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                lastMouseX = e.x
                lastMouseY = e.y
                isDragging = true
            }

            override fun mouseReleased(e: MouseEvent) {
                isDragging = false
            }

            override fun mouseDragged(e: MouseEvent) {
                val dx = e.x - lastMouseX
                val dy = e.y - lastMouseY

                when (dragMode) {
                    DragMode.ROTATE -> {
                        doc.rotationZ -= dx * 0.5
                        doc.rotationX = (doc.rotationX + dy * 0.5).coerceIn(0.0, 90.0)
                    }
                }

                lastMouseX = e.x
                lastMouseY = e.y
                repaint()
            }
        }
        addMouseListener(mouseAdapter)
        addMouseMotionListener(mouseAdapter)
        addMouseWheelListener { e ->
            val rotation = e.preciseWheelRotation
            val factor = 1.1.pow(-rotation)
            doc.scale *= factor
            repaint()
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val w = width
        val h = height
        val centerX = w / 2.0
        val centerY = h / 2.0

        val radX = Math.toRadians(doc.rotationX)
        val radZ = Math.toRadians(doc.rotationZ)
        
        val modelCenter = doc.model.getCenter()
        val rectsToDraw = if (doc.showHighlighting) doc.model.rects else doc.model.rects.filter { !it.isHighlight }

        val projectedRects = rectsToDraw.map { rect ->
            val p1 = project(rect.v1, modelCenter, radX, radZ, doc.scale, centerX, centerY)
            val p2 = project(rect.v2, modelCenter, radX, radZ, doc.scale, centerX, centerY)
            val p3 = project(rect.v3, modelCenter, radX, radZ, doc.scale, centerX, centerY)
            val p4 = project(rect.v4, modelCenter, radX, radZ, doc.scale, centerX, centerY)
            
            // For simple Z-sorting, calculate average Z after rotation
            val rotatedV1 = rotate(rect.v1, modelCenter, radX, radZ)
            val rotatedV2 = rotate(rect.v2, modelCenter, radX, radZ)
            val rotatedV3 = rotate(rect.v3, modelCenter, radX, radZ)
            val rotatedV4 = rotate(rect.v4, modelCenter, radX, radZ)
            val avgZ = (rotatedV1.z + rotatedV2.z + rotatedV3.z + rotatedV4.z) / 4.0
            
            ProjectedRect(p1, p2, p3, p4, rect.color, avgZ)
        }.sortedByDescending { it.avgZ }

        for (pr in projectedRects) {
            val poly = Polygon()
            poly.addPoint(pr.p1.x.toInt(), pr.p1.y.toInt())
            poly.addPoint(pr.p2.x.toInt(), pr.p2.y.toInt())
            poly.addPoint(pr.p3.x.toInt(), pr.p3.y.toInt())
            poly.addPoint(pr.p4.x.toInt(), pr.p4.y.toInt())

            val alpha = 150
            val colorWithAlpha = Color(pr.color.red, pr.color.green, pr.color.blue, alpha)
            g2.color = colorWithAlpha
            g2.fillPolygon(poly)
            g2.color = pr.color
            g2.drawPolygon(poly)
        }
    }

    private data class ProjectedRect(val p1: Point2D, val p2: Point2D, val p3: Point2D, val p4: Point2D, val color: Color, val avgZ: Double)
    private data class Point2D(val x: Double, val y: Double)

    private fun rotate(v: Vector3D, center: Vector3D, radX: Double, radZ: Double): Vector3D {
        // Translate to center
        val vx = v.x - center.x
        val vy = v.y - center.y
        val vz = v.z - center.z

        // Rotate around Z (XY plane) - turning the house
        val x0 = vx * cos(radZ) - vy * sin(radZ)
        val y0 = vx * sin(radZ) + vy * cos(radZ)
        
        // Tilt rotation:
        // When radX = 0 (Side View), we want model Z to be vertical on screen.
        // When radX = PI/2 (Top View), we want model Z to be pointing towards viewer (depth).
        
        // We can achieve this by rotating around X axis by (-PI/2 - radX)
        val angle = -PI/2 - radX
        val y1 = y0 * cos(angle) - vz * sin(angle)
        val z1 = y0 * sin(angle) + vz * cos(angle)
        
        return Vector3D(x0, y1, z1)
    }

    private fun project(v: Vector3D, center: Vector3D, radX: Double, radZ: Double, scale: Double, centerX: Double, centerY: Double): Point2D {
        val rotated = rotate(v, center, radX, radZ)
        
        // Perspective projection
        val d = 1000.0 // Distance to projection plane
        val zOffset = 2000.0 // Move model back
        val pScale = d / (rotated.z + zOffset)
        
        val px = rotated.x * pScale * scale + centerX
        val py = -rotated.y * pScale * scale + centerY // Flip Y for screen coordinates
        
        return Point2D(px, py)
    }
}
