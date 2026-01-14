package ui

import model.Model3D
import model.Vector3D
import java.io.File
import ui.components.ThreeDPanel

enum class CameraMode {
    ORBIT,  // View from outside, rotate around the model
    WALK    // First-person walk mode inside the house
}

class ThreeDDocument(val app: FloorPlanApp) {
    var window: ThreeDWindow? = null
    var model = Model3D()
    var currentFile: File? = null
    
    var isModified = false
    
    // Original data for 3D model XML
    data class FloorData(val filePath: String, val height: Int)
    val floors = mutableListOf<FloorData>()
    
    // View state - Orbit mode
    var rotationX = 30.0 // tilt (0 side, 90 top)
    var rotationZ = 45.0 // turn around Z
    var scale = 1.0
    var showHighlighting = true
    
    // Camera mode
    var cameraMode = CameraMode.ORBIT
    
    // Walk mode state
    var walkX = 0.0      // X position in model coordinates
    var walkY = 0.0      // Y position in model coordinates  
    var walkZ = 170.0    // Z position (eye height, ~170cm for human eye level)
    var walkYaw = 0.0    // Horizontal look angle (degrees)
    var walkPitch = 0.0  // Vertical look angle (degrees, clamped to -89..89)
    
    // Walk physics
    var velocityZ = 0.0  // Vertical velocity for jumping
    var isOnGround = true
    val eyeHeight = 170.0    // Standing eye height in cm
    val walkSpeed = 200.0    // Movement speed in cm per second
    val jumpVelocity = 350.0 // Initial jump velocity
    val gravity = 980.0      // Gravity acceleration (cm/s^2)
    val playerRadius = 30.0  // Collision radius in cm
    
    val panel = ThreeDPanel(this)
    
    // Initialize walk position to center of model
    fun initWalkPosition() {
        val (min, max) = model.getBounds()
        walkX = (min.x + max.x) / 2.0
        walkY = (min.y + max.y) / 2.0
        walkZ = min.z + eyeHeight
        walkYaw = 0.0
        walkPitch = 0.0
        velocityZ = 0.0
        isOnGround = true
    }
}
