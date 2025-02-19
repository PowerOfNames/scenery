package graphics.scenery.net

import com.esotericsoftware.kryo.io.Input
import graphics.scenery.Hub
import graphics.scenery.Hubable
import graphics.scenery.Node
import graphics.scenery.Scene
import graphics.scenery.utils.LazyLogger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.io.ByteArrayInputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread


/**
 * Client of scenery networking.
 *
 * @author Jan Tiemann <j.tiemann@hzdr.de>
 */
class NodeSubscriber(
    override var hub: Hub?,
    ip: String = "tcp://localhost",
    portPublish: Int = 7777,
    portBackchannel: Int = 6666,
    val context: ZContext = ZContext(4),
    startNetworkActivity: Boolean = true
) : Hubable {
    private val logger by LazyLogger()
    val kryo = NodePublisher.freeze()

    private val addressSubscribe = "$ip:$portPublish"
    private val addressBackchannel = "$ip:$portBackchannel"
    var subscriber: ZMQ.Socket = context.createSocket(SocketType.SUB)
    var backchannel: ZMQ.Socket = context.createSocket(SocketType.PUB)

    private val networkObjects = hashMapOf<Int, NetworkWrapper<*>>()
    /** This is the hand-of point between the network thread and update/main-loop thread */
    private val eventQueue = LinkedBlockingQueue<NetworkEvent>()
    private val waitingOnNetworkable = mutableMapOf<Int, List<Pair<NetworkEvent, WaitReason>>>()
    private var listening = false

    init {
        if (startNetworkActivity) {
            if (subscriber.connect(addressSubscribe)) {
                logger.info("Client connected to main channel at $addressSubscribe")
            }
            subscriber.subscribe(ZMQ.SUBSCRIPTION_ALL)
            if (backchannel.connect(addressBackchannel)) {
                logger.info("Client connected to back channel at $addressBackchannel")
            }
            GlobalScope.launch {
                delay(1000)
                NodePublisher.sendEvent(NetworkEvent.RequestInitialization, kryo, backchannel, logger)
            }
        }
    }

    /**
     * Starts the listening thread.
     */
    internal fun startListening() {
        listening = true
        subscriber.receiveTimeOut = 100
        thread {
            while (listening) {
                try {
                    val payload: ByteArray = subscriber.recv() ?: continue

                    val bin = ByteArrayInputStream(payload)
                    val input = Input(bin)
                    val event = kryo.readClassAndObject(input) as? NetworkEvent
                        ?: throw IllegalStateException("Received unknown, not NetworkEvent payload")
                    eventQueue.add(event)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }
    }

    /**
     * Stops the listening thread.
     */
    internal fun stopListening() {
        listening = false
    }

    /**
     * Used in Unit test
     */
    private fun debugListen(event: NetworkEvent) {
        eventQueue.add(event)
    }

    class NetworkableNotFoundException(val id: Int) : IllegalStateException()

    private fun getNetworkable(id: Int): Networkable {
        return networkObjects[id]?.obj ?: throw NetworkableNotFoundException(id)
    }

    /**
     * Should be called in update life cycle
     */
    fun networkUpdate(scene: Scene) {
        while (!eventQueue.isEmpty()) {
            when (val event = eventQueue.poll()) {
                is NetworkEvent.Update -> {
                    processUpdateEvent(event, scene)
                }
                is NetworkEvent.NewRelation -> {
                    processNewRelationEvent(event)
                }
                NetworkEvent.RequestInitialization -> {} // should not arrive at subscriber
            }
        }
    }

    private fun processNewRelationEvent(event: NetworkEvent.NewRelation) {
        val parent = event.parent?.let { networkObjects[it] }?.obj as? Node
        if (event.parent != null && parent == null) {
            waitingOnNetworkable.getOrDefault(event.parent, listOf()) + (event to WaitReason.UpdateRelation)
            return
        }
        val childWrapper = networkObjects[event.child]
        if (childWrapper == null) {
            waitingOnNetworkable.getOrDefault(event.child, listOf()) + (event to WaitReason.UpdateRelation)
            return
        }
        when (val child = childWrapper.obj) {
            is Node -> {
                if (parent == null) {
                    child.parent?.removeChild(child)
                } else {
                    parent.addChild(child)
                }
            }
            else -> {
                // Attribute
                parent?.addAttributeFromNetwork(child.getAttributeClass()!!.java, child)
            }
        }
    }

    private fun processUpdateEvent(event: NetworkEvent.Update, scene: Scene) {
        val networkWrapper = event.wrapper

        // ---------- update -------------
        // The object exists on this client -> we only need to update it
        networkObjects[networkWrapper.networkID]?.let {
            val fresh = networkWrapper.obj
            val tmp = it.obj
            try {
                tmp.update(fresh, this::getNetworkable, event.additionalData)
            } catch (e: NetworkableNotFoundException) {
                waitingOnNetworkable[e.id] =
                    waitingOnNetworkable.getOrDefault(e.id, listOf()) + (event to WaitReason.UpdateRelation)
            }
            return
        }

        // ------------ new object ----------
        // The object does not exist on the client -> we need to add it
        var networkable = networkWrapper.obj
        when (networkable) {
            is Scene -> {
                // dont use the scene from network, but adapt own scene
                scene.networkID = networkable.networkID
                try {
                    scene.update(networkable, this::getNetworkable, event.additionalData)
                } catch (e: NetworkableNotFoundException) {
                    logger.warn("Waiting on related Network Object in scene update. This is likely an invalid, irremediable state.")
                    waitingOnNetworkable[e.id] =
                        waitingOnNetworkable.getOrDefault(e.id, listOf()) + (event to WaitReason.Parent)
                }
                networkObjects[networkWrapper.networkID] =
                    NetworkWrapper(networkWrapper.networkID, scene, mutableListOf())
                networkable = scene
            }
            is Node -> {
                val parentId = networkWrapper.parents.first() // nodes have only one parent
                val parent = networkObjects[parentId]?.obj as? Node
                if (parent == null) {
                    waitingOnNetworkable[parentId] =
                        waitingOnNetworkable.getOrDefault(parentId, listOf()) + (event to WaitReason.Parent)
                    return
                }

                val newNode = event.constructorParameters?.let { networkable.constructWithParameters(it, hub!!) as Node
                    }?: networkable
                val newWrapped = NetworkWrapper(
                    networkWrapper.networkID,
                    newNode,
                    networkWrapper.parents,
                    networkWrapper.publishedAt
                )

                networkObjects[networkWrapper.networkID] = newWrapped
                // update relations (and other values if created client site with constructor params)
                processUpdateEvent(event,scene)

                parent.addChild(newNode)
                networkable = newNode
            }
            else -> {
                // It is an attribute
                val attributeBaseClass = networkable.getAttributeClass()
                    ?: throw IllegalStateException(
                        "Received unknown object from server. ${networkable.javaClass.simpleName}" +
                            "Maybe an attribute missing a getAttributeClass implementation?"
                    )

                val newAttribute = event.constructorParameters?.let { networkable.constructWithParameters(it, hub!!) }
                    ?: networkable
                val newWrapped = NetworkWrapper(
                    networkWrapper.networkID,
                    newAttribute,
                    networkWrapper.parents,
                    networkWrapper.publishedAt
                )

                networkObjects[networkWrapper.networkID] = newWrapped
                // update relations (and other values if created client site with constructor params)
                processUpdateEvent(event,scene)
                networkable = newAttribute

                networkWrapper.parents
                    .mapNotNull { parentId ->
                        val parent = networkObjects[parentId]?.obj as? Node
                        if (parent == null) {
                            waitingOnNetworkable[parentId] =
                                waitingOnNetworkable.getOrDefault(
                                    parentId,
                                    listOf()
                                ) + (event.copy(wrapper = newWrapped) to WaitReason.Parent)
                            null
                        } else {
                            parent
                        }
                    }
                    .forEach { parent ->
                        parent.addAttributeFromNetwork(attributeBaseClass.java, newAttribute)
                    }
            }
        }
        processWaitingNodes(networkable, scene)
    }

    private fun processWaitingNodes(parent: Networkable, scene: Scene) {
        val missingChildren = waitingOnNetworkable.remove(parent.networkID)
        missingChildren?.forEach { childEvent ->
            val reason = childEvent.second
            val event = childEvent.first
            when{
                reason == WaitReason.UpdateRelation && event is NetworkEvent.Update ->
                    processUpdateEvent(event, scene)
                reason == WaitReason.Parent && event is NetworkEvent.NewRelation ->
                    processNewRelationEvent(event)
                reason == WaitReason.Parent && event is NetworkEvent.Update && event.wrapper.obj is Node ->
                    processUpdateEvent(event, scene)
                reason == WaitReason.Parent && event is NetworkEvent.Update -> {
                    // this should be an attribute
                    val child = (event).wrapper.obj
                    val attributeBaseClass = child.getAttributeClass()!!
                    // before adding the attribute to the waitingOnParent list this was null checked
                    (parent as? Node)?.addAttributeFromNetwork(attributeBaseClass.java, child)
                }
            }
        }
    }

    fun close() {
        stopListening()
        context.destroySocket(subscriber)
        context.destroySocket(backchannel)
        context.close()
    }

    private enum class WaitReason {
        // Parent is missing
        Parent,

        // A related Network Object required in the update method is missing
        UpdateRelation
    }
}
