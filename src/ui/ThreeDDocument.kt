package ui

import model.Model3D
import model.Vector3D
import java.io.File
import ui.components.ThreeDPanel

enum class CameraMode {
    ORBIT  // View from outside, rotate around the model
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
    var rotationX = 30.0 // tilt (0 top, 90 side)
    var rotationZ = 45.0 // turn around Z
    var scale = 1.0
    
    // Camera mode
    var cameraMode = CameraMode.ORBIT
    
    val panel = ThreeDPanel(this)
}
