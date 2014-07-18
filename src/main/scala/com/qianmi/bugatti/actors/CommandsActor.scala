package com.qianmi.bugatti.actors

import java.io.File
import java.util.Date

import akka.actor._
import play.api.libs.iteratee.Input.Empty
import play.api.libs.json.{JsObject, JsValue, Json}

import scala.collection.mutable
import scala.sys.process.Process

import scala.concurrent.duration._

/**
 * Created by mind on 7/16/14.
 */

// salt执行命令
case class SaltCommand(id: Int, command: Seq[String], delayTime: Int = 0, workDir: String = ".")

// salt执行结果
case class SaltResult(result: String, excuteMicroseconds: Long)

// 执行超时
case class TimeOut()

class CommandsActor extends Actor with ActorLogging {
  val JobNameFormat = "Job_%s"

  def jobName(jid: String) = JobNameFormat.format(jid)

  val DelayStopJobResult = 0

  override def receive = {
    case cmd: SaltCommand => {
      val saltCmd = context.actorOf(Props(classOf[SaltCommandActor], cmd, sender).withDispatcher("execute-dispatcher"), name = "run_%d".format(cmd.id))

      saltCmd ! Run
    }

    // 从cmd触发过来
    case CheckJob(jid, delayTime) => {
      val jn = jobName(jid)
      context.child(jn).getOrElse {
        context.actorOf(Props(classOf[SaltResultActor], delayTime), name = jn)
      } ! NotifyMe(sender)
    }

    // 从jobresult触发过来，通知另一个job
    case reRun: ReRunNotify => {
      val jn = jobName(reRun.jid)
      context.child(jn).getOrElse {
        context.actorOf(Props(classOf[SaltResultActor], DelayStopJobResult), name = jn)
      } ! reRun
    }

    // 从httpserverActor触发过来
    case jobRet: JobFinish => {
      val jn = jobName(jobRet.jid)
      context.child(jn).getOrElse {
        context.actorOf(Props(classOf[SaltResultActor], DelayStopJobResult), name = jn)
      } ! jobRet
    }

    case `Status` => {
      val ret = context.children.map { child =>
        child.toString()
      }

      sender ! ret
    }

    case x => log.info(s"Unknown commands message: ${x}")
  }
}


private class SaltCommandActor(cmd: SaltCommand, remoteSender: ActorRef) extends Actor with ActorLogging {
  val TimeOutSeconds = 600

  val beginDate = new Date

  var jid = ""

  override def receive = {
    case Run => {
      try {
        val ret = Process(cmd.command ++ Seq("--return", "spirit", "--async"), new File(cmd.workDir)).lines.mkString(",")
        if (ret.size > 0 && ret.contains("Executed command with job ID")) {
          jid = ret.replaceAll("Executed command with job ID: ", "")

          context.parent ! CheckJob(jid, cmd.delayTime)
        }

        log.debug( s"""Execute "${cmd.command.mkString(" ")}"; ret: ${ret}""")
      } catch {
        case x: Exception => log.warning(s"Run exception: ${x}")
      }
    }

    case jobRet: JobFinish => {
      try {
        remoteSender ! SaltResult(jobRet.result, (new Date().getTime - beginDate.getTime))

        context.stop(self)
      } catch {
        case x: Exception => log.warning(s"CheckJid exception: ${x}")
      }
    }

    case x => log.info(s"Unknown salt command message: ${x}")
  }
}

private class SaltResultActor(delayTime: Int) extends Actor with ActorLogging {

  import context._

  val cmdSet = mutable.Set[ActorRef]().empty

  var jsonRet = Json.obj()

  var scheduleOne: Cancellable = _

  var bReturn = false

  var m_cmdActor: ActorRef = _

  var m_jobRet: JobFinish = _

  var reRunJid = ""

  override def receive: Receive = {
    case ReRunNotify(jid, cmdActor) => {
      if (bReturn) {
        cmdActor ! Run
      }
      cmdSet += cmdActor
    }

    case NotifyMe(cmdActor) => {
      if (bReturn) {
        cmdActor ! m_jobRet
      }
      if (reRunJid.length > 0) {
        context.parent ! ReRunNotify(reRunJid, m_cmdActor)
      }
      m_cmdActor = cmdActor
    }

    case jobRet: JobFinish => {
      bReturn = true
      m_jobRet = jobRet

      val retJson = Json.parse(jobRet.result)
      val resultLines = (retJson \ "result" \ "return").validate[Seq[String]].asOpt.getOrElse(Seq.empty)

      if (resultLines.nonEmpty && resultLines.last.contains("is running as PID")) {
        reRunJid = resultLines.last.replaceAll("^.* with jid ", "")

        if (m_cmdActor != null) {
          context.parent ! ReRunNotify(reRunJid, m_cmdActor)
        }
      } else {
        cmdSet.foreach { cmdActor =>
          cmdActor ! Run
        }

        if (delayTime <= 0) {
          if (m_cmdActor != null) {
            m_cmdActor ! jobRet
            log.debug(s"JobResult stop immediatly: ${jobRet}")
          }
        } else {
          jsonRet = jsonRet ++ retJson.as[JsObject]

          if (scheduleOne != null) scheduleOne.cancel

          scheduleOne = context.system.scheduler.scheduleOnce(delayTime seconds) {
            if (m_cmdActor != null) {
              m_cmdActor ! JobFinish(jobRet.jid, Json.stringify(jsonRet))
              log.debug(s"JobResult stop scheduler: ${jobRet}")
            }
          }
        }
      }

      context.system.scheduler.scheduleOnce(30 seconds) {
        context.stop(self)
      }
    }

    case x => log.info(s"Unknown salt result message: ${x}")
  }
}

// 运行命令
private case class Run()

private case class CheckJob(jid: String, delayTime: Int)

private case class JobFinish(jid: String, result: String)

private case class ReRunNotify(jid: String, cmdActor: ActorRef)

private case class Status()

private case class NotifyMe(cmdActor: ActorRef)