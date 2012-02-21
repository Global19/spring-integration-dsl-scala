/*
 * Copyright 2002-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.eip.dsl

import java.lang.IllegalStateException
import scala.collection.JavaConversions
import org.apache.log4j.Logger
import org.springframework.context.ApplicationContext
import org.springframework.integration.support.MessageBuilder
import org.springframework.integration.Message
import org.springframework.integration.MessageChannel
import org.springframework.util.CollectionUtils
import org.springframework.integration.channel.QueueChannel
import scala.collection.JavaConversions._

/**
 * @author Oleg Zhurakousky
 */
object SI {

  def apply(compositions: BaseIntegrationComposition*) = new SI(null, compositions: _*)

  def apply(parentApplicationContext: ApplicationContext)(compositions: BaseIntegrationComposition*) =
    new SI(parentApplicationContext, compositions: _*)

}

/**
 *
 */
class SI(parentContext: ApplicationContext, compositions: BaseIntegrationComposition*) {
  private[dsl] val logger = Logger.getLogger(this.getClass)
  if (logger.isDebugEnabled) {
    logger.debug("Creating new EIP context")
  }
  val normalizedComposition = compositions(0).normalizeComposition()
  normalizedComposition.target match {
    case poller: Poller => {
      throw new IllegalStateException("The resulting message flow configuration ends with Poller which " +
        "has no consumer Consumer: " + poller)
    }
    case ch: Channel => {
      if (ch.capacity == Integer.MIN_VALUE) {
        throw new IllegalStateException("The resulting message flow configuration ends with " +
          "Direct or PubSub Channel but no subscribers are configured: " + ch)
      }
    }
    case _ =>
  }
  val inputChannelName: String = DslUtils.getStartingComposition(normalizedComposition).target.asInstanceOf[AbstractChannel].name

  val compositionList: List[BaseIntegrationComposition] = compositions.toList.patch(0, Seq(normalizedComposition), 1)

  val applicationContext = ApplicationContextBuilder.build(parentContext, compositionList)
  /**
   *
   */
  def send(message: Any, timeout: Long = 0, headers: Map[String, Any] = null): Boolean = {
    if (compositions.size > 1) {
      throw new IllegalStateException("Can not determine starting point for this context since it contains multiple")
    }

    val inputChannel = this.applicationContext.getBean[MessageChannel](this.inputChannelName, classOf[MessageChannel])

    val messageToSend = this.constructMessage(message, headers)
    val sent = if (timeout > 0) {
      inputChannel.send(messageToSend, timeout)
    } else {
      inputChannel.send(messageToSend)
    }
    sent
  }

  def sendAndReceive[T <: Any](message: Any, timeout: Long = 0, headers: Map[String, Any] = null)(implicit m: scala.reflect.Manifest[T]): T = {
    if (compositions.size > 1) {
      throw new IllegalStateException("Can not determine starting point for thsi context since it contains multiple")
    }

    val inputChannel = this.applicationContext.getBean[MessageChannel](this.inputChannelName, classOf[MessageChannel])

    val replyChannel = new QueueChannel()
    val messageToSend = MessageBuilder.
      fromMessage(this.constructMessage(message, headers)).setReplyChannel(replyChannel).build()
    val sent = if (timeout > 0) {
      inputChannel.send(messageToSend, timeout)
    } else {
      inputChannel.send(messageToSend)
    }
    val replyMessage = replyChannel.receive(1000)
    val convertedReply = if (m.erasure.isAssignableFrom(classOf[Message[_]])) {
      replyMessage.asInstanceOf[T]
    } else {
      val reply = replyMessage.getPayload
      reply match {
        case list: java.util.List[_] => list.toList
        case set: java.util.Set[_] => set.toSet
        case map: java.util.Map[_, _] => map.toMap
        case _ => reply
      }
    }
    convertedReply.asInstanceOf[T]
  }

  private def constructMessage(message: Any, headers: Map[String, Any] = null): Message[_] = {
    val javaHeaders = if (headers != null) {
      JavaConversions.mapAsJavaMap(headers)
    } else {
      null
    }
    val messageToSend: Message[_] = message match {
      case msg: Message[_] => {
        if (!CollectionUtils.isEmpty(javaHeaders)) {
          MessageBuilder.fromMessage(msg).copyHeadersIfAbsent(javaHeaders).build()
        } else {
          msg
        }
      }
      case _ => {
        if (!CollectionUtils.isEmpty(javaHeaders)) {
          MessageBuilder.withPayload(message).copyHeaders(javaHeaders).build()
        } else {
          MessageBuilder.withPayload(message).build()
        }
      }
    }
    messageToSend
  }

}