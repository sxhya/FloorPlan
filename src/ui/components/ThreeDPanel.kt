package ui.components

import model.Rect3D
import model.Vector3D
import ui.CameraMode
import ui.ThreeDDocument
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import javax.swing.*
import kotlin.math.*

class ThreeDPanel(private val doc: ThreeDDocument) : JPanel() {
    private var lastMouseX = 0
    private var lastMouseY = 0
    private var isDragging = false
    
    // Keys currently pressed for walk mode
    private val keysPressed = mutableSetOf<Int>()
    
    // Game loop timer for walk mode physics
    private var gameTimer: Timer? = null
    private var lastUpdateTime = System.currentTimeMillis()
    
    // Z-buffer for proper depth testing
    private var zBuffer: DoubleArray? = null
    private var colorBuffer: BufferedImage? = null
    private var bufferWidth = 0
    private var bufferHeight = 0
    
    // Key listener for walk mode
    private val walkKeyListener = object : KeyListener {
        override fun keyPressed(e: KeyEvent) {
            if (doc.cameraMode == CameraMode.WALK) {
                keysPressed.add(e.keyCode)
            }
        }
        
        override fun keyReleased(e: KeyEvent) {
            keysPressed.remove(e.keyCode)
        }
        
        override fun keyTyped(e: KeyEvent) {}
    }
    
    init {
        background = Color.BLACK
        isFocusable = true
        
        val mouseAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                lastMouseX = e.x
                lastMouseY = e.y
                isDragging = true
            }

            override fun mouseReleased(e: MouseEvent) {
                isDragging = false
            }

            override fun mouseDragged(e: MouseEvent) {
                handleMouseMove(e)
            }
        }
        addMouseListener(mouseAdapter)
        addMouseMotionListener(mouseAdapter)
        
        addMouseWheelListener { e ->
            if (doc.cameraMode == CameraMode.ORBIT) {
                val rotation = e.preciseWheelRotation
                val factor = 1.1.pow(-rotation)
                doc.scale *= factor
                repaint()
            }
        }
        
        // Add key listener directly to the panel
        addKeyListener(walkKeyListener)
        
        // Focus listener to clear keys when focus is lost
        addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                keysPressed.clear()
            }
        })
    }
    
    private fun handleMouseMove(e: MouseEvent) {
        if (!isDragging) return
        
        val dx = e.x - lastMouseX
        val dy = e.y - lastMouseY
        
        when (doc.cameraMode) {
            CameraMode.ORBIT -> {
                doc.rotationZ -= dx * 0.5
                doc.rotationX = (doc.rotationX + dy * 0.5).coerceIn(0.0, 90.0)
            }
            CameraMode.WALK -> {
                // Mouse drag to look around (like first-person shooter)
                doc.walkYaw -= dx * 0.3
                doc.walkPitch = (doc.walkPitch - dy * 0.3).coerceIn(-89.0, 89.0)
            }
        }
        
        lastMouseX = e.x
        lastMouseY = e.y
        repaint()
    }
    
    fun startWalkMode() {
        doc.initWalkPosition()
        keysPressed.clear()
        
        // Ensure focus is acquired - first activate the window, then focus the panel
        SwingUtilities.invokeLater {
            val window = SwingUtilities.getWindowAncestor(this)
            window?.toFront()
            window?.requestFocus()
            requestFocusInWindow()
        }
        
        // Start game loop
        if (gameTimer == null) {
            lastUpdateTime = System.currentTimeMillis()
            gameTimer = Timer(16) { // ~60 FPS
                updateWalkPhysics()
            }
            gameTimer?.start()
        }
        repaint()
    }
    
    fun stopWalkMode() {
        gameTimer?.stop()
        gameTimer = null
        keysPressed.clear()
        repaint()
    }
    
    // Button-triggered movement methods for toolbar controls
    fun moveForward() {
        if (doc.cameraMode != CameraMode.WALK) {
            doc.cameraMode = CameraMode.WALK
            doc.initWalkPosition()
        }
        performMove(1.0, 0.0)
    }
    
    fun moveBackward() {
        if (doc.cameraMode != CameraMode.WALK) {
            doc.cameraMode = CameraMode.WALK
            doc.initWalkPosition()
        }
        performMove(-1.0, 0.0)
    }
    
    fun moveLeft() {
        if (doc.cameraMode != CameraMode.WALK) {
            doc.cameraMode = CameraMode.WALK
            doc.initWalkPosition()
        }
        performMove(0.0, -1.0)
    }
    
    fun moveRight() {
        if (doc.cameraMode != CameraMode.WALK) {
            doc.cameraMode = CameraMode.WALK
            doc.initWalkPosition()
        }
        performMove(0.0, 1.0)
    }
    
    fun jump() {
        if (doc.cameraMode != CameraMode.WALK) {
            doc.cameraMode = CameraMode.WALK
            doc.initWalkPosition()
        }
        if (doc.isOnGround) {
            doc.velocityZ = doc.jumpVelocity
            doc.isOnGround = false
        }
    }
    
    private fun performMove(forwardAmount: Double, rightAmount: Double) {
        val yawRad = Math.toRadians(doc.walkYaw)
        val forward = Vector3D(sin(yawRad), cos(yawRad), 0.0)
        val right = Vector3D(cos(yawRad), -sin(yawRad), 0.0)
        
        var moveX = forward.x * forwardAmount + right.x * rightAmount
        var moveY = forward.y * forwardAmount + right.y * rightAmount
        
        // Normalize movement
        val moveLen = sqrt(moveX * moveX + moveY * moveY)
        if (moveLen > 0) {
            moveX /= moveLen
            moveY /= moveLen
        }
        
        // Apply a single step of movement (equivalent to ~0.25 seconds of walking for more noticeable movement)
        val stepSize = doc.walkSpeed * 0.25
        val newX = doc.walkX + moveX * stepSize
        val newY = doc.walkY + moveY * stepSize
        
        // Check collision and apply movement
        val (finalX, finalY) = checkCollisionAndMove(doc.walkX, doc.walkY, newX, newY, doc.walkZ)
        doc.walkX = finalX
        doc.walkY = finalY
        
        repaint()
    }
    
    private fun updateWalkPhysics() {
        if (doc.cameraMode != CameraMode.WALK) return
        
        val currentTime = System.currentTimeMillis()
        val deltaTime = (currentTime - lastUpdateTime) / 1000.0 // seconds
        lastUpdateTime = currentTime
        
        // Limit delta time to prevent huge jumps
        val dt = minOf(deltaTime, 0.1)
        
        // Calculate movement direction based on keys pressed
        var moveX = 0.0
        var moveY = 0.0
        
        val yawRad = Math.toRadians(doc.walkYaw)
        val forward = Vector3D(sin(yawRad), cos(yawRad), 0.0)
        val right = Vector3D(cos(yawRad), -sin(yawRad), 0.0)
        
        if (KeyEvent.VK_W in keysPressed || KeyEvent.VK_UP in keysPressed) {
            moveX += forward.x
            moveY += forward.y
        }
        if (KeyEvent.VK_S in keysPressed || KeyEvent.VK_DOWN in keysPressed) {
            moveX -= forward.x
            moveY -= forward.y
        }
        if (KeyEvent.VK_A in keysPressed || KeyEvent.VK_LEFT in keysPressed) {
            moveX -= right.x
            moveY -= right.y
        }
        if (KeyEvent.VK_D in keysPressed || KeyEvent.VK_RIGHT in keysPressed) {
            moveX += right.x
            moveY += right.y
        }
        
        // Normalize movement
        val moveLen = sqrt(moveX * moveX + moveY * moveY)
        if (moveLen > 0) {
            moveX /= moveLen
            moveY /= moveLen
        }
        
        // Apply movement with collision detection
        val speed = doc.walkSpeed * dt
        val newX = doc.walkX + moveX * speed
        val newY = doc.walkY + moveY * speed
        
        // Check collision and apply movement
        val (finalX, finalY) = checkCollisionAndMove(doc.walkX, doc.walkY, newX, newY, doc.walkZ)
        doc.walkX = finalX
        doc.walkY = finalY
        
        // Apply gravity and jumping
        if (!doc.isOnGround) {
            doc.velocityZ -= doc.gravity * dt
            doc.walkZ += doc.velocityZ * dt
        }
        
        // Find floor height at current position
        val floorZ = findFloorHeight(doc.walkX, doc.walkY)
        val targetZ = floorZ + doc.eyeHeight
        
        if (doc.walkZ <= targetZ) {
            doc.walkZ = targetZ
            doc.velocityZ = 0.0
            doc.isOnGround = true
        } else {
            doc.isOnGround = false
        }
        
        // Jump
        if ((KeyEvent.VK_SPACE in keysPressed) && doc.isOnGround) {
            doc.velocityZ = doc.jumpVelocity
            doc.isOnGround = false
        }
        
        repaint()
    }
    
    private fun checkCollisionAndMove(oldX: Double, oldY: Double, newX: Double, newY: Double, z: Double): Pair<Double, Double> {
        val playerBottom = z - doc.eyeHeight
        val playerTop = z + 10 // Small buffer above eyes
        val radius = doc.playerRadius
        
        var resultX = newX
        var resultY = newY
        
        // Check collision with walls (non-highlight rects)
        for (rect in doc.model.rects) {
            // Skip transparent objects (windows, doors) - player can pass through
            if (rect.isHighlight) continue
            
            // Get rect bounds
            val minRectX = minOf(rect.v1.x, rect.v2.x, rect.v3.x, rect.v4.x)
            val maxRectX = maxOf(rect.v1.x, rect.v2.x, rect.v3.x, rect.v4.x)
            val minRectY = minOf(rect.v1.y, rect.v2.y, rect.v3.y, rect.v4.y)
            val maxRectY = maxOf(rect.v1.y, rect.v2.y, rect.v3.y, rect.v4.y)
            val minRectZ = minOf(rect.v1.z, rect.v2.z, rect.v3.z, rect.v4.z)
            val maxRectZ = maxOf(rect.v1.z, rect.v2.z, rect.v3.z, rect.v4.z)
            
            // Check if player height overlaps with rect
            if (playerTop < minRectZ || playerBottom > maxRectZ) continue
            
            // Skip floor/ceiling slabs (thin horizontal surfaces)
            if (maxRectZ - minRectZ < 5) continue
            
            // Check horizontal collision with expanded bounds
            val expandedMinX = minRectX - radius
            val expandedMaxX = maxRectX + radius
            val expandedMinY = minRectY - radius
            val expandedMaxY = maxRectY + radius
            
            // Check if new position collides
            if (resultX >= expandedMinX && resultX <= expandedMaxX &&
                resultY >= expandedMinY && resultY <= expandedMaxY) {
                
                // Determine which axis to block
                val wasInsideX = oldX >= expandedMinX && oldX <= expandedMaxX
                val wasInsideY = oldY >= expandedMinY && oldY <= expandedMaxY
                
                if (!wasInsideX) {
                    // Block X movement
                    resultX = if (oldX < expandedMinX) expandedMinX - 0.1 else expandedMaxX + 0.1
                }
                if (!wasInsideY) {
                    // Block Y movement
                    resultY = if (oldY < expandedMinY) expandedMinY - 0.1 else expandedMaxY + 0.1
                }
                
                // If was inside both (shouldn't happen normally), push out
                if (wasInsideX && wasInsideY) {
                    resultX = oldX
                    resultY = oldY
                }
            }
        }
        
        return Pair(resultX, resultY)
    }
    
    private fun findFloorHeight(x: Double, y: Double): Double {
        var maxFloorZ = 0.0
        val playerBottom = doc.walkZ - doc.eyeHeight
        
        for (rect in doc.model.rects) {
            // Look for horizontal surfaces (floors)
            val minZ = minOf(rect.v1.z, rect.v2.z, rect.v3.z, rect.v4.z)
            val maxZ = maxOf(rect.v1.z, rect.v2.z, rect.v3.z, rect.v4.z)
            
            // Check if it's a horizontal surface (thin in Z)
            if (maxZ - minZ > 5) continue
            
            // Check if player is above this surface
            if (maxZ > playerBottom + 50) continue // Can't stand on surfaces too far above
            
            // Check if player is within XY bounds
            val minX = minOf(rect.v1.x, rect.v2.x, rect.v3.x, rect.v4.x)
            val maxX = maxOf(rect.v1.x, rect.v2.x, rect.v3.x, rect.v4.x)
            val minY = minOf(rect.v1.y, rect.v2.y, rect.v3.y, rect.v4.y)
            val maxY = maxOf(rect.v1.y, rect.v2.y, rect.v3.y, rect.v4.y)
            
            if (x >= minX && x <= maxX && y >= minY && y <= maxY) {
                maxFloorZ = maxOf(maxFloorZ, maxZ)
            }
        }
        
        return maxFloorZ
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        when (doc.cameraMode) {
            CameraMode.ORBIT -> paintOrbitModeWithZBuffer(g2)
            CameraMode.WALK -> paintWalkModeWithZBuffer(g2)
        }
    }
    
    private fun ensureBuffers(w: Int, h: Int) {
        if (colorBuffer == null || bufferWidth != w || bufferHeight != h) {
            bufferWidth = w
            bufferHeight = h
            colorBuffer = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            zBuffer = DoubleArray(w * h)
        }
    }
    
    private fun clearBuffers() {
        val g = colorBuffer!!.createGraphics()
        g.color = Color.BLACK
        g.fillRect(0, 0, bufferWidth, bufferHeight)
        g.dispose()
        
        zBuffer!!.fill(Double.MAX_VALUE)
    }
    
    /**
     * Calculate lit color for a surface based on point light sources.
     * Uses inverse square falloff with ambient light.
     */
    private fun calculateLitColor(rect: Rect3D): Color {
        val lights = doc.model.lightPositions
        if (lights.isEmpty()) {
            // No lights - return original color
            return rect.color
        }
        
        // Calculate center of the rect
        val centerX = (rect.v1.x + rect.v2.x + rect.v3.x + rect.v4.x) / 4.0
        val centerY = (rect.v1.y + rect.v2.y + rect.v3.y + rect.v4.y) / 4.0
        val centerZ = (rect.v1.z + rect.v2.z + rect.v3.z + rect.v4.z) / 4.0
        
        // Ambient light level (minimum brightness)
        val ambient = 0.2
        
        // Calculate total light contribution from all light sources
        var totalLight = 0.0
        val lightRadius = 500.0 // Light effective radius in cm
        
        for (light in lights) {
            val dx = centerX - light.x
            val dy = centerY - light.y
            val dz = centerZ - light.z
            val distance = sqrt(dx * dx + dy * dy + dz * dz)
            
            // Inverse square falloff with attenuation
            if (distance < lightRadius) {
                val attenuation = 1.0 - (distance / lightRadius)
                totalLight += attenuation * attenuation // Quadratic falloff
            }
        }
        
        // Clamp total light contribution
        val lightLevel = (ambient + totalLight * (1.0 - ambient)).coerceIn(0.0, 1.0)
        
        // Apply lighting to color
        val r = (rect.color.red * lightLevel).toInt().coerceIn(0, 255)
        val g = (rect.color.green * lightLevel).toInt().coerceIn(0, 255)
        val b = (rect.color.blue * lightLevel).toInt().coerceIn(0, 255)
        
        return Color(r, g, b)
    }
    
    private fun paintOrbitModeWithZBuffer(g2: Graphics2D) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        
        ensureBuffers(w, h)
        clearBuffers()
        
        val centerX = w / 2.0
        val centerY = h / 2.0

        val radX = Math.toRadians(doc.rotationX)
        val radZ = Math.toRadians(doc.rotationZ)
        
        val modelCenter = doc.model.getCenter()
        val rectsToDraw = if (doc.showHighlighting) doc.model.rects else doc.model.rects.filter { !it.isHighlight }

        for (rect in rectsToDraw) {
            val p1 = projectOrbit(rect.v1, modelCenter, radX, radZ, doc.scale, centerX, centerY)
            val p2 = projectOrbit(rect.v2, modelCenter, radX, radZ, doc.scale, centerX, centerY)
            val p3 = projectOrbit(rect.v3, modelCenter, radX, radZ, doc.scale, centerX, centerY)
            val p4 = projectOrbit(rect.v4, modelCenter, radX, radZ, doc.scale, centerX, centerY)
            
            val z1 = getOrbitDepth(rect.v1, modelCenter, radX, radZ)
            val z2 = getOrbitDepth(rect.v2, modelCenter, radX, radZ)
            val z3 = getOrbitDepth(rect.v3, modelCenter, radX, radZ)
            val z4 = getOrbitDepth(rect.v4, modelCenter, radX, radZ)
            
            // Apply lighting to the base color
            val litColor = calculateLitColor(rect)
            val alpha = if (rect.isHighlight) 100 else 180
            val color = Color(litColor.red, litColor.green, litColor.blue, alpha)
            
            // Draw two triangles for the quad with Z-buffer
            drawTriangleWithZBuffer(p1, p2, p3, z1, z2, z3, color)
            drawTriangleWithZBuffer(p1, p3, p4, z1, z3, z4, color)
        }
        
        g2.drawImage(colorBuffer, 0, 0, null)
    }
    
    private fun paintWalkModeWithZBuffer(g2: Graphics2D) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        
        ensureBuffers(w, h)
        clearBuffers()
        
        val centerX = w / 2.0
        val centerY = h / 2.0
        
        val cameraPos = Vector3D(doc.walkX, doc.walkY, doc.walkZ)
        val yawRad = Math.toRadians(doc.walkYaw)
        val pitchRad = Math.toRadians(doc.walkPitch)
        
        val rectsToDraw = if (doc.showHighlighting) doc.model.rects else doc.model.rects.filter { !it.isHighlight }
        
        for (rect in rectsToDraw) {
            val proj1 = projectWalk(rect.v1, cameraPos, yawRad, pitchRad, centerX, centerY)
            val proj2 = projectWalk(rect.v2, cameraPos, yawRad, pitchRad, centerX, centerY)
            val proj3 = projectWalk(rect.v3, cameraPos, yawRad, pitchRad, centerX, centerY)
            val proj4 = projectWalk(rect.v4, cameraPos, yawRad, pitchRad, centerX, centerY)
            
            // Skip if all points are behind camera
            if (proj1 == null && proj2 == null && proj3 == null && proj4 == null) continue
            
            // Apply lighting to the base color
            val litColor = calculateLitColor(rect)
            val alpha = if (rect.isHighlight) 100 else 200
            val color = Color(litColor.red, litColor.green, litColor.blue, alpha)
            
            // Draw triangles with clipping for partially visible quads
            if (proj1 != null && proj2 != null && proj3 != null) {
                drawTriangleWithZBuffer(
                    Point2D(proj1.x, proj1.y), Point2D(proj2.x, proj2.y), Point2D(proj3.x, proj3.y),
                    proj1.z, proj2.z, proj3.z, color
                )
            }
            if (proj1 != null && proj3 != null && proj4 != null) {
                drawTriangleWithZBuffer(
                    Point2D(proj1.x, proj1.y), Point2D(proj3.x, proj3.y), Point2D(proj4.x, proj4.y),
                    proj1.z, proj3.z, proj4.z, color
                )
            }
        }
        
        g2.drawImage(colorBuffer, 0, 0, null)
        
        // Draw crosshair
        g2.color = Color.WHITE
        val crossSize = 10
        g2.drawLine(w/2 - crossSize, h/2, w/2 + crossSize, h/2)
        g2.drawLine(w/2, h/2 - crossSize, w/2, h/2 + crossSize)
        
        // Draw instructions
        g2.color = Color.WHITE
        g2.font = Font("SansSerif", Font.PLAIN, 12)
        g2.drawString("WASD/Arrows: Move | Mouse drag: Look | Space: Jump", 10, h - 10)
    }
    
    private fun drawTriangleWithZBuffer(
        p1: Point2D, p2: Point2D, p3: Point2D,
        z1: Double, z2: Double, z3: Double,
        color: Color
    ) {
        // Sort vertices by Y coordinate
        val vertices = listOf(
            Triple(p1, z1, 0),
            Triple(p2, z2, 1),
            Triple(p3, z3, 2)
        ).sortedBy { it.first.y }
        
        val (top, topZ, _) = vertices[0]
        val (mid, midZ, _) = vertices[1]
        val (bot, botZ, _) = vertices[2]
        
        // Scanline rasterization
        val yStart = maxOf(0, top.y.toInt())
        val yEnd = minOf(bufferHeight - 1, bot.y.toInt())
        
        for (y in yStart..yEnd) {
            val yf = y.toDouble()
            
            // Calculate x bounds for this scanline
            var xLeft = Double.MAX_VALUE
            var xRight = -Double.MAX_VALUE
            var zLeft = 0.0
            var zRight = 0.0
            
            // Edge from top to bot
            if (bot.y != top.y) {
                val t = (yf - top.y) / (bot.y - top.y)
                if (t in 0.0..1.0) {
                    val x = top.x + t * (bot.x - top.x)
                    val z = topZ + t * (botZ - topZ)
                    if (x < xLeft) { xLeft = x; zLeft = z }
                    if (x > xRight) { xRight = x; zRight = z }
                }
            }
            
            // Edge from top to mid
            if (mid.y != top.y && yf <= mid.y) {
                val t = (yf - top.y) / (mid.y - top.y)
                if (t in 0.0..1.0) {
                    val x = top.x + t * (mid.x - top.x)
                    val z = topZ + t * (midZ - topZ)
                    if (x < xLeft) { xLeft = x; zLeft = z }
                    if (x > xRight) { xRight = x; zRight = z }
                }
            }
            
            // Edge from mid to bot
            if (bot.y != mid.y && yf >= mid.y) {
                val t = (yf - mid.y) / (bot.y - mid.y)
                if (t in 0.0..1.0) {
                    val x = mid.x + t * (bot.x - mid.x)
                    val z = midZ + t * (botZ - midZ)
                    if (x < xLeft) { xLeft = x; zLeft = z }
                    if (x > xRight) { xRight = x; zRight = z }
                }
            }
            
            if (xLeft > xRight) continue
            
            val xStart = maxOf(0, xLeft.toInt())
            val xEnd = minOf(bufferWidth - 1, xRight.toInt())
            
            for (x in xStart..xEnd) {
                // Interpolate Z
                val t = if (xRight != xLeft) (x - xLeft) / (xRight - xLeft) else 0.0
                val z = zLeft + t * (zRight - zLeft)
                
                val idx = y * bufferWidth + x
                if (z < zBuffer!![idx]) {
                    zBuffer!![idx] = z
                    colorBuffer!!.setRGB(x, y, color.rgb)
                }
            }
        }
    }
    
    private data class Point2D(val x: Double, val y: Double)
    private data class Point3D(val x: Double, val y: Double, val z: Double)

    private fun getOrbitDepth(v: Vector3D, center: Vector3D, radX: Double, radZ: Double): Double {
        val rotated = rotateOrbit(v, center, radX, radZ)
        return rotated.z + 2000.0 // Add offset to keep all values positive
    }
    
    private fun rotateOrbit(v: Vector3D, center: Vector3D, radX: Double, radZ: Double): Vector3D {
        val vx = v.x - center.x
        val vy = v.y - center.y
        val vz = v.z - center.z

        val x0 = vx * cos(radZ) - vy * sin(radZ)
        val y0 = vx * sin(radZ) + vy * cos(radZ)
        
        val angle = -PI/2 - radX
        val y1 = y0 * cos(angle) - vz * sin(angle)
        val z1 = y0 * sin(angle) + vz * cos(angle)
        
        return Vector3D(x0, y1, z1)
    }

    private fun projectOrbit(v: Vector3D, center: Vector3D, radX: Double, radZ: Double, scale: Double, centerX: Double, centerY: Double): Point2D {
        val rotated = rotateOrbit(v, center, radX, radZ)
        
        val d = 1000.0
        val zOffset = 2000.0
        val pScale = d / (rotated.z + zOffset)
        
        val px = rotated.x * pScale * scale + centerX
        val py = -rotated.y * pScale * scale + centerY
        
        return Point2D(px, py)
    }
    
    private fun projectWalk(v: Vector3D, camera: Vector3D, yawRad: Double, pitchRad: Double, centerX: Double, centerY: Double): Point3D? {
        // Translate to camera space
        val dx = v.x - camera.x
        val dy = v.y - camera.y
        val dz = v.z - camera.z
        
        // Rotate by yaw (around Z axis, but we're looking along Y initially)
        val cosYaw = cos(yawRad)
        val sinYaw = sin(yawRad)
        val x1 = dx * cosYaw + dy * sinYaw
        val y1 = -dx * sinYaw + dy * cosYaw
        val z1 = dz
        
        // Rotate by pitch (around X axis)
        val cosPitch = cos(pitchRad)
        val sinPitch = sin(pitchRad)
        val y2 = y1 * cosPitch - z1 * sinPitch
        val z2 = y1 * sinPitch + z1 * cosPitch
        
        // y2 is now depth (forward), x1 is left/right, z2 is up/down
        
        // Near plane clipping
        if (y2 < 10) return null
        
        // Perspective projection
        val fov = 90.0
        val fovScale = 1.0 / tan(Math.toRadians(fov / 2.0))
        
        val px = centerX + (x1 / y2) * centerY * fovScale
        val py = centerY - (z2 / y2) * centerY * fovScale
        
        // Return screen position and depth for Z-buffer
        return Point3D(px, py, y2)
    }
}
