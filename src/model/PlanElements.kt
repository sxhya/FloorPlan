package model

import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.io.Serializable

data class WallLayoutKind(var name: String, var color: Color, var diameter: Double = 1.0) : Serializable

data class WallLayoutPoint(var x: Double, var z: Int, var kind: Int, var name: String = "") : Serializable

class WallLayout : Serializable {
    val points = mutableListOf<WallLayoutPoint>()
    var isAbsolute: Boolean = false
}

enum class ElementType {
    WALL, ROOM, WINDOW, DOOR, STAIRS, POLYGON_ROOM, UTILITIES_CONNECTION
}

abstract class PlanElement(
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    val type: ElementType
) : Serializable {
    fun getBounds(): Rectangle = Rectangle(x, y, width, height)

    open fun contains(px: Int, py: Int): Boolean {
        return getBounds().contains(px.toDouble(), py.toDouble())
    }

    open fun getArea(): Double = width.toDouble() * height.toDouble()
}

class Wall(x: Int, y: Int, width: Int, height: Int) : PlanElement(x, y, width, height, ElementType.WALL) {
    val frontLayout = WallLayout()
    val backLayout = WallLayout()

    fun getFloorPlanCoords(p: WallLayoutPoint, isFront: Boolean): Point2D.Double {
        val isVertical = width < height
        return if (isVertical) {
            val fx = if (isFront) x.toDouble() else (x + width).toDouble()
            Point2D.Double(fx, p.x)
        } else {
            val fy = if (isFront) y.toDouble() else (y + height).toDouble()
            Point2D.Double(p.x, fy)
        }
    }
}
class Room(x: Int, y: Int, width: Int, height: Int, var floorThickness: Int = 15, var zOffset: Int = 0) : PlanElement(x, y, width, height, ElementType.ROOM)
class Window(x: Int, y: Int, width: Int, height: Int, var height3D: Int = 150, var sillElevation: Int = 90, var windowPosition: WindowPosition = WindowPosition.XY) : PlanElement(x, y, width, height, ElementType.WINDOW)
class Door(x: Int, y: Int, width: Int, height: Int, var verticalHeight: Int = 200) : PlanElement(x, y, width, height, ElementType.DOOR)
class Stairs(x: Int, y: Int, width: Int, height: Int, var directionAlongX: Boolean = true, var totalRaise: Int = 0, var zOffset: Int = 0) : PlanElement(x, y, width, height, ElementType.STAIRS)

class UtilitiesConnection(
    var startPoint: WallLayoutPoint,
    var startWall: Wall,
    var startIsFront: Boolean,
    var endPoint: WallLayoutPoint,
    var endWall: Wall,
    var endIsFront: Boolean,
    val kind: Int
) : PlanElement(0, 0, 0, 0, ElementType.UTILITIES_CONNECTION) {

    init {
        updateBounds()
    }

    fun updateBounds() {
        val p1 = startWall.getFloorPlanCoords(startPoint, startIsFront)
        val p2 = endWall.getFloorPlanCoords(endPoint, endIsFront)

        val minX = Math.min(p1.x, p2.x).toInt()
        val minY = Math.min(p1.y, p2.y).toInt()
        val maxX = Math.max(p1.x, p2.x).toInt()
        val maxY = Math.max(p1.y, p2.y).toInt()

        x = minX
        y = minY
        width = Math.max(1, maxX - minX)
        height = Math.max(1, maxY - minY)
    }

    override fun contains(px: Int, py: Int): Boolean {
        val p1 = startWall.getFloorPlanCoords(startPoint, startIsFront)
        val p2 = endWall.getFloorPlanCoords(endPoint, endIsFront)

        val dist = Line2D.ptSegDist(p1.x, p1.y, p2.x, p2.y, px.toDouble(), py.toDouble())
        return dist < 10.0
    }
}

class PolygonRoom(val vertices: MutableList<Point>, var floorThickness: Int = 15, var zOffset: Int = 0) : PlanElement(0, 0, 0, 0, ElementType.POLYGON_ROOM) {
    init {
        updateBounds()
    }

    fun updateBounds() {
        if (vertices.isEmpty()) return
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (v in vertices) {
            minX = minOf(minX, v.x)
            minY = minOf(minY, v.y)
            maxX = maxOf(maxX, v.x)
            maxY = maxOf(maxY, v.y)
        }
        x = minX
        y = minY
        width = maxX - minX
        height = maxY - minY
    }

    override fun getArea(): Double {
        if (vertices.size < 3) return 0.0
        var area = 0.0
        for (i in vertices.indices) {
            val j = (i + 1) % vertices.size
            area += vertices[i].x.toDouble() * vertices[j].y.toDouble()
            area -= vertices[j].x.toDouble() * vertices[i].y.toDouble()
        }
        return Math.abs(area) / 2.0
    }

    override fun contains(px: Int, py: Int): Boolean {
        var intersectCount = 0
        for (i in vertices.indices) {
            val v1 = vertices[i]
            val v2 = vertices[(i + 1) % vertices.size]
            if (((v1.y > py) != (v2.y > py)) &&
                (px < (v2.x - v1.x) * (py - v1.y) / (v2.y - v1.y) + v1.x)
            ) {
                intersectCount++
            }
        }
        return intersectCount % 2 != 0
    }
}
