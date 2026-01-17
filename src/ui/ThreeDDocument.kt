package ui

import model.Model3D
import model.Vector3D
import java.io.File
import ui.components.ThreeDPanel

enum class CameraMode {
    ORBIT,  // View from outside, rotate around the model
    WALK    // First-person walking mode inside the house
}

class ThreeDDocument(val app: FloorPlanApp) {
    var window: ThreeDWindow? = null
    var model = Model3D()
    var currentFile: File? = null
    
    var isModified = false
    
    // Original data for 3D model XML
    data class FloorData(val filePath: String, var height: Int, var draw: Boolean = true)
    val floors = mutableListOf<FloorData>()
    
    // View state - Orbit mode
    var rotationX = 30.0 // tilt (0 top, 90 side)
    var rotationZ = 45.0 // turn around Z
    var scale = 1.0
    
    // Camera mode
    var cameraMode = CameraMode.ORBIT
    
    // Walk mode state - player position in model coordinates (cm)
    var playerX = 0.0
    var playerY = 0.0
    var playerZ = 100.0  // Eye height ~100cm (1 meter) above floor
    
    // Walk mode - look direction (degrees)
    var playerYaw = 0.0   // Horizontal rotation (0 = looking along +Y axis)
    var playerPitch = 0.0 // Vertical rotation (-90 to +90, 0 = horizontal)
    
    // Player constants
    val playerHeight = 170.0  // Player eye height in cm
    val playerRadius = 15.0   // Collision radius in cm (small to fit through door openings)
    val moveSpeed = 10.0      // Movement speed in cm per frame
    
    // Physics - gravity and jumping
    var verticalVelocity = 0.0  // Current vertical velocity in cm per frame
    val gravity = -2.0          // Gravity acceleration in cm per frame^2 (negative = downward)
    val jumpImpulse = 30.0      // Initial upward velocity when jumping in cm per frame
    var isOnGround = true       // Whether player is standing on ground
    
    val panel = ThreeDPanel(this)
    
    // Initialize player position at the center of a room
    fun initializePlayerPosition() {
        // Use light positions which are placed at room centers
        // This ensures the player starts inside a room, not in a wall
        if (model.lightPositions.isNotEmpty()) {
            val roomCenter = model.lightPositions.first()
            playerX = roomCenter.x
            playerY = roomCenter.y
            // Light Z is at center height of room, adjust to floor level + eye height
            val bounds = model.getBounds()
            playerZ = bounds.first.z + playerHeight  // Start at eye height above lowest floor
        } else {
            // Fallback to model center if no light positions
            val bounds = model.getBounds()
            val min = bounds.first
            val max = bounds.second
            playerX = (min.x + max.x) / 2.0
            playerY = (min.y + max.y) / 2.0
            playerZ = min.z + playerHeight
        }
        playerYaw = 0.0
        playerPitch = 0.0
    }
}
