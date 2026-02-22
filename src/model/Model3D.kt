package model

import java.io.Serializable

data class Vector3D(val x: Double, val y: Double, val z: Double) : Serializable

data class Rect3D(
    val v1: Vector3D,
    val v2: Vector3D,
    val v3: Vector3D,
    val v4: Vector3D,
    val color: java.awt.Color,
    val isWindow: Boolean = false,
    val isStairsVisualOnly: Boolean = false  // For stairs slabs - draw only, ignore in collision
) : Serializable

data class Triangle3D(
    val v1: Vector3D,
    val v2: Vector3D,
    val v3: Vector3D,
    val color: java.awt.Color,
    val isStairsVisualOnly: Boolean = false  // For stairs sides - draw only, ignore in collision
) : Serializable

// Door opening information for walk mode collision detection
data class DoorInfo(
    val x1: Double,      // Min X bound
    val y1: Double,      // Min Y bound
    val x2: Double,      // Max X bound
    val y2: Double,      // Max Y bound
    val z1: Double,      // Bottom of door opening (usually floor level)
    val z2: Double       // Top of door opening (door height)
) : Serializable {
    // Check if a point is within the door opening
    fun isInOpening(x: Double, y: Double, z: Double, radius: Double): Boolean {
        // Check if player center is within door bounds (with some tolerance for radius)
        val inXY = x >= x1 - radius && x <= x2 + radius &&
                   y >= y1 - radius && y <= y2 + radius
        // Check if player's feet to head range is within door height
        val inZ = z >= z1 && z <= z2
        return inXY && inZ
    }
}

// Stair information for walk mode height calculation
data class StairInfo(
    val x1: Double,      // Min X bound
    val y1: Double,      // Min Y bound
    val x2: Double,      // Max X bound
    val y2: Double,      // Max Y bound
    val baseZ: Double,   // Z at the start of stairs (at x1,y1 corner)
    val totalRaise: Double,  // Total height change
    val isAlongX: Boolean    // Direction: true = along X axis, false = along Y axis
) : Serializable {
    // Calculate floor Z at a given position on the stairs
    fun getFloorZ(x: Double, y: Double): Double {
        if (x < x1 || x > x2 || y < y1 || y > y2) return Double.MIN_VALUE  // Not on stairs
        
        val progress = if (isAlongX) {
            (x - x1) / (x2 - x1)
        } else {
            (y - y1) / (y2 - y1)
        }
        
        // If totalRaise is negative, stairs go down as we progress
        return baseZ + progress * totalRaise
    }
}

data class Cylinder3D(
    val start: Vector3D,
    val end: Vector3D,
    val radius: Double,
    val color: java.awt.Color
) : Serializable

data class Label3D(
    val position: Vector3D,
    val text: String,
    val color: java.awt.Color
) : Serializable

class Model3D : Serializable {
    val rects = mutableListOf<Rect3D>()
    val triangles = mutableListOf<Triangle3D>()
    val cylinders = mutableListOf<Cylinder3D>()
    val labels = mutableListOf<Label3D>()
    val lightPositions = mutableListOf<Vector3D>()
    val stairInfos = mutableListOf<StairInfo>()
    val doorInfos = mutableListOf<DoorInfo>()

    fun getBounds(): Pair<Vector3D, Vector3D> {
        if (rects.isEmpty() && triangles.isEmpty()) return Vector3D(0.0, 0.0, 0.0) to Vector3D(0.0, 0.0, 0.0)
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var maxZ = -Double.MAX_VALUE

        for (rect in rects) {
            for (v in listOf(rect.v1, rect.v2, rect.v3, rect.v4)) {
                minX = minOf(minX, v.x)
                minY = minOf(minY, v.y)
                minZ = minOf(minZ, v.z)
                maxX = maxOf(maxX, v.x)
                maxY = maxOf(maxY, v.y)
                maxZ = maxOf(maxZ, v.z)
            }
        }
        for (tri in triangles) {
            for (v in listOf(tri.v1, tri.v2, tri.v3)) {
                minX = minOf(minX, v.x)
                minY = minOf(minY, v.y)
                minZ = minOf(minZ, v.z)
                maxX = maxOf(maxX, v.x)
                maxY = maxOf(maxY, v.y)
                maxZ = maxOf(maxZ, v.z)
            }
        }
        return Vector3D(minX, minY, minZ) to Vector3D(maxX, maxY, maxZ)
    }

    fun getCenter(): Vector3D {
        val (min, max) = getBounds()
        return Vector3D((min.x + max.x) / 2.0, (min.y + max.y) / 2.0, (min.z + max.z) / 2.0)
    }
}
