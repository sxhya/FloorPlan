package model

import java.awt.Rectangle

data class IntersectionInfo(val el1: PlanElement, val el2: PlanElement, val rect: Rectangle)

data class Axis(
    val x1: Double, val y1: Double,
    val x2: Double, val y2: Double,
    val label: String,
    val isHorizontal: Boolean
)
