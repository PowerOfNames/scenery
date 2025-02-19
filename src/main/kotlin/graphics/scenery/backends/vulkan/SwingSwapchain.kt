package graphics.scenery.backends.vulkan

import graphics.scenery.Hub
import graphics.scenery.backends.RenderConfigReader
import graphics.scenery.backends.SceneryWindow
import graphics.scenery.utils.SceneryJPanel
import graphics.scenery.utils.SceneryPanel
import org.lwjgl.system.MemoryUtil.memFree
import org.lwjgl.system.Platform
import org.lwjgl.vulkan.KHRSurface
import org.lwjgl.vulkan.KHRSwapchain
import org.lwjgl.vulkan.VkQueue
import org.lwjgl.vulkan.awt.AWTVK
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.event.ComponentEvent
import java.awt.event.ComponentListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * GLFW-based default Vulkan Swapchain and window, residing on [device], associated with [queue].
 * Needs to be given [commandPools] to allocate command buffers from. [useSRGB] determines whether
 * the sRGB colorspace will be used, [vsync] determines whether vertical sync will be forced (swapping
 * rendered images in sync with the screen's frequency). [undecorated] determines whether the created
 * window will have the window system's default chrome or not.
 *
 * @author Ulrik Günther <hello@ulrik.is>
 */
open class SwingSwapchain(override val device: VulkanDevice,
                          override val queue: VkQueue,
                          override val commandPools: VulkanRenderer.CommandPools,
                          override val renderConfig: RenderConfigReader.RenderConfig,
                          override val useSRGB: Boolean = true,
                          override val vsync: Boolean = true,
                          override val undecorated: Boolean = false) : VulkanSwapchain(device, queue, commandPools, renderConfig, useSRGB, vsync, undecorated) {

    private val WINDOW_RESIZE_TIMEOUT: Long = 500_000_000

    protected var sceneryPanel: SceneryPanel? = null
    var mainFrame: JFrame? = null
        protected set

    /**
     * Creates a window for this swapchain, and initialiases [win] as [SceneryWindow.GLFWWindow].
     * Needs to be handed a [VulkanRenderer.SwapchainRecreator].
     * Returns the initialised [SceneryWindow].
     */
    override fun createWindow(win: SceneryWindow, swapchainRecreator: VulkanRenderer.SwapchainRecreator): SceneryWindow {
        val p = if(Platform.get() == Platform.MACOSX && sceneryPanel == null) {
            val mainFrame = JFrame(win.title)
            mainFrame.setSize(win.width, win.height)
            mainFrame.layout = BorderLayout()

            val sceneryPanel = SceneryJPanel()
            mainFrame.add(sceneryPanel, BorderLayout.CENTER)
            mainFrame.isVisible = true

            sceneryPanel
        }
        else {
            sceneryPanel as? SceneryJPanel ?: throw IllegalArgumentException("Must have SwingWindow")
        }

        val canvas = Canvas()

        p.component = canvas
        p.layout = BorderLayout()
        p.add(canvas, BorderLayout.CENTER)

        val frame = SwingUtilities.getAncestorOfClass(JFrame::class.java, p) as JFrame

        surface = AWTVK.create(canvas, device.instance)
        frame.isVisible = true

        frame.addWindowListener(object: WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                super.windowClosing(e)

                KHRSurface.vkDestroySurfaceKHR(device.instance, surface, null)
            }

        })

        window = SceneryWindow.SwingWindow(p)
        window.width = win.width
        window.height = win.height

        // the listener should only be initialized here, otherwise [window]
        // might be uninitialized.
        p.addComponentListener(object : ComponentListener {
            override fun componentResized(e: ComponentEvent) {
                if(lastResize > 0L && lastResize + WINDOW_RESIZE_TIMEOUT > System.nanoTime()) {
                    return
                }

                if(e.component.width <= 0 || e.component.height <= 0) {
                    return
                }

                window.width = e.component.width
                window.height = e.component.height

                logger.debug("Resizing panel to ${window.width}x${window.height}")
                swapchainRecreator.mustRecreate = true
                lastResize = System.nanoTime()
            }

            override fun componentMoved(e: ComponentEvent) {}
            override fun componentHidden(e: ComponentEvent) {}
            override fun componentShown(e: ComponentEvent) {}
        })

        return window
    }

    /**
     * Changes the current window to fullscreen.
     */
    override fun toggleFullscreen(hub: Hub, swapchainRecreator: VulkanRenderer.SwapchainRecreator) {
        // TODO: Add
    }

    /**
     * Embeds the swapchain into a [SceneryPanel].
     */
    override fun embedIn(panel: SceneryPanel?) {
        if(panel == null) {
            return
        }

        sceneryPanel = panel
    }

    /**
     * Returns the number of fully presented frames.
     */
    override fun presentedFrames(): Long {
        return presentedFrames
    }

    /**
     * Closes the swapchain, deallocating all of its resources.
     */
    override fun close() {
        logger.debug("Closing swapchain $this")
        KHRSwapchain.vkDestroySwapchainKHR(device.vulkanDevice, handle, null)

        (sceneryPanel as? SceneryJPanel)?.remove(0)
        presentInfo.free()
        memFree(swapchainImage)
        memFree(swapchainPointer)
    }

    companion object: SwapchainParameters {
        override var headless = false
        override var usageCondition = { p: SceneryPanel? ->
            System.getProperty("scenery.Renderer.UseAWT", "false")?.toBoolean() ?: false
                    || p is SceneryJPanel
                    || (Platform.get() == Platform.MACOSX && System.getProperty("scenery.Headless", "false").toBoolean())
        }
    }
}
