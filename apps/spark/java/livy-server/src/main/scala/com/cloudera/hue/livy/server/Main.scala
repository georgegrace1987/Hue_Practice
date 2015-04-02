package com.cloudera.hue.livy.server

import javax.servlet.ServletContext

import com.cloudera.hue.livy.server.batch.{BatchYarnFactory, BatchProcessFactory, BatchServlet, BatchManager}
import com.cloudera.hue.livy.server.sessions._
import com.cloudera.hue.livy.{Utils, Logging, LivyConf, WebServer}
import org.scalatra._
import org.scalatra.servlet.ScalatraListener

object Main {

  val SESSION_KIND = "livy-server.session.kind"
  val THREAD_SESSION = "thread"
  val PROCESS_SESSION = "process"
  val YARN_SESSION = "yarn"

  def main(args: Array[String]): Unit = {
    val livyConf = new LivyConf()
    Utils.loadDefaultLivyProperties(livyConf)

    val host = livyConf.get("livy.server.host", "0.0.0.0")
    val port = livyConf.getInt("livy.server.port", 8998)

    val server = new WebServer(host, port)

    server.context.setResourceBase("src/main/com/cloudera/hue/livy/server")
    server.context.setInitParameter(ScalatraListener.LifeCycleKey, classOf[ScalatraBootstrap].getCanonicalName)
    server.context.addEventListener(new ScalatraListener)

    server.start()

    try {
      System.setProperty("livy.server.callback-url", f"http://${server.host}:${server.port}")
    } finally {
      server.join()
      server.stop()

      // Make sure to close all our outstanding http requests.
      dispatch.Http.shutdown()
    }
  }
}

class ScalatraBootstrap extends LifeCycle with Logging {

  var sessionManager: SessionManager = null
  var batchManager: BatchManager = null

  override def init(context: ServletContext): Unit = {
    val livyConf = new LivyConf()

    val sessionFactoryKind = livyConf.get("livy.server.session.factory", "process")

    info(f"Using $sessionFactoryKind sessions")

    val sessionFactory = sessionFactoryKind match {
      case "thread" => new ThreadSessionFactory(livyConf)
      case "process" => new ProcessSessionFactory(livyConf)
      case "yarn" => new YarnSessionFactory(livyConf)
      case _ =>
        println(f"Unknown session factory: $sessionFactoryKind")
        sys.exit(1)
    }

    sessionManager = new SessionManager(sessionFactory)

    val batchFactory = sessionFactoryKind match {
      case "thread" | "process" => new BatchProcessFactory()
      case "yarn" => new BatchYarnFactory()
      case _ =>
        println(f"Unknown batch factory: $sessionFactoryKind")
        sys.exit(1)
    }

    batchManager = new BatchManager(batchFactory)

    context.mount(new SessionServlet(sessionManager), "/sessions/*")
    context.mount(new BatchServlet(batchManager), "/batches/*")
  }

  override def destroy(context: ServletContext): Unit = {
    if (sessionManager != null) {
      sessionManager.shutdown()
    }

    if (batchManager != null) {
      batchManager.shutdown()
    }
  }
}
