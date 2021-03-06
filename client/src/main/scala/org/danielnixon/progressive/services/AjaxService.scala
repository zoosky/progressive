package org.danielnixon.progressive.services

import org.scalajs.dom.ext.Ajax.InputData
import org.scalajs.dom.raw.XMLHttpRequest
import org.danielnixon.progressive.extensions.core.StringWrapper
import org.danielnixon.progressive.shared.Wart
import org.danielnixon.progressive.shared.api.AjaxResponse
import org.danielnixon.progressive.shared.http.{ HeaderNames, HeaderValues }
import org.scalajs.dom.Event

import scala.concurrent.{ Future, Promise }
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.annotation.ScalaJSDefined
import scalaz.Scalaz._

final case class AjaxRequestException(message: String, html: String, invalidForm: Option[String]) extends Exception

// ScalaJSDefined so that it can be stored in a WeakMap.
@ScalaJSDefined
final class AjaxRequest(val future: Future[AjaxResponse], val abort: () => Unit) extends js.Object

class AjaxService {

  private val ajaxHeaders = Map(HeaderNames.X_REQUESTED_WITH -> HeaderValues.XML_HTTP_REQUEST)

  def ajax(method: String, url: String, data: Option[InputData], headers: Map[String, String]): AjaxRequest = {

    val (request, abort) = makeRequest(method, url, data, ajaxHeaders ++ headers)

    val fut = request flatMap { response =>
      AjaxResponse.fromJson(response.responseText) match {
        case Some(ajaxResponse) =>
          Future.successful(ajaxResponse)
        case None =>
          Future.failed[AjaxResponse](AjaxRequestException("Could not parse.", "Could not parse.", None))
      }
    }

    new AjaxRequest(fut, abort)
  }

  def get(url: String): AjaxRequest = {
    ajax("GET", url, None, Map.empty[String, String])
  }

  /**
    * Based on org.scalajs.dom.ext.Ajax but exposes the underlying XMLHttpRequest so it can be aborted.
    */
  @SuppressWarnings(Array(Wart.Any))
  private def makeRequest(
    method: String,
    url: String,
    data: Option[InputData],
    headers: Map[String, String]
  ): (Future[XMLHttpRequest], () => Unit) = {
    val req = new XMLHttpRequest()
    val promise = Promise[XMLHttpRequest]()

    req.onreadystatechange = { (e: Event) =>
      if (req.readyState === 4) {
        if ((req.status >= 200 && req.status < 300) || req.status === 304) {
          promise.success(req)
        } else {
          val fallbackErrorMessage = req.responseText.toOption.getOrElse(req.statusText)
          val ajaxResponse = AjaxResponse.fromJson(req.responseText)
          val message = ajaxResponse.flatMap(_.message).getOrElse(fallbackErrorMessage)
          val html = ajaxResponse.flatMap(_.html).getOrElse(message)
          val invalidForm = ajaxResponse.flatMap(_.invalidForm)
          promise.failure(AjaxRequestException(message, html, invalidForm))
        }
      }
    }

    req.open(method, url)

    for {
      (header, value) <- headers
    } req.setRequestHeader(header, value)

    data match {
      case Some(d) => req.send(d)
      case None => req.send()
    }

    (promise.future, () => req.abort())
  }
}
