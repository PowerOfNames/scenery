package graphics.scenery.tests.unit.network

import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import graphics.scenery.Box
import graphics.scenery.Hub
import graphics.scenery.Scene
import graphics.scenery.Sphere
import graphics.scenery.attribute.material.Material
import graphics.scenery.attribute.spatial.Spatial
import graphics.scenery.net.NetworkEvent
import graphics.scenery.net.NetworkWrapper
import graphics.scenery.net.NodePublisher
import org.joml.Vector3f
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NodePublisherTest {

    @Test
    fun initialSceneDiscovery() {
        val hub = Hub()
        val pub = NodePublisher(hub)
        pub.close()

        val scene = Scene()
        val box = Box()
        val sphere = Sphere()

        scene.addChild(box)
        box.addChild(sphere)

        pub.register(scene)

        val results = mutableListOf<NetworkEvent>()

        pub.debugPublish { results.add(it) }

        val newEvents = results.filterIsInstance<NetworkEvent.Update>().toList()
        //val relationEvents = results.filterIsInstance<NetworkEvent.NewRelation>().toList()

        assert(newEvents.any{it.wrapper.obj is Scene})
        assert(newEvents.any{it.wrapper.obj is Box})
        assert(newEvents.any{it.wrapper.obj is Sphere})
        assert(newEvents.count{it.wrapper.obj is Material} == 2)
        assert(newEvents.count{it.wrapper.obj is Spatial} == 2)
    }

    @Test
    fun registerUpdate() {
        val hub = Hub()
        val pub = NodePublisher(hub)
        pub.close()

        val scene = Scene()
        val box = Box()

        scene.addChild(box)

        pub.register(scene)
        pub.scanForChanges()

        val results2 = mutableListOf<NetworkEvent>()
        pub.debugPublish { results2.add(it) }
        //pub.debugPublish {  } // clear event queue
        box.spatial().position = Vector3f(0f,0f,3f)

        pub.scanForChanges()

        val results = mutableListOf<NetworkEvent>()
        pub.debugPublish { results.add(it) }

        assertEquals(1, results.size)
        val event = results[0] as? NetworkEvent.Update
        assertNotNull(event)
        assertEquals(3f,(event.wrapper.obj as? Spatial)?.position?.z)
    }

    @Test
    fun serializeScene() {

        val scene = Scene()
        scene.name = "lol"

        val kryo = NodePublisher.freeze()
        val bos = ByteArrayOutputStream()
        val output = Output(bos)
        kryo.writeClassAndObject(output, NetworkEvent.Update(NetworkWrapper(2, scene, mutableListOf(1))))
        output.flush()

        val bin = ByteArrayInputStream(bos.toByteArray())
        val input = Input(bin)
        val event = kryo.readClassAndObject(input) as NetworkEvent.Update

        assertEquals("lol", (event.wrapper.obj as Scene).name)
    }
}
