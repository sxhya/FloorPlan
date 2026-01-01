package model

import java.io.Serializable

data class Vector3D(val x: Double, val y: Double, val z: Double) : Serializable

data class Rect3D(
    val v1: Vector3D,
    val v2: Vector3D,
    val v3: Vector3D,
    val v4: Vector3D,
    val color: java.awt.Color,
    val isHighlight: Boolean = false
) : Serializable

class Model3D : Serializable {
    val rects = mutableListOf<Rect3D>()

    fun getBounds(): Pair<Vector3D, Vector3D> {
        if (rects.isEmpty()) return Vector3D(0.0, 0.0, 0.0) to Vector3D(0.0, 0.0, 0.0)
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
        return Vector3D(minX, minY, minZ) to Vector3D(maxX, maxY, maxZ)
    }

    fun getCenter(): Vector3D {
        val (min, max) = getBounds()
        return Vector3D((min.x + max.x) / 2.0, (min.y + max.y) / 2.0, (min.z + max.z) / 2.0)
    }
}
