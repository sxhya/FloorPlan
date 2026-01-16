package ui.components

import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.*
import javafx.scene.paint.Color as JFXColor
import javafx.scene.paint.PhongMaterial
import javafx.scene.shape.CullFace
import javafx.scene.shape.MeshView
import javafx.scene.shape.TriangleMesh
import javafx.scene.transform.Rotate
import javafx.scene.transform.Translate
import model.Rect3D
import model.Triangle3D
import model.Vector3D
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
    private var roomLightsGroup: Group? = null  // Group for room lights (visible in night mode only)
    private var camera: PerspectiveCamera? = null
    private var cameraXform: Group? = null
    private var cameraXRotate: Rotate? = null
    private var cameraZRotate: Rotate? = null
    private var cameraTranslate: Translate? = null
    private var ambientLight: AmbientLight? = null
    private var windowAmbientLight: AmbientLight? = null  // Always-on light for windows
    
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    
    init {
        layout = java.awt.BorderLayout()
        add(fxPanel, java.awt.BorderLayout.CENTER)
        
        // JFXPanel constructor starts the FX toolkit if it's not already started.
        // We use Platform.runLater to ensure initialization happens on the FX thread.
        Platform.runLater {
            setupFXScene()
        }
        
        isFocusable = true
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
        
        // Always-on ambient light for windows so they remain visible regardless of daylight setting
        val wAmbient = AmbientLight(JFXColor.WHITE)
        windowAmbientLight = wAmbient
        wGroup.children.add(wAmbient)
        
        // Separate group for room lights (point lights + spheres) - visible only in night mode
        val rlGroup = Group()
        roomLightsGroup = rlGroup
        rootGroup.children.add(rlGroup)
        
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
            Platform.runLater { fxPanel.requestFocus() }
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
        doc.rotationZ -= dx * 0.5
        doc.rotationX = (doc.rotationX + dy * 0.5).coerceIn(0.0, 90.0)
        updateCamera()
    }

    fun updateModel() {
        val regularMeshViews = doc.model.rects.filter { !it.isWindow }.map { rect ->
            createRectMeshView(rect)
        }
        val triangleMeshViews = doc.model.triangles.map { tri ->
            createTriangleMeshView(tri)
        }
        val windowMeshViews = doc.model.rects.filter { it.isWindow }.map { rect ->
            createRectMeshView(rect)
        }
        
        Platform.runLater {
            modelGroup?.children?.clear()
            modelGroup?.children?.addAll(regularMeshViews)
            modelGroup?.children?.addAll(triangleMeshViews)
            
            // Keep the window ambient light, clear only meshes, then re-add windows
            windowGroup?.children?.removeIf { it !is AmbientLight }
            windowGroup?.children?.addAll(windowMeshViews)
            
            // Clear and rebuild room lights group
            roomLightsGroup?.children?.clear()
            
            // Add point lights at room centers (no visual spheres)
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
            }
            
            // Center the model group (X is mirrored, so use positive center.x)
            val center = doc.model.getCenter()
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
            
            // Re-calculate camera distance to fit model
            updateCamera()
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
            // Orbit mode only
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
        }
    }


    fun dispose() {
        Platform.runLater {
            fxPanel.scene = null
            root?.children?.clear()
            modelGroup?.children?.clear()
            windowGroup?.children?.clear()
            roomLightsGroup?.children?.clear()
            root = null
            modelGroup = null
            windowGroup = null
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
