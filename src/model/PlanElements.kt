package model

import java.awt.Point
import java.awt.Rectangle
import java.io.Serializable

enum class ElementType {
    WALL, ROOM, WINDOW, DOOR, STAIRS, FLOOR_OPENING
}

abstract class PlanElement(
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    val type: ElementType
) : Serializable {
    fun getBounds(): Rectangle = Rectangle(x, y, width, height)
    
    fun setBounds(newX: Int, newY: Int, newWidth: Int, newHeight: Int) {
        x = newX
        y = newY
        width = newWidth
        height = newHeight
    }

    open fun contains(px: Int, py: Int): Boolean {
        return getBounds().contains(px.toDouble(), py.toDouble())
    }

    open fun getArea(): Double = width.toDouble() * height.toDouble()
}

class Wall(x: Int, y: Int, width: Int, height: Int) : PlanElement(x, y, width, height, ElementType.WALL)
class Room(x: Int, y: Int, width: Int, height: Int) : PlanElement(x, y, width, height, ElementType.ROOM)
class Window(x: Int, y: Int, width: Int, height: Int, var height3D: Int = 150) : PlanElement(x, y, width, height, ElementType.WINDOW)
class Door(x: Int, y: Int, width: Int, height: Int, var height3D: Int = 200) : PlanElement(x, y, width, height, ElementType.DOOR)
class Stairs(x: Int, y: Int, width: Int, height: Int) : PlanElement(x, y, width, height, ElementType.STAIRS)

class FloorOpening(val vertices: MutableList<Point>) : PlanElement(0, 0, 0, 0, ElementType.FLOOR_OPENING) {
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
