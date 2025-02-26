package org.jetbrains.plugins.notebooks.visualization.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.removeUserData
import com.intellij.util.asSafely
import org.jetbrains.plugins.notebooks.ui.editor.actions.command.mode.NotebookEditorMode
import org.jetbrains.plugins.notebooks.ui.editor.actions.command.mode.setMode
import org.jetbrains.plugins.notebooks.ui.visualization.NotebookCodeCellBackgroundLineMarkerRenderer
import org.jetbrains.plugins.notebooks.ui.visualization.NotebookLineMarkerRenderer
import org.jetbrains.plugins.notebooks.ui.visualization.NotebookTextCellBackgroundLineMarkerRenderer
import org.jetbrains.plugins.notebooks.ui.visualization.notebookAppearance
import org.jetbrains.plugins.notebooks.visualization.*
import java.awt.*
import javax.swing.JComponent
import kotlin.reflect.KClass

class EditorCellView(
  private val editor: EditorImpl,
  private val intervals: NotebookCellLines,
  internal var cell: EditorCell
) : Disposable {

  private var _controllers: List<NotebookCellInlayController> = emptyList()
  private val controllers: List<NotebookCellInlayController>
    get() = _controllers + ((input.component as? ControllerEditorCellViewComponent)?.controller?.let { listOf(it) } ?: emptyList())

  private val intervalPointer: NotebookIntervalPointer
    get() = cell.intervalPointer
  private val interval: NotebookCellLines.Interval
    get() {
      return intervalPointer.get() ?: error("Invalid interval")
    }

  private val cellHighlighters = mutableListOf<RangeHighlighter>()

  val input: EditorCellInput = EditorCellInput(
    editor,
    { currentComponent: EditorCellViewComponent? ->
      val currentController = (currentComponent as? ControllerEditorCellViewComponent)?.controller
      val controller = getInputFactories().firstNotNullOfOrNull { factory ->
        failSafeCompute(factory, editor, currentController?.let { listOf(it) }
                                         ?: emptyList(), intervals.intervals.listIterator(interval.ordinal))
      }
      if (controller != null) {
        if (controller == currentController) {
          currentComponent
        }
        else {
          ControllerEditorCellViewComponent(controller)
        }
      }
      else {
        TextEditorCellViewComponent(editor, cell)
      }
    }, cell).also {
    it.addViewComponentListener(object : EditorCellViewComponentListener {
      override fun componentBoundaryChanged(location: Point, size: Dimension) {
        updateBoundaries()
      }
    })
  }

  private var _location: Point = Point(0, 0)

  val location: Point get() = _location

  private var _size: Dimension = Dimension(0, 0)

  val size: Dimension get() = _size

  private var _outputs: EditorCellOutputs? = null

  val outputs: EditorCellOutputs?
    get() = _outputs

  private var selected = false

  private var mouseOver = false

  init {
    update()
    updateSelection(false)
  }

  private fun updateBoundaries() {
    val inputBounds = input.bounds
    val y = inputBounds.y
    _location = Point(0, y)
    val currentOutputs = outputs
    _size = Dimension(
      editor.contentSize.width,
      currentOutputs?.bounds?.let { it.height + it.y - y } ?: inputBounds.height
    )
  }

  override fun dispose() {
    _controllers.forEach { controller ->
      disposeController(controller)
    }
    input.dispose()

    removeCellHighlight()
  }

  private fun disposeController(controller: NotebookCellInlayController) {
    val inlay = controller.inlay
    inlay.renderer.asSafely<JComponent>()?.let { DataManager.removeDataProvider(it) }
    Disposer.dispose(inlay)
  }

  fun update(force: Boolean = false) {
    extracted(force)
  }

  private fun extracted(force: Boolean) {
    val otherFactories = NotebookCellInlayController.Factory.EP_NAME.extensionList
      .filter { it !is NotebookCellInlayController.InputFactory }

    val controllersToDispose = _controllers.toMutableSet()
    _controllers = if (!editor.isDisposed) {
      otherFactories.mapNotNull { factory -> failSafeCompute(factory, editor, _controllers, intervals.intervals.listIterator(interval.ordinal)) }
    }
    else {
      emptyList()
    }
    controllersToDispose.removeAll(_controllers.toSet())
    controllersToDispose.forEach { disposeController(it) }
    for (controller in controllers) {
      val inlay = controller.inlay
      inlay.renderer.asSafely<JComponent>()?.let { component ->
        val oldProvider = DataManager.getDataProvider(component)
        if (oldProvider != null && oldProvider !is NotebookCellDataProvider) {
          LOG.error("Overwriting an existing CLIENT_PROPERTY_DATA_PROVIDER. Old provider: $oldProvider")
        }
        DataManager.removeDataProvider(component)
        DataManager.registerDataProvider(component, NotebookCellDataProvider(editor, component) { interval })
      }
    }
    input.update(force)
    updateOutputs()
    updateBoundaries()
    updateCellHighlight()
  }

  private fun updateOutputs() {
    if (hasOutputs()) {
      if (_outputs == null) {
        _outputs = EditorCellOutputs(editor, { interval }).also { Disposer.register(this, it) }
        updateCellHighlight()
        updateFolding()
      }
      else {
        outputs?.update()
      }
    }
    else {
      outputs?.let { Disposer.dispose(it) }
      _outputs = null
    }
  }

  private fun hasOutputs() = interval.type == NotebookCellLines.CellType.CODE
                             && (editor.editorKind != EditorKind.DIFF || Registry.`is`("jupyter.diff.viewer.output"))

  private fun getInputFactories(): Sequence<NotebookCellInlayController.Factory> {
    return NotebookCellInlayController.Factory.EP_NAME.extensionList.asSequence()
      .filter { it is NotebookCellInlayController.InputFactory }
  }

  private fun failSafeCompute(factory: NotebookCellInlayController.Factory,
                              editor: Editor,
                              controllers: Collection<NotebookCellInlayController>,
                              intervalIterator: ListIterator<NotebookCellLines.Interval>): NotebookCellInlayController? {
    try {
      return factory.compute(editor as EditorImpl, controllers, intervalIterator)
    }
    catch (t: Throwable) {
      thisLogger().error("${factory.javaClass.name} shouldn't throw exceptions at NotebookCellInlayController.Factory.compute(...)", t)
      return null
    }
  }

  fun updatePositions() {
    input.updatePositions()
    outputs?.updatePositions()
  }

  fun onViewportChanges() {
    input.onViewportChange()
    outputs?.onViewportChange()
  }

  fun setGutterAction(action: AnAction) {
    input.setGutterAction(action)
  }

  fun mouseExited() {
    mouseOver = false
    updateFolding()
    updateRunButton()
  }

  fun mouseEntered() {
    mouseOver = true
    updateFolding()
    updateRunButton()
  }

  inline fun <reified T : Any> getExtension(): T? {
    return getExtension(T::class)
  }

  @Suppress("UNCHECKED_CAST")
  fun <T : Any> getExtension(type: KClass<T>): T? {
    return controllers.firstOrNull { type.isInstance(it) } as? T
  }

  fun addCellHighlighter(provider: () -> RangeHighlighter) {
    val highlighter = provider()
    cellHighlighters.add(highlighter)
  }

  private fun removeCellHighlight() {
    for (highlighter in cellHighlighters) {
      highlighter.dispose()
    }
    cellHighlighters.clear()
  }

  private fun updateCellHighlight() {
    removeCellHighlight()
    val interval = intervalPointer.get() ?: error("Invalid interval")
    val startOffset = editor.document.getLineStartOffset(interval.lines.first)
    val endOffset = editor.document.getLineEndOffset(interval.lines.last)
    addCellHighlighter {
      editor.markupModel.addRangeHighlighter(
        null,
        startOffset,
        endOffset,
        HighlighterLayer.FIRST - 100,  // Border should be seen behind any syntax highlighting, selection or any other effect.
        HighlighterTargetArea.LINES_IN_RANGE
      ).also {
        it.lineMarkerRenderer = NotebookGutterLineMarkerRenderer(interval)
      }
    }

    if (interval.type == NotebookCellLines.CellType.CODE && editor.notebookAppearance.shouldShowCellLineNumbers() && editor.editorKind != EditorKind.DIFF) {
      addCellHighlighter {
        editor.markupModel.addRangeHighlighter(
          null,
          startOffset,
          endOffset,
          HighlighterLayer.FIRST - 99,  // Border should be seen behind any syntax highlighting, selection or any other effect.
          HighlighterTargetArea.LINES_IN_RANGE
        )
      }
    }

    if (interval.type == NotebookCellLines.CellType.CODE) {
      addCellHighlighter {
        editor.markupModel.addRangeHighlighterAndChangeAttributes(null, startOffset, endOffset, HighlighterLayer.FIRST - 100, HighlighterTargetArea.LINES_IN_RANGE, false) { o: RangeHighlighterEx ->
          o.lineMarkerRenderer = NotebookCodeCellBackgroundLineMarkerRenderer(o)
        }
      }
    }
    else if (editor.editorKind != EditorKind.DIFF) {
      addCellHighlighter {
        editor.markupModel.addRangeHighlighterAndChangeAttributes(null, startOffset, endOffset, HighlighterLayer.FIRST - 100, HighlighterTargetArea.LINES_IN_RANGE, false) { o: RangeHighlighterEx ->
          o.lineMarkerRenderer = NotebookTextCellBackgroundLineMarkerRenderer(o)
        }
      }
    }

    for (controller: NotebookCellInlayController in controllers) {
      controller.createGutterRendererLineMarker(editor, interval, this)
    }
  }

  fun updateSelection(value: Boolean) {
    selected = value
    updateFolding()
    updateRunButton()
    updateCellHighlight()
  }

  fun disableMarkdownRenderingIfEnabled() {  // PY-73017 point 1
    val markdownController = controllers.filterIsInstance<MarkdownInlayRenderingController>().firstOrNull()
    markdownController?.let {  // exists iff this is a rendered markdown cell
      it.stopRendering()
      editor.setMode(NotebookEditorMode.EDIT)

      val startOffset = editor.document.getLineStartOffset(interval.lines.first + 1)
      val endOffset = editor.document.getLineEndOffset(interval.lines.last)
      val foldingModel = editor.foldingModel
      val foldRegion: FoldRegion? = foldingModel.getFoldRegion(startOffset, endOffset)

      foldRegion?.let {  // to avoid clash with folding created by EditorCellInput.toggleTextFolding
        foldingModel.runBatchFoldingOperation {
          foldingModel.removeFoldRegion(it)
        }
      }

      update(true)
      cell.putUserData(wasFoldedInRenderedState, true)
    }
  }

  fun enableMarkdownRenderingIfNeeded() {
    // Making use of [org.jetbrains.plugins.notebooks.editor.JupyterMarkdownEditorCaretListener]
    // The idea was to force rendering of md cells that were folded in the rendered state.
    // This was a temporary solution written on rush, since we cannot use NotebookMarkdownEditorManager here directly.
    // todo: a refactor will be required as part of the ongoing reorganization of Jupyter modules
    cell.getUserData(wasFoldedInRenderedState) ?: return
    val document = editor.document
    val caretModel = editor.caretModel
    val oldPosition = caretModel.offset
    val startLine = cell.interval.lines.first
    val startOffset = document.getLineEndOffset(startLine)
    caretModel.moveToOffset(startOffset)
    caretModel.moveToOffset(oldPosition)
    cell.removeUserData(wasFoldedInRenderedState)
  }

  private fun updateFolding() {
    input.updateSelection(selected)
    outputs?.updateSelection(selected)
    if (mouseOver || selected) {
      input.showFolding()
      outputs?.showFolding()
    }
    else {
      input.hideFolding()
      outputs?.hideFolding()
    }
  }

  private fun updateRunButton() {
    if (mouseOver || selected) {
      input.showRunButton()
    }
    else {
      input.hideRunButton()
    }
  }

  inner class NotebookGutterLineMarkerRenderer(private val interval: NotebookCellLines.Interval) : NotebookLineMarkerRenderer() {
    override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
      editor as EditorImpl

      @Suppress("NAME_SHADOWING")
      g.create().use { g ->
        g as Graphics2D

        val visualLineStart = editor.xyToVisualPosition(Point(0, g.clip.bounds.y)).line
        val visualLineEnd = editor.xyToVisualPosition(Point(0, g.clip.bounds.run { y + height })).line
        val logicalLineStart = editor.visualToLogicalPosition(VisualPosition(visualLineStart, 0)).line
        val logicalLineEnd = editor.visualToLogicalPosition(VisualPosition(visualLineEnd, 0)).line

        if (interval.lines.first > logicalLineEnd || interval.lines.last < logicalLineStart) return

        paintBackground(editor, g, r, interval)
      }
    }

    private fun paintBackground(editor: EditorImpl,
                                g: Graphics,
                                r: Rectangle,
                                interval: NotebookCellLines.Interval) {
      for (controller: NotebookCellInlayController in controllers) {
        controller.paintGutter(editor, g, r, interval)
      }
      outputs?.paintGutter(editor, g, r)
    }
  }

  internal data class NotebookCellDataProvider(
    val editor: Editor,
    val component: JComponent,
    val intervalProvider: () -> NotebookCellLines.Interval,
  ) : DataProvider {
    override fun getData(key: String): Any? =
      when (key) {
        NOTEBOOK_CELL_LINES_INTERVAL_DATA_KEY.name -> intervalProvider()
        PlatformCoreDataKeys.CONTEXT_COMPONENT.name -> component
        PlatformDataKeys.EDITOR.name -> editor
        else -> null
      }
  }

  companion object {
    private val LOG = logger<EditorCell>()
    val wasFoldedInRenderedState = Key<Boolean>("jupyter.markdown.folding.was.rendered")
  }
}