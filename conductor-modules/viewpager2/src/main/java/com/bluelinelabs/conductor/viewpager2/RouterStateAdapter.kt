package com.bluelinelabs.conductor.viewpager2

import android.os.Bundle
import android.os.Parcelable
import android.util.LongSparseArray
import android.util.SparseArray
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.StatefulAdapter
import androidx.viewpager2.widget.ViewPager2
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.Router
import kotlinx.android.parcel.Parcelize

/**
 * An ViewPager2 adapter that uses Routers as pages
 */
abstract class RouterStateAdapter(private val host: Controller) :
  RecyclerView.Adapter<RouterViewHolder>(), StatefulAdapter {

  private var savedPages = LongSparseArray<Bundle>()
  internal var savedPageHistory = mutableListOf<Long>()
  private var maxPagesToStateSave = Int.MAX_VALUE
  private val visibleRouters = SparseArray<Router>()
  private var currentPrimaryRouterPosition = 0
  private var primaryItemCallback: PrimaryItemCallback? = null

  init {
    super.setHasStableIds(true)
  }

  /**
   * Called when a router is instantiated. Here the router's root should be set if needed.
   *
   * @param router   The router used for the page
   * @param position The page position to be instantiated.
   */
  abstract fun configureRouter(router: Router, position: Int)

  /**
   * Sets the maximum number of pages that will have their states saved. When this number is exceeded,
   * the page that was state saved least recently will have its state removed from the save data.
   */
  open fun setMaxPagesToStateSave(maxPagesToStateSave: Int) {
    require(maxPagesToStateSave >= 0) { "Only positive integers may be passed for maxPagesToStateSave." }
    this.maxPagesToStateSave = maxPagesToStateSave
    ensurePagesSaved()
  }

  private fun inferViewPager(recyclerView: RecyclerView): ViewPager2 {
    return recyclerView.parent as? ViewPager2 ?:
      error("Expected ViewPager2 instance. Got: ${recyclerView.parent}")
  }

  override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
    val viewPager = inferViewPager(recyclerView)
    primaryItemCallback = PrimaryItemCallback().also {
      viewPager.registerOnPageChangeCallback(it)
    }
  }

  override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
    val viewPager = inferViewPager(recyclerView)
    primaryItemCallback?.let {
      viewPager.unregisterOnPageChangeCallback(it)
    }
    primaryItemCallback = null
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouterViewHolder {
    return RouterViewHolder(parent)
  }

  override fun onBindViewHolder(holder: RouterViewHolder, position: Int) {
    holder.currentItemPosition = position

    attachRouter(holder, position)
  }

  override fun onViewAttachedToWindow(holder: RouterViewHolder) {
    super.onViewAttachedToWindow(holder)

    if (!holder.attached) {
      attachRouter(holder, holder.currentItemPosition)
    }
  }

  override fun onViewDetachedFromWindow(holder: RouterViewHolder) {
    super.onViewDetachedFromWindow(holder)

    detachRouter(holder)
  }

  override fun onViewRecycled(holder: RouterViewHolder) {
    super.onViewRecycled(holder)

    detachRouter(holder)

    holder.currentRouter?.let { router ->
      host.removeChildRouter(router)
      holder.currentRouter = null
    }
  }

  override fun onFailedToRecycleView(holder: RouterViewHolder): Boolean {
    return true
  }

  override fun getItemId(position: Int): Long {
    return position.toLong()
  }

  override fun setHasStableIds(hasStableIds: Boolean) {
    throw UnsupportedOperationException("Stable Ids are required for the adapter to function properly")
  }

  override fun saveState(): Parcelable {
    return SavedState(
      savedPagesKeys = (0 until savedPages.size()).map { savedPages.keyAt(it) },
      savedPagesValues = (0 until savedPages.size()).map { savedPages.valueAt(it) },
      savedPageHistory = savedPageHistory,
      maxPagesToStateSave = maxPagesToStateSave
    )
  }

  override fun restoreState(state: Parcelable) {
    if (state !is SavedState) return

    savedPages = LongSparseArray()
    state.savedPagesKeys.indices.forEach { index ->
      savedPages.put(state.savedPagesKeys[index], state.savedPagesValues[index])
    }

    savedPageHistory = state.savedPageHistory.toMutableList()
    maxPagesToStateSave = state.maxPagesToStateSave
  }

  private fun attachRouter(holder: RouterViewHolder, position: Int) {
    val itemId = getItemId(position)
    val router = host.getChildRouter(holder.container, "$itemId")
    holder.currentRouter = router
    holder.currentItemId = itemId

    if (!router.hasRootController()) {
      val routerSavedState = savedPages[position.toLong()]
      if (routerSavedState != null) {
        router.restoreInstanceState(routerSavedState)
        savedPages.remove(itemId)
        savedPageHistory.remove(itemId)
      }
    }

    router.rebindIfNeeded()
    configureRouter(router, position)

    if (position != currentPrimaryRouterPosition) {
      for (transaction in router.backstack) {
        transaction.controller.setOptionsMenuHidden(true)
      }
    }

    visibleRouters.put(position, router)

    holder.attached = true
  }

  private fun detachRouter(holder: RouterViewHolder) {
    if (!holder.attached) {
      return
    }

    holder.currentRouter?.let { router ->
      router.prepareForHostDetach()

      val savedState = Bundle()
      router.saveInstanceState(savedState)
      savedPages.put(holder.currentItemId, savedState)

      savedPageHistory.remove(holder.currentItemId)
      savedPageHistory.add(holder.currentItemId)

      ensurePagesSaved()

      if (visibleRouters[holder.currentItemPosition] == router) {
        visibleRouters.remove(holder.currentItemPosition)
      }
    }

    holder.attached = false
  }

  private fun ensurePagesSaved() {
    while (savedPages.size() > maxPagesToStateSave) {
      val positionToRemove = savedPageHistory.removeAt(0)
      savedPages.remove(positionToRemove)
    }
  }

  /**
   * Returns the already instantiated Router in the specified position or `null` if there
   * is no router associated with this position.
   */
  fun getRouter(position: Int): Router? {
    return visibleRouters[position]
  }

  inner class PrimaryItemCallback : ViewPager2.OnPageChangeCallback() {
    override fun onPageSelected(position: Int) {
      val router = visibleRouters[position]
      if (position != currentPrimaryRouterPosition) {
        val previousRouter = visibleRouters[currentPrimaryRouterPosition]

        previousRouter?.backstack?.forEach { it.controller.setOptionsMenuHidden(true) }
        router?.backstack?.forEach { it.controller.setOptionsMenuHidden(false) }
        currentPrimaryRouterPosition = position
      }
    }
  }

  @Parcelize
  private data class SavedState(
    val savedPagesKeys: List<Long>,
    val savedPagesValues: List<Bundle>,
    val savedPageHistory: List<Long>,
    val maxPagesToStateSave: Int
  ) : Parcelable
}