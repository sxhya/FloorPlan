package ui

import model.Model3D
import java.io.File
import ui.components.ThreeDPanel

class ThreeDDocument(val app: FloorPlanApp) {
    var window: ThreeDWindow? = null
    var model = Model3D()
    var currentFile: File? = null
    
    var isModified = false
    
    // View state
    var rotationX = 30.0 // tilt (0 side, 90 top)
    var rotationZ = 45.0 // turn around Z
    var scale = 1.0
    var showHighlighting = true
    
    val panel = ThreeDPanel(this)
}
