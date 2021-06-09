package graphics.scenery.tests.examples.basic

import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import org.joml.Vector3f
import org.scijava.ui.behaviour.ClickBehaviour

class ProteinRollercoasterExample: SceneryBase("RollerCoaster", wantREPL = true, windowWidth = 1280, windowHeight = 720) {
    private val protein = Protein.fromID("3nir")
    private val ribbon = RibbonDiagram(protein, false, scene)

    override fun init() {
        renderer = hub.add(Renderer.createRenderer(hub, applicationName, scene, windowWidth, windowHeight))

        val rowSize = 20f

        val matFaint = Material()
        matFaint.diffuse  = Vector3f(0.0f, 0.6f, 0.6f)
        matFaint.ambient  = Vector3f(1.0f, 1.0f, 1.0f)
        matFaint.specular = Vector3f(1.0f, 1.0f, 1.0f)
        matFaint.cullingMode = Material.CullingMode.None


        ribbon.children.forEach{ subProtein ->
            subProtein.children.forEach{ helix ->
                if(helix is Helix) {
                    val arrow = Arrow(helix.axis.direction - Vector3f())
                    arrow.position = helix.axis.position
                    arrow.edgeWidth = 0.5f
                    arrow.material = matFaint
                    scene.addChild(arrow)

                }
            }
        }
        scene.addChild(ribbon)


        val lightbox = Box(Vector3f(500.0f, 500.0f, 500.0f), insideNormals = true)
        lightbox.name = "Lightbox"
        lightbox.material.diffuse = Vector3f(0.1f, 0.1f, 0.1f)
        lightbox.material.roughness = 1.0f
        lightbox.material.metallic = 0.0f
        lightbox.material.cullingMode = Material.CullingMode.None
        scene.addChild(lightbox)
        val lights = (0 until 8).map {
            val l = PointLight(radius = 80.0f)
            l.position = Vector3f(
                Random.randomFromRange(-rowSize/2.0f, rowSize/2.0f),
                Random.randomFromRange(-rowSize/2.0f, rowSize/2.0f),
                Random.randomFromRange(1.0f, 5.0f)
            )
            l.emissionColor = Random.random3DVectorFromRange( 0.2f, 0.8f)
            l.intensity = Random.randomFromRange(0.2f, 0.8f)

            lightbox.addChild(l)
            l
        }

        lights.forEach { lightbox.addChild(it) }

        val stageLight = PointLight(radius = 500.0f)
        stageLight.name = "StageLight"
        stageLight.intensity = 0.5f
        stageLight.position = Vector3f(0.0f, 0.0f, 5.0f)
        scene.addChild(stageLight)

        val cameraLight = PointLight(radius = 50.0f)
        cameraLight.name = "CameraLight"
        cameraLight.emissionColor = Vector3f(1.0f, 1.0f, 0.0f)
        cameraLight.intensity = 2.0f

        val cam: Camera = DetachedHeadCamera()
        cam.name = "camera"
        cam.position = Vector3f(0.0f, 0.0f, 15.0f)
        cam.perspectiveCamera(50.0f, windowWidth, windowHeight)
        scene.addChild(cam)

        cam.addChild(cameraLight)
    }

    override fun inputSetup() {
        super.inputSetup()
        inputHandler?.addBehaviour("rollercoaster", Rollercoaster(ribbon, {scene.activeObserver} ))
        inputHandler?.addKeyBinding("rollercoaster", "E")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ProteinRollercoasterExample().main()
        }
    }
}


