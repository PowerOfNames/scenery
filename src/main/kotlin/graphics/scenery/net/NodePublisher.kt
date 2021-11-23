package graphics.scenery.net

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.Input
import com.esotericsoftware.kryo.io.Output
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import de.javakaffee.kryoserializers.UUIDSerializer
import graphics.scenery.*
import graphics.scenery.serialization.*
import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.Statistics
import graphics.scenery.volumes.BufferedVolume
import graphics.scenery.volumes.RAIVolume
import graphics.scenery.volumes.Volume
import graphics.scenery.volumes.VolumeManager
import net.imglib2.img.basictypeaccess.array.ByteArray
import org.joml.Vector3f
import org.objenesis.strategy.StdInstantiatorStrategy
import org.slf4j.Logger
import org.zeromq.ZContext
import org.zeromq.ZMQ
import org.zeromq.ZMQException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import kotlin.concurrent.thread

/**
 * Created by ulrik on 4/4/2017.
 */
class NodePublisher(
    override var hub: Hub?,
    //ip: String = "tcp://127.0.0.1",
    ip: String = "tcp://localhost",
    portPublish: Int = 7777,
    portControl: Int = 6666,
    val context: ZContext = ZContext(4)
): Hubable {
    private val logger by LazyLogger()

    private val addressPublish = "$ip:$portPublish"
    private val addressControl = "$ip:$portControl"
    //private val addressControl = "tcp://*:5560" //"$ip:$portControl"

    //private var publishedAt = ConcurrentHashMap<Int, Long>()
    //var nodes: ConcurrentHashMap<Int, Node> = ConcurrentHashMap()
    var nodes: ConcurrentHashMap<Int, Node> = ConcurrentHashMap() //TODO delete

    private val eventQueueTimeout = 500L
    private val publisher: ZMQ.Socket = context.createSocket(ZMQ.PUB)
    var portPublish: Int = try {
        publisher.bind(addressPublish)
        addressPublish.substringAfterLast(":").toInt()
    } catch (e: ZMQException) {
        logger.warn("Binding failed, trying random port: $e")
        publisher.bindToRandomPort(addressPublish.substringBeforeLast(":"))
    }

    private val controlSubscriber: ZMQ.Socket = context.createSocket(ZMQ.SUB)
    var portControl: Int = try {
        controlSubscriber.bind(addressControl)
        addressControl.substringAfterLast(":").toInt()
    } catch (e: ZMQException) {
        logger.warn("Binding failed, trying random port: $e")
        controlSubscriber.bindToRandomPort(addressControl.substringBeforeLast(":"))
    }

    val kryo = freeze()
    private val publishedObjects = ConcurrentHashMap<Int, NetworkObject<*>>()
    private val eventQueue = LinkedBlockingQueue<NetworkEvent>()
    private var index = 1

    private var publishing = false
    private var listeningForControl = false

    private fun generateNetworkID() = index++

    init {
        controlSubscriber.subscribe(ZMQ.SUBSCRIPTION_ALL)
    }

    fun register(scene: Scene){
        val sceneNo = NetworkObject(generateNetworkID(),scene, mutableListOf())
        publishedObjects[sceneNo.networkID] = sceneNo
        eventQueue.add(NetworkEvent.Update(sceneNo))

        // TODO relation updates
        //scene.onChildrenAdded["networkPublish"] = {_, child -> registerNode(child)}
        //scene.onChildrenRemoved["networkPublish"] = {_, child -> removeNode(child)}

        // abusing the discover function for a tree walk
        scene.discover(scene,{registerNode(it); false})
    }

    fun registerNode(node:Node){
        if (!node.wantsSync()){
            return
        }
        if (node.parent == null){
            throw IllegalArgumentException("Node not part of scene graph and cant be synchronized alone")
        }
        val parentId = node.parent?.networkID
        if (parentId == null || publishedObjects[parentId] == null){
            throw IllegalArgumentException("Node Parent not registered with publisher.")
        }

        if (publishedObjects[node.networkID] == null) {
            val netObject = NetworkObject(generateNetworkID(), node, mutableListOf(parentId))
            eventQueue.add(NetworkEvent.Update(netObject))
            publishedObjects[netObject.networkID] = netObject
        }

        node.getSubcomponents().forEach { subComponent ->
            val subNetObj = publishedObjects[subComponent.networkID]
            if (subNetObj != null) {
                subNetObj.parents.add(node.networkID)
                eventQueue.add(NetworkEvent.NewRelation(node.networkID, subComponent.networkID))
            } else {
                val new = NetworkObject(generateNetworkID(), subComponent, mutableListOf(node.networkID))
                publishedObjects[new.networkID] = new
                eventQueue.add(NetworkEvent.Update(new))
            }
        }
    }

    fun removeNode(node: Node){
        //TODO
    }

    /**
     * Should be called in the update phase of the life cycle
     */
    fun scanForChanges(){
        for (it in publishedObjects.values) {
            if (it.obj.lastChange() >= it.publishedAt) {
                it.publishedAt = System.nanoTime()
                eventQueue.add(NetworkEvent.Update(it))
            }
        }
    }

    fun startPublishing(){
        publishing = true
        thread {
            while (publishing){
                val event = eventQueue.poll(eventQueueTimeout, TimeUnit.MILLISECONDS) ?: continue
                if (!publishing) break // in case of shutdown while polling
                if (event is NetworkEvent.RequestInitialization){
                    publishedObjects.forEach{
                        eventQueue.add(NetworkEvent.Update(it.value))
                    }
                }
                publishEvent(event)
            }
        }
        startListeningControl()
    }

    fun startListeningControl() {
        listeningForControl = true
        controlSubscriber.receiveTimeOut = 0
        thread {
            while (listeningForControl) {
                try {
                    var payload: kotlin.ByteArray? = controlSubscriber.recv()
                    while (payload != null && listeningForControl) {
                        try {
                            val bin = ByteArrayInputStream(payload)
                            val input = Input(bin)
                            val event = kryo.readClassAndObject(input) as? NetworkEvent
                                ?: throw IllegalStateException("Received unknown, not NetworkEvent payload")
                            eventQueue.add(event)
                            payload = controlSubscriber.recv()

                        } catch (t: Throwable) {
                            print(t)
                        }
                    }
                } catch (t: Throwable) {
                    print(t)
                }
                Thread.sleep(50)
            }
        }
    }

    fun debugPublish(send: (NetworkEvent) -> Unit){
        while(eventQueue.isNotEmpty()){
            send(eventQueue.poll())
        }
    }

    fun stopPublishing(waitForFinishOfPublishing: Boolean) {
        publishing = false
        if(waitForFinishOfPublishing){
            Thread.sleep(eventQueueTimeout*2)
        }
    }

    fun stopListening(waitForIt: Boolean){
        listeningForControl = false
        if (waitForIt){
            Thread.sleep(100)
        }
    }

    private fun publishEvent(event: NetworkEvent){
        val start = System.nanoTime()
        val payloadSize = sendEvent(event, kryo, publisher, logger)
        val duration = (System.nanoTime() - start).toFloat()
        (hub?.get(SceneryElement.Statistics) as? Statistics)?.add("Serialise.duration", duration)
        (hub?.get(SceneryElement.Statistics) as? Statistics)?.add("Serialise.payloadSize", payloadSize, isTime = false)
    }

    fun close(waitForFinishOfPublishing: Boolean = false) {
        stopPublishing(waitForFinishOfPublishing)
        stopListening(true)
        context.destroySocket(publisher)
        context.close()
    }

    companion object {
        fun sendEvent(event: NetworkEvent, kryo: Kryo, socket: ZMQ.Socket, logger: Logger): Long {
            var payloadSize = 0L
            try {
                val bos = ByteArrayOutputStream()
                val output = Output(bos)
                kryo.writeClassAndObject(output, event)
                output.flush()

                val payload = bos.toByteArray()
                socket.send(payload)
                Thread.sleep(1)
                payloadSize = payload.size.toLong()

                output.close()
                bos.close()
            } catch (e: IOException) {
                logger.warn("Error in publishing: ${event.javaClass.name}", e)
            } catch (e: AssertionError) {
                logger.warn("Error in publishing: ${event.javaClass.name}", e)
            } catch (e: Throwable) {
                print(e)
            }
            return payloadSize
        }

        fun freeze(): Kryo {
            val kryo = Kryo()
            kryo.instantiatorStrategy = DefaultInstantiatorStrategy(StdInstantiatorStrategy())
            kryo.isRegistrationRequired = false
            kryo.references = true
            kryo.setCopyReferences(true)
            kryo.register(UUID::class.java, UUIDSerializer())
            kryo.register(OrientedBoundingBox::class.java, OrientedBoundingBoxSerializer())
            kryo.register(Triple::class.java, TripleSerializer())
            kryo.register(ByteBuffer::class.java, ByteBufferSerializer())

            // A little trick here, because DirectByteBuffer is package-private
            val tmp = ByteBuffer.allocateDirect(1)
            kryo.register(tmp.javaClass, ByteBufferSerializer())
            kryo.register(ByteArray::class.java, Imglib2ByteArraySerializer())
            kryo.register(ShaderMaterial::class.java, ShaderMaterialSerializer())
            kryo.register(java.util.zip.Inflater::class.java, IgnoreSerializer<Inflater>())
            kryo.register(VolumeManager::class.java, IgnoreSerializer<VolumeManager>())
            kryo.register(Vector3f::class.java, Vector3fSerializer())

            kryo.register(Volume::class.java, VolumeSerializer())
            kryo.register(RAIVolume::class.java, VolumeSerializer())
            kryo.register(BufferedVolume::class.java, VolumeSerializer())

            return kryo
        }

    }

}
