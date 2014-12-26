package com.qianmi.bugatti.actors

import akka.actor._
import akka.event.LoggingReceive

import scala.language.postfixOps

/**
 * Created by mind on 7/16/14.
 */

trait SpiritCommand

trait SpiritResult

// salt执行命令
case class SaltStatus(hostName: String, hostIp: String) extends SpiritCommand
case class SaltCommand(command: Seq[String], workDir: String = ".") extends SpiritCommand
case class SaltJobStop(jid: String) extends JobMsg with SpiritCommand


case class SaltStatusResult(hostName: String, hostIp: String, canPing: Boolean, canSPing: Boolean, mmInfo: String) extends SpiritResult
case class SaltJobBegin(jid: String, excuteMicroseconds: Long) extends SpiritResult
case class SaltJobOk(result: String, excuteMicroseconds: Long) extends SpiritResult
case class SaltJobError(msg: String, excuteMicroseconds: Long) extends SpiritResult
case class SaltJobStoped(result: String, excuteMicroseconds: Long) extends SpiritResult

// 执行超时
case class SaltTimeOut() extends SpiritResult

class CommandsActor extends Actor with ActorLogging {
  val JobNameFormat = "Job_%s"

  def jobName(jid: String) = JobNameFormat.format(jid)

  val DelayStopJobResult = 3

  override def receive = LoggingReceive {
    case cmd: SaltCommand => {
      log.debug(s"remoteSender: ${sender}")

      val saltCmd = context.actorOf(Props(classOf[SaltCommandActor], cmd, sender).withDispatcher("execute-dispatcher"))
      saltCmd ! Run
    }

    case ss: SaltStatus => {
      val ssa = context.actorOf(Props(classOf[SaltStatusActor], sender))
      ssa ! ss
    }

    case jobMsg: JobMsg => {
      val jn = jobName(jobMsg.jid)
      context.child(jn).getOrElse {
        context.actorOf(Props(classOf[SaltResultActor]), name = jn)
      } ! jobMsg
    }

    case Status => {
      val ret = context.children.map { child =>
        child.toString()
      }

      sender ! ret
    }

    case x => log.info(s"Unknown commands message: ${x}")
  }
}
