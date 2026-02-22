package ui.components

import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.geometry.Point3D
import javafx.scene.*
import javafx.scene.paint.Color as JFXColor
import javafx.scene.paint.PhongMaterial
import javafx.scene.shape.Box
import javafx.scene.shape.CullFace
import javafx.scene.shape.Cylinder
import javafx.scene.shape.MeshView
import javafx.scene.shape.Sphere
import javafx.scene.shape.TriangleMesh
import javafx.scene.transform.Rotate
import javafx.scene.transform.Translate
import javafx.scene.text.Font as JFXFont
import javafx.scene.text.Text as JFXText
import model.Cylinder3D
import model.Label3D
import model.Rect3D
import model.Triangle3D
import model.Vector3D
import ui.CameraMode
import ui.ThreeDDocument
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.*

class ThreeDPanel(private val doc: ThreeDDocument) : JPanel() {
    private val fxPanel = JFXPanel()
    
    // JavaFX 3D components
    private var root: Group? = null
    private var modelGroup: Group? = null
    private var windowGroup: Group? = null  // Separate group for windows with always-on lighting
    private var utilitiesGroup: Group? = null  // Separate group for utility pipe cylinders
    private var labelsGroup: Group? = null     // Separate group for point name labels
    private var roomLightsGroup: Group? = null  // Group for room lights (point lights)
    private var lightSpheresGroup: Group? = null  // Group for yellow spheres marking light positions (night mode only)
    private var floorGridGroup: Group? = null  // Group for floor grid at Z=0
    private var camera: PerspectiveCamera? = null
    private var cameraXform: Group? = null
    private var cameraXRotate: Rotate? = null
    private var cameraZRotate: Rotate? = null
    private var cameraTranslate: Translate? = null
    private var ambientLight: AmbientLight? = null
    private var windowAmbientLight: AmbientLight? = null  // Always-on light for windows
    
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    
    // Walk mode - keyboard state
    private val keysPressed = mutableSetOf<Int>()
    private var walkTimer: Timer? = null
    
    init {
        layout = java.awt.BorderLayout()
        add(fxPanel, java.awt.BorderLayout.CENTER)
        
        // JFXPanel constructor starts the FX toolkit if it's not already started.
        // We use Platform.runLater to ensure initialization happens on the FX thread.
        Platform.runLater {
            setupFXScene()
        }
        
        isFocusable = true
        
        // Setup keyboard listener for walk mode
        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (doc.cameraMode == CameraMode.WALK) {
                    keysPressed.add(e.keyCode)
                }
            }
            override fun keyReleased(e: java.awt.event.KeyEvent) {
                keysPressed.remove(e.keyCode)
            }
        })
        
        // Timer for smooth movement in walk mode (60 FPS)
        walkTimer = Timer(16) {
            if (doc.cameraMode == CameraMode.WALK && keysPressed.isNotEmpty()) {
                processWalkMovement()
            }
        }
        walkTimer?.start()
    }
    
    private fun setupFXScene() {
        val rootGroup = Group()
        root = rootGroup
        val scene = Scene(rootGroup, 1000.0, 700.0, true, SceneAntialiasing.BALANCED)
        scene.fill = JFXColor.SKYBLUE
        
        val mGroup = Group()
        modelGroup = mGroup
        rootGroup.children.add(mGroup)
        
        // Separate group for windows - will have its own always-on ambient light
        val wGroup = Group()
        windowGroup = wGroup
        rootGroup.children.add(wGroup)

        // Separate group for utility pipe cylinders
        val ugGroup = Group()
        utilitiesGroup = ugGroup
        rootGroup.children.add(ugGroup)

        // Separate group for point name labels
        val lgGroup = Group()
        labelsGroup = lgGroup
        rootGroup.children.add(lgGroup)
        
        // Always-on ambient light for windows so they remain visible regardless of daylight setting
        val wAmbient = AmbientLight(JFXColor.WHITE)
        windowAmbientLight = wAmbient
        wGroup.children.add(wAmbient)
        
        // Separate group for room lights (point lights)
        val rlGroup = Group()
        roomLightsGroup = rlGroup
        rootGroup.children.add(rlGroup)
        
        // Separate group for yellow spheres marking light positions (night mode only)
        val lsGroup = Group()
        lightSpheresGroup = lsGroup
        rootGroup.children.add(lsGroup)
        
        // Floor grid group
        val fgGroup = Group()
        floorGridGroup = fgGroup
        rootGroup.children.add(fgGroup)
        
        val cam = PerspectiveCamera(true)
        camera = cam
        cam.nearClip = 0.1
        cam.farClip = 10000.0
        cam.fieldOfView = 45.0
        
        val rx = Rotate(0.0, Rotate.X_AXIS)
        val ry = Rotate(0.0, Rotate.Y_AXIS) // Mapping Z rotation to Y axis in FX
        val tr = Translate(0.0, 0.0, 0.0)
        
        cameraXRotate = rx
        cameraZRotate = ry
        cameraTranslate = tr
        
        val cXform = Group()
        cXform.transforms.addAll(ry, rx, tr)
        cXform.children.add(cam)
        
        cameraXform = cXform
        
        rootGroup.children.add(cXform)
        scene.camera = cam
        
        // Add a white ambient light to make the scene visible without directional light sources.
        // This ensures the model is visible with its diffuse colors and isn't affected by rotation.
        val al = AmbientLight(JFXColor.WHITE)
        ambientLight = al
        rootGroup.children.add(al)
        
        updateModel()
        updateCamera()
        
        fxPanel.scene = scene
        
        // Ensure camera is updated once the panel size is known
        fxPanel.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                updateCamera()
            }
        })
        
        // Handle FX mouse events for orbit
        scene.setOnMousePressed { e ->
            lastMouseX = e.sceneX
            lastMouseY = e.sceneY
            // Request focus on the parent panel (ThreeDPanel) so keyboard events work
            SwingUtilities.invokeLater { this@ThreeDPanel.requestFocusInWindow() }
        }
        
        scene.setOnMouseDragged { e ->
            val dx = e.sceneX - lastMouseX
            val dy = e.sceneY - lastMouseY
            
            handleMouseMoveFX(dx, dy)
            
            lastMouseX = e.sceneX
            lastMouseY = e.sceneY
        }
        
        scene.setOnScroll { e ->
            val factor = 1.1.pow(-e.deltaY / 40.0)
            doc.scale *= factor
            updateCamera()
        }
    }
    
    private fun handleMouseMoveFX(dx: Double, dy: Double) {
        if (doc.cameraMode == CameraMode.WALK) {
            // Walk mode: mouse controls look direction
            // Moving mouse right (positive dx) should rotate view right (increase yaw)
            doc.playerYaw += dx * 0.3
            doc.playerPitch = (doc.playerPitch + dy * 0.3).coerceIn(-89.0, 89.0)
            updateCamera()
        } else {
            // Orbit mode: mouse controls orbit rotation
            doc.rotationZ -= dx * 0.5
            doc.rotationX = (doc.rotationX - dy * 0.5).coerceIn(0.0, 90.0)
            updateCamera()
        }
    }
    
    private fun processWalkMovement() {
        val speed = doc.moveSpeed
        // Camera uses -playerYaw for rotation, so we need to use the same angle for movement
        // to match the camera's actual view direction
        val yawRad = Math.toRadians(-doc.playerYaw)
        
        // Calculate forward and right vectors based on camera's actual view direction
        // Forward is the direction the camera is looking (flat projection on XY plane)
        val forwardX = sin(yawRad)
        val forwardY = -cos(yawRad)
        val rightX = cos(yawRad)
        val rightY = sin(yawRad)
        
        var moveX = 0.0
        var moveY = 0.0
        
        // WASD and Arrow keys movement (W=backward, S=forward)
        if (keysPressed.contains(java.awt.event.KeyEvent.VK_W) || keysPressed.contains(java.awt.event.KeyEvent.VK_UP)) {
            moveX -= forwardX * speed
            moveY -= forwardY * speed
        }
        if (keysPressed.contains(java.awt.event.KeyEvent.VK_S) || keysPressed.contains(java.awt.event.KeyEvent.VK_DOWN)) {
            moveX += forwardX * speed
            moveY += forwardY * speed
        }
        if (keysPressed.contains(java.awt.event.KeyEvent.VK_A) || keysPressed.contains(java.awt.event.KeyEvent.VK_LEFT)) {
            moveX += rightX * speed
            moveY += rightY * speed
        }
        if (keysPressed.contains(java.awt.event.KeyEvent.VK_D) || keysPressed.contains(java.awt.event.KeyEvent.VK_RIGHT)) {
            moveX -= rightX * speed
            moveY -= rightY * speed
        }
        
        // Jump - Space key applies upward impulse when on ground
        if (keysPressed.contains(java.awt.event.KeyEvent.VK_SPACE) && doc.isOnGround) {
            doc.verticalVelocity = doc.jumpImpulse
            doc.isOnGround = false
        }
        
        // Apply gravity to vertical velocity
        doc.verticalVelocity += doc.gravity
        
        // Calculate new position
        val newX = doc.playerX + moveX
        val newY = doc.playerY + moveY
        var newZ = doc.playerZ + doc.verticalVelocity
        
        // Calculate floor height at new position (for stairs and ground)
        val stairFloorZ = getFloorHeight(newX, newY, doc.playerZ)
        
        // Get the base floor level from model bounds
        val modelBounds = doc.model.getBounds()
        val baseFloorZ = modelBounds.first.z
        
        // Determine the effective floor height (stairs or base floor)
        val effectiveFloorZ = if (stairFloorZ > Double.MIN_VALUE) stairFloorZ else baseFloorZ
        val groundZ = effectiveFloorZ + doc.playerHeight
        
        // Check for ceiling collision when moving upward
        if (doc.verticalVelocity > 0) {
            val ceilingZ = getCeilingHeight(newX, newY, doc.playerZ)
            if (ceilingZ < Double.MAX_VALUE && newZ >= ceilingZ) {
                // Hit the ceiling - stop upward movement
                newZ = ceilingZ - 1.0  // Small margin below ceiling
                doc.verticalVelocity = 0.0  // Stop upward velocity, start falling
            }
        }
        
        // Check if player has landed on ground
        if (newZ <= groundZ) {
            newZ = groundZ
            doc.verticalVelocity = 0.0
            doc.isOnGround = true
        } else {
            doc.isOnGround = false
        }
        
        // Simple collision check - try to move, check for wall collisions
        if (!checkWallCollision(newX, newY, newZ)) {
            doc.playerX = newX
            doc.playerY = newY
            doc.playerZ = newZ
        } else {
            // Try sliding along walls - check X and Y separately
            if (!checkWallCollision(newX, doc.playerY, newZ)) {
                doc.playerX = newX
                doc.playerZ = newZ
            } else if (!checkWallCollision(doc.playerX, newY, newZ)) {
                doc.playerY = newY
                doc.playerZ = newZ
            } else {
                // Can't move horizontally, but still apply vertical movement
                doc.playerZ = newZ
            }
        }
        
        updateCamera()
    }
    
    private fun checkWallCollision(x: Double, y: Double, z: Double): Boolean {
        val playerRadius = doc.playerRadius
        val playerBottom = z - doc.playerHeight + 10  // Feet level + small margin
        val playerTop = z + 10  // Head level + small margin
        
        // Check if player is within any door opening - if so, allow passage
        for (door in doc.model.doorInfos) {
            if (door.isInOpening(x, y, z, playerRadius)) {
                return false  // Player is in a door opening, no collision
            }
        }
        
        // Check collision with all wall rectangles
        for (rect in doc.model.rects) {
            if (rect.isWindow) continue  // Skip windows
            if (rect.isStairsVisualOnly) continue  // Skip stairs slabs (visual only)
            
            // Get the bounding box of the rectangle in model coordinates
            val minX = minOf(rect.v1.x, rect.v2.x, rect.v3.x, rect.v4.x)
            val maxX = maxOf(rect.v1.x, rect.v2.x, rect.v3.x, rect.v4.x)
            val minY = minOf(rect.v1.y, rect.v2.y, rect.v3.y, rect.v4.y)
            val maxY = maxOf(rect.v1.y, rect.v2.y, rect.v3.y, rect.v4.y)
            val minZ = minOf(rect.v1.z, rect.v2.z, rect.v3.z, rect.v4.z)
            val maxZ = maxOf(rect.v1.z, rect.v2.z, rect.v3.z, rect.v4.z)
            
            // Skip floor/ceiling surfaces (horizontal surfaces)
            val isHorizontal = (maxZ - minZ) < 1.0
            if (isHorizontal) continue
            
            // Check if player cylinder intersects with wall box
            // Expand wall box by player radius for cylinder collision
            val expandedMinX = minX - playerRadius
            val expandedMaxX = maxX + playerRadius
            val expandedMinY = minY - playerRadius
            val expandedMaxY = maxY + playerRadius
            
            // Check XY overlap
            if (x >= expandedMinX && x <= expandedMaxX &&
                y >= expandedMinY && y <= expandedMaxY) {
                // Check Z overlap
                if (playerTop >= minZ && playerBottom <= maxZ) {
                    return true  // Collision detected
                }
            }
        }
        return false
    }
    
    private fun getFloorHeight(x: Double, y: Double, currentZ: Double): Double {
        // Check if player is on any stairs and return the floor height
        // Allow automatic step-up for stair climbing (typical step height is ~17cm)
        // Use a generous margin to allow smooth stair walking without jumping
        var maxFloorZ = Double.MIN_VALUE
        val playerFeet = currentZ - doc.playerHeight
        
        // Step-up height: how high the player can automatically step up without jumping
        // Set to 25cm to accommodate typical stair steps (17cm) with some margin
        val maxStepUp = 25.0
        
        for (stair in doc.model.stairInfos) {
            val stairZ = stair.getFloorZ(x, y)
            if (stairZ > Double.MIN_VALUE) {
                // Allow stepping up onto stairs that are within step-up height above current feet
                // Also allow stairs below current feet (walking down stairs)
                if (stairZ <= playerFeet + maxStepUp && stairZ > maxFloorZ) {
                    maxFloorZ = stairZ
                }
            }
        }
        
        // Also check floor slabs (horizontal surfaces from rectangles) - prevents getting stuck inside them
        for (rect in doc.model.rects) {
            if (rect.isWindow) continue
            if (rect.isStairsVisualOnly) continue  // Skip stairs slabs (visual only)
            
            val minX = minOf(rect.v1.x, rect.v2.x, rect.v3.x, rect.v4.x)
            val maxX = maxOf(rect.v1.x, rect.v2.x, rect.v3.x, rect.v4.x)
            val minY = minOf(rect.v1.y, rect.v2.y, rect.v3.y, rect.v4.y)
            val maxY = maxOf(rect.v1.y, rect.v2.y, rect.v3.y, rect.v4.y)
            val minZ = minOf(rect.v1.z, rect.v2.z, rect.v3.z, rect.v4.z)
            val maxZ = maxOf(rect.v1.z, rect.v2.z, rect.v3.z, rect.v4.z)
            
            // Check if this is a horizontal surface (floor/ceiling)
            val isHorizontal = (maxZ - minZ) < 1.0
            if (!isHorizontal) continue
            
            // Check if player is within XY bounds of this surface
            if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                // Use the top of the surface as floor height
                // Allow stepping up onto floors within step-up height
                if (maxZ <= playerFeet + maxStepUp && maxZ > maxFloorZ) {
                    maxFloorZ = maxZ
                }
            }
        }
        
        // Also check triangular surfaces (from PolygonRoom floor slabs)
        // Use bounding box clipping (same approach as rectangular room slabs) for more reliable collision
        for (tri in doc.model.triangles) {
            if (tri.isStairsVisualOnly) continue  // Skip stairs sides (visual only)
            val minX = minOf(tri.v1.x, tri.v2.x, tri.v3.x)
            val maxX = maxOf(tri.v1.x, tri.v2.x, tri.v3.x)
            val minY = minOf(tri.v1.y, tri.v2.y, tri.v3.y)
            val maxY = maxOf(tri.v1.y, tri.v2.y, tri.v3.y)
            val minZ = minOf(tri.v1.z, tri.v2.z, tri.v3.z)
            val maxZ = maxOf(tri.v1.z, tri.v2.z, tri.v3.z)
            
            // Check if this is a horizontal surface (floor/ceiling)
            val isHorizontal = (maxZ - minZ) < 1.0
            if (!isHorizontal) continue
            
            // Check if player is within the bounding box of the triangle (minimal rectangular slab)
            if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                // Use the top of the surface as floor height
                // Allow stepping up onto floors within step-up height
                if (maxZ <= playerFeet + maxStepUp && maxZ > maxFloorZ) {
                    maxFloorZ = maxZ
                }
            }
        }
        
        return maxFloorZ
    }
    
    private fun isPointInTriangle(px: Double, py: Double, 
                                   x1: Double, y1: Double, 
                                   x2: Double, y2: Double, 
                                   x3: Double, y3: Double): Boolean {
        // Use barycentric coordinate method to check if point is inside triangle
        val denom = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
        if (abs(denom) < 1e-10) return false  // Degenerate triangle
        
        val a = ((y2 - y3) * (px - x3) + (x3 - x2) * (py - y3)) / denom
        val b = ((y3 - y1) * (px - x3) + (x1 - x3) * (py - y3)) / denom
        val c = 1.0 - a - b
        
        // Point is inside if all barycentric coordinates are between 0 and 1
        return a >= 0 && a <= 1 && b >= 0 && b <= 1 && c >= 0 && c <= 1
    }
    
    private fun getCeilingHeight(x: Double, y: Double, currentZ: Double): Double {
        // Find the lowest ceiling (bottom of horizontal surface) above the player's head
        var minCeilingZ = Double.MAX_VALUE
        val playerHead = currentZ  // Player's eye/head level
        
        for (rect in doc.model.rects) {
            if (rect.isWindow) continue
            if (rect.isStairsVisualOnly) continue  // Skip stairs slabs (visual only)
            
            val minX = minOf(rect.v1.x, rect.v2.x, rect.v3.x, rect.v4.x)
            val maxX = maxOf(rect.v1.x, rect.v2.x, rect.v3.x, rect.v4.x)
            val minY = minOf(rect.v1.y, rect.v2.y, rect.v3.y, rect.v4.y)
            val maxY = maxOf(rect.v1.y, rect.v2.y, rect.v3.y, rect.v4.y)
            val minZ = minOf(rect.v1.z, rect.v2.z, rect.v3.z, rect.v4.z)
            val maxZ = maxOf(rect.v1.z, rect.v2.z, rect.v3.z, rect.v4.z)
            
            // Check if this is a horizontal surface (floor/ceiling)
            val isHorizontal = (maxZ - minZ) < 1.0
            if (!isHorizontal) continue
            
            // Check if player is within XY bounds of this surface
            if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                // Use the bottom of the surface as ceiling height
                // Only consider surfaces that are above the player's head
                if (minZ > playerHead && minZ < minCeilingZ) {
                    minCeilingZ = minZ
                }
            }
        }
        
        return minCeilingZ
    }

    fun updateModel() {
        val regularMeshViews = doc.model.rects.map { rect ->
            createRectMeshView(rect)
        }
        val triangleMeshViews = doc.model.triangles.map { tri ->
            createTriangleMeshView(tri)
        }
        
        Platform.runLater {
            modelGroup?.children?.clear()
            modelGroup?.children?.addAll(regularMeshViews)
            modelGroup?.children?.addAll(triangleMeshViews)
            
            // Clear windows
            windowGroup?.children?.removeIf { it !is AmbientLight }
            
            // Add window frames to windowGroup
            val windowMeshViews = doc.model.rects.filter { it.isWindow }.map { rect ->
                createRectMeshView(rect)
            }
            windowGroup?.children?.addAll(windowMeshViews)
            
            // Clear and rebuild room lights group
            roomLightsGroup?.children?.clear()
            lightSpheresGroup?.children?.clear()
            
            // Add point lights at room centers and yellow spheres for night mode
            doc.model.lightPositions.forEach { pos ->
                // Mapping: Model(x, y, z) -> FX(-x, -z, y) with X mirrored
                val fxX = -pos.x
                val fxY = -pos.z
                val fxZ = pos.y
                
                // Internal room light - omnidirectional (no range limit)
                val light = PointLight(JFXColor.WHITE)
                light.translateX = fxX
                light.translateY = fxY
                light.translateZ = fxZ
                light.maxRange = Double.MAX_VALUE
                roomLightsGroup?.children?.add(light)
                
                // Yellow sphere to mark light position (visible in night mode)
                val sphere = Sphere(10.0)
                val yellowMaterial = PhongMaterial(JFXColor.YELLOW)
                yellowMaterial.specularColor = JFXColor.WHITE
                sphere.material = yellowMaterial
                sphere.translateX = fxX
                sphere.translateY = fxY
                sphere.translateZ = fxZ
                lightSpheresGroup?.children?.add(sphere)
            }
            
            // Create floor grid at Z=0 (in absolute model coordinates, not centered)
            floorGridGroup?.children?.clear()
            val bounds = doc.model.getBounds()
            val gridMaterial = PhongMaterial(JFXColor.rgb(100, 100, 100, 0.5))
            val gridSpacing = 100.0  // 100cm grid spacing
            val gridExtent = 2000.0  // Extend grid beyond model bounds
            val minX = bounds.first.x - gridExtent
            val maxX = bounds.second.x + gridExtent
            val minY = bounds.first.y - gridExtent
            val maxY = bounds.second.y + gridExtent
            val gridZ = 0.0  // Z=0 in model coordinates (0th floor level)
            
            // Create grid lines along X axis (use absolute coordinates, then apply centering)
            val center = doc.model.getCenter()
            var y = (minY / gridSpacing).toInt() * gridSpacing
            while (y <= maxY) {
                val line = Box(maxX - minX, 1.0, 1.0)
                line.material = gridMaterial
                // FX coordinates: (-x, -z, y) with centering applied
                line.translateX = -(minX + maxX) / 2.0 + center.x
                line.translateY = -gridZ + center.z
                line.translateZ = y - center.y
                floorGridGroup?.children?.add(line)
                y += gridSpacing
            }
            
            // Create grid lines along Y axis
            var x = (minX / gridSpacing).toInt() * gridSpacing
            while (x <= maxX) {
                val line = Box(1.0, 1.0, maxY - minY)
                line.material = gridMaterial
                // FX coordinates: (-x, -z, y) with centering applied
                line.translateX = -x + center.x
                line.translateY = -gridZ + center.z
                line.translateZ = (minY + maxY) / 2.0 - center.y
                floorGridGroup?.children?.add(line)
                x += gridSpacing
            }
            
            // Center the model group (X is mirrored, so use positive center.x)
            // Note: 'center' already declared above for grid positioning
            modelGroup?.translateX = center.x
            modelGroup?.translateY = center.z // FX Y is -Model Z
            modelGroup?.translateZ = -center.y // FX Z is Model Y
            
            // Apply same centering to window group so windows align with the model
            windowGroup?.translateX = center.x
            windowGroup?.translateY = center.z
            windowGroup?.translateZ = -center.y
            
            // Apply same centering to room lights group so lights align with the model
            roomLightsGroup?.translateX = center.x
            roomLightsGroup?.translateY = center.z
            roomLightsGroup?.translateZ = -center.y
            
            // Apply same centering to light spheres group so spheres align with the model
            lightSpheresGroup?.translateX = center.x
            lightSpheresGroup?.translateY = center.z
            lightSpheresGroup?.translateZ = -center.y

            // Clear and rebuild utilities group (pipe cylinders)
            utilitiesGroup?.children?.clear()
            doc.model.cylinders.forEach { cyl ->
                utilitiesGroup?.children?.add(createCylinderNode(cyl))
            }
            utilitiesGroup?.translateX = center.x
            utilitiesGroup?.translateY = center.z
            utilitiesGroup?.translateZ = -center.y

            // Clear and rebuild labels group (point name text nodes)
            labelsGroup?.children?.clear()
            doc.model.labels.forEach { label ->
                labelsGroup?.children?.add(createLabelNode(label))
            }
            labelsGroup?.translateX = center.x
            labelsGroup?.translateY = center.z
            labelsGroup?.translateZ = -center.y

            // Re-calculate camera distance to fit model
            updateCamera()
            updateLighting()
        }
    }
    
    fun updateLighting() {
        Platform.runLater {
            if (doc.isDayMode) {
                // Day mode: ambient light on, room lights off, no spheres
                ambientLight?.color = JFXColor.WHITE
                roomLightsGroup?.isVisible = false
                lightSpheresGroup?.isVisible = false
            } else {
                // Night mode: dim ambient, room lights on, show yellow spheres
                ambientLight?.color = JFXColor.rgb(30, 30, 40)  // Very dim ambient
                roomLightsGroup?.isVisible = true
                lightSpheresGroup?.isVisible = true
            }
        }
    }
    
    private fun createRectMeshView(rect: Rect3D): MeshView {
        val mesh = TriangleMesh()
        
        // Mapping: Model(x, y, z) -> FX(-x, -z, y) with X mirrored
        fun toFX(v: Vector3D) = floatArrayOf((-v.x).toFloat(), (-v.z).toFloat(), v.y.toFloat())
        
        val p1 = toFX(rect.v1)
        val p2 = toFX(rect.v2)
        val p3 = toFX(rect.v3)
        val p4 = toFX(rect.v4)
        
        mesh.points.addAll(*p1)
        mesh.points.addAll(*p2)
        mesh.points.addAll(*p3)
        mesh.points.addAll(*p4)
        
        mesh.texCoords.addAll(0f, 0f)
        
        // Two triangles for the rect (CullFace.NONE renders both sides, no need for back faces)
        mesh.faces.addAll(
            0, 0, 1, 0, 2, 0,
            0, 0, 2, 0, 3, 0
        )
        
        val meshView = MeshView(mesh)
        val material = PhongMaterial()
        val awtColor = rect.color
        val fxColor = JFXColor.rgb(awtColor.red, awtColor.green, awtColor.blue, awtColor.alpha / 255.0)
        material.diffuseColor = fxColor
        material.specularColor = JFXColor.BLACK
        // Windows are added to a separate group with their own always-on ambient light,
        // so they remain visible regardless of the daylight setting
        meshView.material = material
        meshView.cullFace = CullFace.NONE
        
        return meshView
    }
    
    private fun createCylinderNode(cyl: Cylinder3D): Node {
        val dx = cyl.end.x - cyl.start.x
        val dy = cyl.end.y - cyl.start.y
        val dz = cyl.end.z - cyl.start.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 0.01) return Group()

        val cylinder = Cylinder(cyl.radius, length)
        val material = PhongMaterial(JFXColor.rgb(cyl.color.red, cyl.color.green, cyl.color.blue))
        material.specularColor = JFXColor.WHITE
        cylinder.material = material

        // Midpoint in FX space: model(x,y,z) -> FX(-x,-z,y)
        val midFxX = -(cyl.start.x + cyl.end.x) / 2.0
        val midFxY = -(cyl.start.z + cyl.end.z) / 2.0
        val midFxZ = (cyl.start.y + cyl.end.y) / 2.0

        // Direction in FX space: model(dx,dy,dz) -> FX(-dx,-dz,dy)
        val fxDx = -dx
        val fxDy = -dz
        val fxDz = dy

        val group = Group(cylinder)
        group.translateX = midFxX
        group.translateY = midFxY
        group.translateZ = midFxZ

        // JavaFX Cylinder default axis is Y (0,1,0); rotate to align with direction
        val defaultAxis = Point3D(0.0, 1.0, 0.0)
        val target = Point3D(fxDx, fxDy, fxDz).normalize()
        val cross = defaultAxis.crossProduct(target)
        if (cross.magnitude() > 1e-6) {
            val angle = Math.toDegrees(acos(defaultAxis.dotProduct(target).coerceIn(-1.0, 1.0)))
            group.transforms.add(Rotate(angle, cross))
        } else if (defaultAxis.dotProduct(target) < 0) {
            group.transforms.add(Rotate(180.0, Point3D(1.0, 0.0, 0.0)))
        }

        return group
    }

    private fun createLabelNode(label: Label3D): Node {
        val text = JFXText(label.text)
        text.font = JFXFont.font("System", 20.0)
        text.fill = JFXColor.rgb(label.color.red, label.color.green, label.color.blue)
        // Position in FX space: model(x,y,z) -> FX(-x,-z,y)
        val group = Group(text)
        group.translateX = -label.position.x
        group.translateY = -label.position.z
        group.translateZ = label.position.y
        return group
    }

    private fun createTriangleMeshView(tri: Triangle3D): MeshView {
        val mesh = TriangleMesh()
        
        // Mapping: Model(x, y, z) -> FX(-x, -z, y) with X mirrored
        fun toFX(v: Vector3D) = floatArrayOf((-v.x).toFloat(), (-v.z).toFloat(), v.y.toFloat())
        
        val p1 = toFX(tri.v1)
        val p2 = toFX(tri.v2)
        val p3 = toFX(tri.v3)
        
        mesh.points.addAll(*p1)
        mesh.points.addAll(*p2)
        mesh.points.addAll(*p3)
        
        mesh.texCoords.addAll(0f, 0f)
        
        // Single triangle
        mesh.faces.addAll(
            0, 0, 1, 0, 2, 0
        )
        
        val meshView = MeshView(mesh)
        val material = PhongMaterial()
        val awtColor = tri.color
        val fxColor = JFXColor.rgb(awtColor.red, awtColor.green, awtColor.blue, awtColor.alpha / 255.0)
        material.diffuseColor = fxColor
        material.specularColor = JFXColor.BLACK
        meshView.material = material
        meshView.cullFace = CullFace.NONE
        
        return meshView
    }

    fun updateCamera() {
        Platform.runLater {
            if (doc.cameraMode == CameraMode.WALK) {
                updateWalkCamera()
            } else {
                updateOrbitCamera()
            }
        }
    }
    
    private fun updateOrbitCamera() {
        val bounds = doc.model.getBounds()
        val min = bounds.first
        val max = bounds.second
        
        val width = max.x - min.x
        val height = max.z - min.z // In FX y is -z
        val depth = max.y - min.y // In FX z is y
        
        val radius = sqrt(width * width + height * height + depth * depth) / 2.0
        
        // Calculate distance based on FOV
        val fov = camera?.fieldOfView ?: 45.0
        val halfFovRad = Math.toRadians(fov / 2.0)
        
        val aspectRatio = if (fxPanel.width > 0 && fxPanel.height > 0) fxPanel.width.toDouble() / fxPanel.height.toDouble() else 1000.0 / 700.0
        
        val effectiveHalfFovRad = if (aspectRatio >= 1.0) {
            halfFovRad
        } else {
            atan(tan(halfFovRad) * aspectRatio)
        }
        
        // Add a small margin (1.1x)
        val autoDistance = (radius / sin(effectiveHalfFovRad)) * 1.1
        val distance = (if (autoDistance > 0) autoDistance else 1000.0) / doc.scale
        
        // For orbit mode, we need transform order: rotate THEN translate
        // This makes rotation happen around the scene origin (0,0,0)
        // Reorder transforms: ry, rx, tr (rotations first, then translate)
        cameraXform?.transforms?.clear()
        cameraXform?.transforms?.addAll(cameraZRotate, cameraXRotate, cameraTranslate)
        
        cameraZRotate?.axis = Rotate.Y_AXIS
        cameraZRotate?.angle = -doc.rotationZ
        
        cameraXRotate?.axis = Rotate.X_AXIS
        cameraXRotate?.angle = doc.rotationX - 90.0
        
        // Since modelGroup is centered at (0,0,0), camera rotates around (0,0,0)
        cameraTranslate?.x = 0.0
        cameraTranslate?.y = 0.0
        cameraTranslate?.z = 0.0
        
        camera?.translateX = 0.0
        camera?.translateY = 0.0
        camera?.translateZ = -distance
        
        // Aggressive near and far clip to avoid any clipping
        camera?.nearClip = 0.1
        camera?.farClip = max(100000.0, distance * 100.0)
        
        // Reset FOV to default for orbit view
        camera?.fieldOfView = 45.0
    }
    
    private fun updateWalkCamera() {
        // In walk mode, position camera at player location looking in player direction
        // Model coordinates: (x, y, z) where z is up
        // FX coordinates: (-x, -z, y) - X mirrored, Y is -Z, Z is Y
        
        val center = doc.model.getCenter()
        
        // Player position in model coordinates, adjusted for model centering
        val playerFxX = -doc.playerX + center.x
        val playerFxY = -doc.playerZ + center.z  // FX Y is -Model Z
        val playerFxZ = doc.playerY - center.y   // FX Z is Model Y
        
        // For walk mode, we need transform order: translate THEN rotate
        // This makes rotation happen around the player's position (camera eye)
        // Reorder transforms: tr, ry, rx (translate first, then rotations)
        cameraXform?.transforms?.clear()
        cameraXform?.transforms?.addAll(cameraTranslate, cameraZRotate, cameraXRotate)
        
        // Set camera rotation based on player look direction
        // Yaw rotates around Y axis (vertical in FX)
        // Pitch rotates around X axis
        cameraZRotate?.axis = Rotate.Y_AXIS
        cameraZRotate?.angle = -doc.playerYaw
        
        cameraXRotate?.axis = Rotate.X_AXIS
        cameraXRotate?.angle = -doc.playerPitch
        
        // Position the camera transform at player position
        cameraTranslate?.x = playerFxX
        cameraTranslate?.y = playerFxY
        cameraTranslate?.z = playerFxZ
        
        // Camera itself is at origin of its transform group
        camera?.translateX = 0.0
        camera?.translateY = 0.0
        camera?.translateZ = 0.0
        
        // Set appropriate clip planes for indoor viewing
        camera?.nearClip = 1.0
        camera?.farClip = 100000.0
        
        // Wider FOV for first-person view
        camera?.fieldOfView = 70.0
    }


    fun dispose() {
        Platform.runLater {
            fxPanel.scene = null
            root?.children?.clear()
            modelGroup?.children?.clear()
            windowGroup?.children?.clear()
            utilitiesGroup?.children?.clear()
            roomLightsGroup?.children?.clear()
            root = null
            modelGroup = null
            windowGroup = null
            utilitiesGroup = null
            labelsGroup = null
            roomLightsGroup = null
            camera = null
            cameraXform = null
            ambientLight = null
            windowAmbientLight = null
        }
    }

    override fun paintComponent(g: java.awt.Graphics) {
        super.paintComponent(g)
    }
}
