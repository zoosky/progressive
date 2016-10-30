package org.danielnixon.progressive.services

import org.danielnixon.progressive.extensions.dom.{ ElementWrapper, NodeSelectorWrapper }
import org.danielnixon.progressive.extensions.virtualdom.PatchObjectWrapper
import org.danielnixon.progressive.facades.es6.WeakMap
import org.danielnixon.progressive.facades.virtualdom.{ VDomParser, VTree, VirtualDom }
import org.danielnixon.progressive.shared.Wart
import org.danielnixon.progressive.shared.api.{ CssClasses, DataAttributes, RefreshSettings }
import org.scalajs.dom.Element
import org.scalajs.dom.raw.HTMLElement

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js.timers.setInterval

class RefreshService(
    virtualDom: VirtualDom,
    vdomParser: VDomParser,
    eventHandlerSetupService: EventHandlerSetupService,
    ajaxService: AjaxService,
    applyDiff: Element => Boolean
) {

  private object Data {
    val paused = "data-paused"
  }

  private val refreshRequestMap = new WeakMap[Element, AjaxRequest]
  private val vdomMap = new WeakMap[Element, VTree]

  private def vDomTarget(element: Element): Element = {
    element.querySelectorOpt(s".${CssClasses.refreshContent}").getOrElse(element)
  }

  @SuppressWarnings(Array(Wart.AsInstanceOf))
  private def createVdom(element: Element): VTree = {
    val target = vDomTarget(element)
    vdomParser(target.asInstanceOf[HTMLElement])
  }

  def refresh(element: Element, userTriggered: Boolean): Future[Unit] = {

    val alreadyRefreshing = refreshRequestMap.has(element)

    element.getAttributeOpt(DataAttributes.refresh) match {
      case Some(url) if !alreadyRefreshing =>
        val request = ajaxService.get(url)
        refreshRequestMap.set(element, request)
        val fut = request.future.map { ajaxResponse =>
          val shouldApplyDiff = userTriggered || applyDiff(element)

          if (shouldApplyDiff) {
            // Get existing virtual DOM.
            val targetVdom = vdomMap.get(element).toOption.getOrElse(createVdom(element))

            // Create new virtual DOM.
            val newVdom = vdomParser(ajaxResponse.html)
            vdomMap.set(element, newVdom)

            // Calculate patch.
            val patchObject = virtualDom.diff(targetVdom, newVdom)

            if (!patchObject.isEmpty) {
              // Apply patch.
              val target = vDomTarget(element)
              virtualDom.patch(target, patchObject)

              // Re-bind event handlers, etc.
              eventHandlerSetupService.setup(element, this)
            }
          }
        }

        fut.onComplete { _ => refreshRequestMap.delete(element) }

        fut

      case _ => Future.successful(())
    }
  }

  def setupRefresh(element: Element): Unit = {
    element.getAttributeOpt(DataAttributes.refresh).flatMap(RefreshSettings.fromJson) foreach { settings =>

      vdomMap.set(element, createVdom(element))

      element.setAttribute(DataAttributes.refresh, settings.url)

      settings.interval map { interval =>
        setInterval(interval.toDouble) {
          val paused = element.hasAttribute(Data.paused)
          if (!paused) {
            refresh(element, userTriggered = false)
          }
        }
      }
    }
  }

  def updateRefresh(element: Element, url: String): Unit = {
    if (element.hasAttribute(DataAttributes.refresh)) {
      element.setAttribute(DataAttributes.refresh, url)
      vdomMap.delete(element)
    }
  }

  def pauseAutoRefresh(element: Element): Unit = {
    // Cancel existing request.
    val request = refreshRequestMap.get(element)
    refreshRequestMap.delete(element)
    request.foreach(_.abort())

    element.setAttribute(Data.paused, "true")
  }

  def resumeAutoRefresh(element: Element): Unit = {
    element.removeAttribute(Data.paused)
  }
}
