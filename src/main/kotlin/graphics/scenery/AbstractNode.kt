package graphics.scenery

import graphics.scenery.utils.LazyLogger
import graphics.scenery.utils.MaybeIntersects
import graphics.scenery.utils.extensions.max
import graphics.scenery.utils.extensions.minus
import graphics.scenery.utils.extensions.plus
import graphics.scenery.utils.extensions.times
import graphics.scenery.utils.extensions.xyz
import graphics.scenery.utils.extensions.xyzw
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.imglib2.Localizable
import net.imglib2.RealLocalizable
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector4f
import java.sql.Timestamp
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates
import kotlin.reflect.KProperty

abstract class AbstractNode(override var name: String) : Node {

    @Transient override var children = CopyOnWriteArrayList<Node>()
    @Transient override var linkedNodes = CopyOnWriteArrayList<Node>()
    @Transient override var metadata: HashMap<String, Any> = HashMap()
    override var parent: Node? = null
    override var createdAt = (Timestamp(Date().time).time)
    override var modifiedAt = 0L
    override var wantsComposeModel = true
    override var needsUpdate = true
    override var needsUpdateWorld = true
    override var discoveryBarrier = false
    override var boundingBox: OrientedBoundingBox? = null
    override var update: ArrayList<() -> Unit> = ArrayList()
    override var visible: Boolean = true
        set(v) {
            children.forEach { it.visible = v }
            field = v
        }
    override var world: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var iworld: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var model: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var imodel: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var view: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var iview: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var projection: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var iprojection: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var modelView: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var imodelView: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var mvp: Matrix4f by Delegates.observable(Matrix4f().identity()) { property, old, new -> propertyChanged(property, old, new) }
    override var scale: Vector3f by Delegates.observable(Vector3f(1.0f, 1.0f, 1.0f)) { property, old, new -> propertyChanged(property, old, new) }
    override var rotation: Quaternionf by Delegates.observable(Quaternionf(0.0f, 0.0f, 0.0f, 1.0f)) { property, old, new -> propertyChanged(property, old, new) }
    override var position: Vector3f by Delegates.observable(Vector3f(0.0f, 0.0f, 0.0f)) { property, old, new -> propertyChanged(property, old, new) }
    override var material: Material
        set(m) {
            renderable()?.material = m
            renderable()?.needsUpdate = true
            renderable()?.dirty = true
        }
        get() {
            return renderable()!!.material
        }
    override var nodeType = "Node"

    override val logger by LazyLogger()

    @Suppress("UNUSED_PARAMETER")
    protected fun <R> propertyChanged(property: KProperty<*>, old: R, new: R, custom: String = "") {
        if(property.name == "rotation"
            || property.name == "position"
            || property.name  == "scale"
            || property.name == custom) {
            needsUpdate = true
            needsUpdateWorld = true
        }
    }

    private var uuid: UUID = UUID.randomUUID()

    override fun getUuid(): UUID {
        return uuid
    }

    override fun addChild(child: Node) {
        child.parent = this
        this.children.add(child)

        val scene = this.getScene() ?: return
        scene.sceneSize.incrementAndGet()
        if(scene.onChildrenAdded.isNotEmpty()) {
            GlobalScope.launch {
                scene.onChildrenAdded.forEach { it.value.invoke(this@AbstractNode, child) }
            }
        }
    }

    override fun removeChild(child: Node): Boolean {
        this.getScene()?.sceneSize?.decrementAndGet()
        GlobalScope.launch { this@AbstractNode.getScene()?.onChildrenRemoved?.forEach { it.value.invoke(this@AbstractNode, child) } }

        return this.children.remove(child)
    }

    override fun removeChild(name: String): Boolean {
        for (c in this.children) {
            if (c.name.compareTo(name) == 0) {
                c.parent = null
                this.children.remove(c)
                return true
            }
        }

        return false
    }

    override fun getChildrenByName(name: String): List<Node> {
        return children.filter { it.name == name }
    }

    override fun generateBoundingBox(): OrientedBoundingBox? {
        if (this is HasGeometry) {
            val vertexBufferView = vertices.asReadOnlyBuffer()
            val boundingBoxCoords = floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)

            if (vertexBufferView.capacity() == 0 || vertexBufferView.remaining() == 0) {
                boundingBox = if(!children.none()) {
                    getMaximumBoundingBox()
                } else {
                    logger.warn("$name: Zero vertices currently, returning empty bounding box")
                    OrientedBoundingBox(this,0.0f, 0.0f, 0.0f,
                        0.0f, 0.0f, 0.0f)
                }

                return boundingBox
            } else {

                val vertex = floatArrayOf(0.0f, 0.0f, 0.0f)
                vertexBufferView.get(vertex)

                boundingBoxCoords[0] = vertex[0]
                boundingBoxCoords[1] = vertex[0]

                boundingBoxCoords[2] = vertex[1]
                boundingBoxCoords[3] = vertex[1]

                boundingBoxCoords[4] = vertex[2]
                boundingBoxCoords[5] = vertex[2]

                while(vertexBufferView.remaining() >= 3) {
                    vertexBufferView.get(vertex)

                    boundingBoxCoords[0] = minOf(boundingBoxCoords[0], vertex[0])
                    boundingBoxCoords[2] = minOf(boundingBoxCoords[2], vertex[1])
                    boundingBoxCoords[4] = minOf(boundingBoxCoords[4], vertex[2])

                    boundingBoxCoords[1] = maxOf(boundingBoxCoords[1], vertex[0])
                    boundingBoxCoords[3] = maxOf(boundingBoxCoords[3], vertex[1])
                    boundingBoxCoords[5] = maxOf(boundingBoxCoords[5], vertex[2])
                }

                logger.debug("$name: Calculated bounding box with ${boundingBoxCoords.joinToString(", ")}")
                return OrientedBoundingBox(this, Vector3f(boundingBoxCoords[0], boundingBoxCoords[2], boundingBoxCoords[4]),
                    Vector3f(boundingBoxCoords[1], boundingBoxCoords[3], boundingBoxCoords[5])
                )
            }
        } else {
            logger.warn("$name: Assuming 3rd party BB generation")
            return boundingBox
        }
    }

    override fun getScene(): Scene? {
        var p: Node? = this
        while(p !is Scene && p != null) {
            p = p.parent
        }
        return p as? Scene
    }


    @Synchronized override fun updateWorld(recursive: Boolean, force: Boolean) {
        update.forEach { it.invoke() }

        if ((needsUpdate or force)) {
            if(wantsComposeModel) {
                this.composeModel()
            }

            needsUpdate = false
            needsUpdateWorld = true
        }

        if (needsUpdateWorld or force) {
            val p = parent?.renderable()
            if (p == null || p is Scene) {
                world.set(model)
            } else {
                world.set(p.world)
                world.mul(this.model)
            }
        }

        if (recursive) {
            this.children.forEach { it.updateWorld(true, needsUpdateWorld) }
            // also update linked nodes -- they might need updated
            // model/view/proj matrices as well
            this.linkedNodes.forEach { it.updateWorld(true, needsUpdateWorld) }
        }

        if(needsUpdateWorld) {
            needsUpdateWorld = false
        }
    }

    /**
     * This method composes the [model] matrices of the node from its
     * [position], [scale] and [rotation].
     */
    open fun composeModel() {
        @Suppress("SENSELESS_COMPARISON")
        if(position != null && rotation != null && scale != null) {
            model.translationRotateScale(
                Vector3f(position.x(), position.y(), position.z()),
                this.rotation,
                Vector3f(this.scale.x(), this.scale.y(), this.scale.z()))
        }
    }

    override fun centerOn(position: Vector3f): Vector3f {
        val min = getMaximumBoundingBox().min
        val max = getMaximumBoundingBox().max

        val center = (max - min) * 0.5f
        this.position = position - (getMaximumBoundingBox().min + center)

        return center
    }

    override fun putAbove(position: Vector3f): Vector3f {
        val center = centerOn(position)

        val diffY = center.y() + position.y()
        val diff = Vector3f(0.0f, diffY, 0.0f)
        this.position = this.position + diff

        return diff
    }

    override fun fitInto(sideLength: Float, scaleUp: Boolean): Vector3f {
        val min = getMaximumBoundingBox().min.xyzw()
        val max = getMaximumBoundingBox().max.xyzw()

        val maxDimension = (max - min).max()
        val scaling = sideLength/maxDimension

        if((scaleUp && scaling > 1.0f) || scaling <= 1.0f) {
            this.scale = Vector3f(scaling, scaling, scaling)
        } else {
            this.scale = Vector3f(1.0f, 1.0f, 1.0f)
        }

        return this.scale
    }

    /**
     * Orients the Node between points [p1] and [p2], and optionally
     * [rescale]s and [reposition]s it.
     */
    @JvmOverloads fun orientBetweenPoints(p1: Vector3f, p2: Vector3f, rescale: Boolean = false, reposition: Boolean = false): Quaternionf {
        val direction = p2 - p1
        val length = direction.length()

        this.rotation = Quaternionf().rotationTo(Vector3f(0.0f, 1.0f, 0.0f), direction.normalize())
        if(rescale) {
            this.scale = Vector3f(1.0f, length, 1.0f)
        }

        if(reposition) {
            this.position = Vector3f(p1)
        }

        return this.rotation
    }

    override fun getMaximumBoundingBox(): OrientedBoundingBox {
        if(boundingBox == null && children.size == 0) {
            return OrientedBoundingBox(this,0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
        }

        if(children.none { it !is BoundingGrid }) {
            return OrientedBoundingBox(this,boundingBox?.min ?: Vector3f(0.0f, 0.0f, 0.0f), boundingBox?.max ?: Vector3f(0.0f, 0.0f, 0.0f))
        }

        return children
            .filter { it !is BoundingGrid  }.map { it.getMaximumBoundingBox().translate(it.position) }
            .fold(boundingBox ?: OrientedBoundingBox(this, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f), { lhs, rhs -> lhs.expand(lhs, rhs) })
    }

    override fun intersects(other: Node): Boolean {
        boundingBox?.let { ownOBB ->
            other.boundingBox?.let { otherOBB ->
                return ownOBB.intersects(otherOBB)
            }
        }

        return false
    }

    override fun worldPosition(v: Vector3f?): Vector3f {
        val target = v ?: position
        return if(parent is Scene && v == null) {
            Vector3f(target)
        } else {
            world.transform(Vector4f().set(target, 1.0f)).xyz()
        }
    }

    /**
     * Performs a intersection test with an axis-aligned bounding box of this [Node], where
     * the test ray originates at [origin] and points into [dir].
     *
     * Returns a Pair of Boolean and Float, indicating whether an intersection is possible,
     * and at which distance.
     *
     * Code adapted from [zachamarz](http://gamedev.stackexchange.com/a/18459).
     */
    override fun intersectAABB(origin: Vector3f, dir: Vector3f): MaybeIntersects {
        val bbmin = getMaximumBoundingBox().min.xyzw()
        val bbmax = getMaximumBoundingBox().max.xyzw()

        val min = world.transform(bbmin)
        val max = world.transform(bbmax)

        // skip if inside the bounding box
        if(origin.isInside(min.xyz(), max.xyz())) {
            return MaybeIntersects.NoIntersection()
        }

        val invDir = Vector3f(1 / (dir.x() + Float.MIN_VALUE), 1 / (dir.y() + Float.MIN_VALUE), 1 / (dir.z() + Float.MIN_VALUE))

        val t1 = (min.x() - origin.x()) * invDir.x()
        val t2 = (max.x() - origin.x()) * invDir.x()
        val t3 = (min.y() - origin.y()) * invDir.y()
        val t4 = (max.y() - origin.y()) * invDir.y()
        val t5 = (min.z() - origin.z()) * invDir.z()
        val t6 = (max.z() - origin.z()) * invDir.z()

        val tmin = max(max(min(t1, t2), min(t3, t4)), min(t5, t6))
        val tmax = min(min(max(t1, t2), max(t3, t4)), max(t5, t6))

        // we are in front of the AABB
        if (tmax < 0) {
            return MaybeIntersects.NoIntersection()
        }

        // we have missed the AABB
        if (tmin > tmax) {
            return MaybeIntersects.NoIntersection()
        }

        // we have a match! calculate entry and exit points
        val entry = origin + dir * tmin
        val exit = origin + dir * tmax
        val localEntry = Matrix4f(world).invert().transform(Vector4f().set(entry, 1.0f))
        val localExit = Matrix4f(world).invert().transform(Vector4f().set(exit, 1.0f))

        return MaybeIntersects.Intersection(tmin, entry, exit, localEntry.xyz(), localExit.xyz())
    }

    private fun Vector3f.isInside(min: Vector3f, max: Vector3f): Boolean {
        return this.x() > min.x() && this.x() < max.x()
            && this.y() > min.y() && this.y() < max.y()
            && this.z() > min.z() && this.z() < max.z()
    }

    override fun runRecursive(func: (Node) -> Unit) {
        func.invoke(this)

        children.forEach { it.runRecursive(func) }
    }

    override fun runRecursive(func: Consumer<Node>) {
        func.accept(this)

        children.forEach { it.runRecursive(func) }
    }

    override fun localize(position: FloatArray?) {
        position?.set(0, this.position.x())
        position?.set(1, this.position.y())
        position?.set(2, this.position.z())
    }

    override fun localize(position: DoubleArray?) {
        position?.set(0, this.position.x().toDouble())
        position?.set(1, this.position.y().toDouble())
        position?.set(2, this.position.z().toDouble())
    }

    override fun getFloatPosition(d: Int): Float {
        return this.position[d]
    }

    override fun toString(): String {
        return "$name(${javaClass.simpleName})"
    }


    override fun bck(d: Int) {
        move(-1, d)
    }

    override fun move(distance: Float, d: Int) {
        setPosition( getFloatPosition(d) + distance, d )
    }

    override fun move(distance: Double, d: Int) {
        setPosition( getDoublePosition(d) + distance, d )
    }

    override fun move(distance: RealLocalizable?) {
        distance?.getDoublePosition(0)?.let { move(it, 0) }
        distance?.getDoublePosition(1)?.let { move(it, 1) }
        distance?.getDoublePosition(2)?.let { move(it, 2) }
    }

    override fun move(distance: FloatArray?) {
        distance?.get(0)?.let { move(it, 0 ) }
        distance?.get(1)?.let { move(it, 1 ) }
        distance?.get(2)?.let { move(it, 2 ) }
    }

    override fun move(distance: DoubleArray?) {
        distance?.get(0)?.let { move(it, 0 ) }
        distance?.get(1)?.let { move(it, 1 ) }
        distance?.get(2)?.let { move(it, 2 ) }
    }

    override fun move(distance: Int, d: Int) {
        move( distance.toLong(), d )
    }

    override fun move(distance: Long, d: Int) {
        this.position = this.position + Vector3f().setComponent(d, distance.toFloat())
    }

    override fun move(distance: Localizable?) {
        distance?.getDoublePosition(0)?.let { move(it, 0) }
        distance?.getDoublePosition(1)?.let { move(it, 1) }
        distance?.getDoublePosition(2)?.let { move(it, 2) }
    }

    override fun move(distance: IntArray?) {
        distance?.get(0)?.let { move(it, 0 ) }
        distance?.get(1)?.let { move(it, 1 ) }
        distance?.get(2)?.let { move(it, 2 ) }
    }

    override fun move(distance: LongArray?) {
        distance?.get(0)?.let { move(it, 0 ) }
        distance?.get(1)?.let { move(it, 1 ) }
        distance?.get(2)?.let { move(it, 2 ) }
    }

    override fun numDimensions(): Int {
        return 3
    }

    override fun fwd(d: Int) {
        move( 1, d)
    }

    override fun getDoublePosition(d: Int): Double {
        return this.position[d].toDouble()
    }

    override fun setPosition(pos: RealLocalizable) {
        position.setComponent( 0, pos.getFloatPosition(0) )
        position.setComponent( 1, pos.getFloatPosition(1) )
        position.setComponent( 2, pos.getFloatPosition(2) )
    }

    override fun setPosition(pos: FloatArray?) {
        pos?.get(0)?.let { setPosition(it, 0 ) }
        pos?.get(1)?.let { setPosition(it, 1 ) }
        pos?.get(2)?.let { setPosition(it, 2 ) }
    }

    override fun setPosition(pos: DoubleArray?) {
        pos?.get(0)?.let { setPosition(it, 0 ) }
        pos?.get(1)?.let { setPosition(it, 1 ) }
        pos?.get(2)?.let { setPosition(it, 2 ) }
    }

    override fun setPosition(pos: Float, d: Int) {
        position.setComponent( d, pos )
    }

    override fun setPosition(pos: Double, d: Int) {
        setPosition( pos.toFloat(), d )
    }

    override fun setPosition(pos: Localizable?) {
        pos?.getIntPosition(0)?.let { setPosition(it, 0 ) }
        pos?.getIntPosition(1)?.let { setPosition(it, 1 ) }
        pos?.getIntPosition(2)?.let { setPosition(it, 2 ) }
    }

    override fun setPosition(pos: IntArray?) {
        pos?.get(0)?.let { setPosition(it, 0) }
        pos?.get(1)?.let { setPosition(it, 1) }
        pos?.get(2)?.let { setPosition(it, 2) }
    }

    override fun setPosition(pos: LongArray?) {
        pos?.get(0)?.let { setPosition(it, 0) }
        pos?.get(1)?.let { setPosition(it, 1) }
        pos?.get(2)?.let { setPosition(it, 2) }
    }

    override fun setPosition(position: Int, d: Int) {
        setPosition(position.toLong(), d)
    }

    override fun setPosition(position: Long, d: Int) {
        setPosition(position.toFloat(), d)
    }

}
