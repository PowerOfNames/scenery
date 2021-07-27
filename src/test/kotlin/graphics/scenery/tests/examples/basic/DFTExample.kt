package graphics.scenery.tests.examples.basic

import org.joml.Vector3f
import graphics.scenery.*
import graphics.scenery.backends.Renderer
import graphics.scenery.numerics.Random
import graphics.scenery.textures.Texture
import graphics.scenery.utils.Image
import graphics.scenery.volumes.Colormap
import graphics.scenery.volumes.TransferFunction
import graphics.scenery.volumes.Volume
import net.imglib2.type.numeric.integer.UnsignedByteType
import kotlin.concurrent.thread

/**
 * <Description>
 *  Visualizes a DFT snapshot.
 *
 * @author Lenz Fiedler <l.fiedler@hzdr.de>
 */
class DFTExample : SceneryBase("DFTExample", wantREPL = System.getProperty("scenery.master", "false").toBoolean()) {
    override fun init() {
        renderer = hub.add(SceneryElement.Renderer,
            Renderer.createRenderer(hub, applicationName, scene, 512, 512))
        val snapshot = DFTParser()
        snapshot.parseFile("/home/fiedlerl/data/qe_calcs/Fe2/dft/snapshot0/" +
            "Fe_snapshot0_dens.cube")

        // Visualize the atoms.
        for(i in 0 until snapshot.numberOfAtoms) {
            val s = Icosphere(1.0f, 4)
            s.position = snapshot.atomicPositions[i]
            s.material.metallic = 0.3f
            s.material.roughness = 0.9f
            scene.addChild(s)
        }

        // Visualize the density data.
        val volume = Volume.fromBuffer(emptyList(), snapshot.gridDimensions[0], snapshot.gridDimensions[1],
                                        snapshot.gridDimensions[2], UnsignedByteType(), hub)

        volume.name = "volume"
        volume.position = Vector3f(2.683464f, 2.683464f, 2.683464f)
        volume.colormap = Colormap.get("viridis")
        volume.pixelToWorldRatio = snapshot.gridSpacings[0]

        // Do I need this?
//        with(volume.transferFunction) {
//            addControlPoint(0.0f, 0.01f)
//            addControlPoint(0.2f, 0.02f)
//            addControlPoint(0.4f, 0.03f)
//            addControlPoint(0.8f, 0.04f)
//            addControlPoint(1.0f, 0.05f)
//        }
        volume.transferFunction = TransferFunction.ramp(0.0f, 0.3f, 0.5f)
        scene.addChild(volume)
        volume.addTimepoint("t-0", snapshot.electronicDensityUInt)
        volume.goToLastTimepoint()


        // One light in every corner.
        val lights = (0 until 8).map {
            PointLight(radius = 15.0f)
        }
        lights.mapIndexed { i, light ->
            val permutation = String.format("%3s", Integer.toBinaryString(i)).replace(' ', '0')
            light.position = Vector3f(6.0f * (permutation[0].code-48) ,  6.0f * (permutation[1].code-48),
                6.0f * (permutation[2].code-48))
            light.emissionColor = Vector3f(1.0f, 1.0f, 1.0f)
            light.intensity = 1.0f
            scene.addChild(light)
        }

        val cam: Camera = DetachedHeadCamera()
        with(cam) {
            position = Vector3f(0.0f, 0.0f, 5.0f)
            perspectiveCamera(50.0f, 512, 512)

            scene.addChild(this)
        }

        thread {
            while (running) {

                Thread.sleep(20)
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            DFTExample().main()
        }
    }
}

