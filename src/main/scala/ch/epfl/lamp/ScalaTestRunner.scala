package ch.epfl.lamp

import sbt._
import Keys._
import sys.process.{ Process => SysProc, ProcessLogger }
import java.util.concurrent._
import collection.mutable.ListBuffer
import scala.pickling.Defaults._
import scala.pickling.json._
import grading.GradingSummary
import Settings._

object ScalaTestRunner {

  class LimitedStringBuffer {
    val buf = new ListBuffer[String]()
    private var lines = 0
    private var lengthCropped = false

    override def toString() = buf.mkString("\n").trim

    def append(s: String) =
      if (lines < Settings.maxOutputLines) {
        val shortS =
          if (s.length > Settings.maxOutputLineLength) {
            if (!lengthCropped) {
              val msg =
                """WARNING: OUTPUT LINES CROPPED
                  |Your program generates very long lines on the standard (or error) output. Some of
                  |the lines have been cropped.
                  |This should not have an impact on your grade or the grading process; however it is
                  |bad style to leave `print` statements in production code, so consider removing and
                  |replacing them by proper tests.
                  |""".stripMargin
              buf.prepend(msg)
              lengthCropped = true
            }
            s.substring(0, Settings.maxOutputLineLength)
          } else s
        buf.append(shortS)
        lines += 1
      } else if (lines == Settings.maxOutputLines) {
        val msg =
          """WARNING: PROGRAM OUTPUT TOO LONG
            |Your program generates massive amounts of data on the standard (or error) output.
            |You are probably using `print` statements to debug your code.
            |This should not have an impact on your grade or the grading process; however it is
            |bad style to leave `print` statements in production code, so consider removing and
            |replacing them by proper tests.
            |""".stripMargin
        buf.prepend(msg)
        lines += 1
      }
  }

  private def forkProcess(proc: SysProc, timeout: Int) {
    val executor = Executors.newSingleThreadExecutor()
    val future: Future[Unit] = executor.submit(new Callable[Unit] {
      def call { proc.exitValue() }
    })
    try {
      future.get(timeout, TimeUnit.SECONDS)
    } catch {
      case to: TimeoutException =>
        future.cancel(true)
        throw to
    } finally {
      executor.shutdown()
    }
  }

  private def runPathString(file: File) = file.getAbsolutePath().replace(" ", "\\ ")

  def runScalaTest(classpath: Classpath, testClasses: File, outfile: File,
    policyFile: File, resourceFiles: List[File], agents: List[File],
    javaSystemProperties: Traversable[(String, String)],
    logError: String => Unit) = {

    val classpathString = classpath map {
      case Attributed(file) => file.getAbsolutePath()
    } mkString (":")

    val testRunpath = runPathString(testClasses)

    val outfileStr = outfile.getAbsolutePath
    val policyFileStr = policyFile.getAbsolutePath
    val agentsStr = agents.map(_.getAbsolutePath)
    val resourceFilesString = resourceFiles.map(_.getAbsolutePath).mkString(":")
    // Deleting the file is helpful: it makes reading the file below crash in case ScalaTest doesn't
    // run as expected. Problem is, it's hard to detect if ScalaTest ran successfully or not: it
    // exits with non-zero if there are failed tests, and also if it crashes...
    new java.io.File(outfileStr).delete()

    def prop(name: String, value: String) = "-D" + name + "=" + value

    // we don't specify "-w packageToTest" - the build file only compiles the tests
    // for the current project. so we don't need to do it again here.
    val cmd = ("java" :: agentsStr.map(agent => s"-javaagent:${agent}")) ++ (
      // "-Djava.security.manager" :: prop("java.security.policy", policyFileStr) ::
      prop(Settings.scalaTestReportFileProperty, outfileStr) ::
      prop(Settings.scalaTestIndividualTestTimeoutProperty, Settings.individualTestTimeout.toString) ::
      prop(Settings.scalaTestReadableFilesProperty, resourceFilesString) ::
      prop(Settings.scalaTestDefaultWeigthProperty, Settings.scalaTestDefaultWeigth.toString) ::
      javaSystemProperties.map((prop _).tupled).toList :::
      "-cp" :: classpathString ::
      "org.scalatest.tools.Runner" ::
      "-R" :: testRunpath ::
      "-C" :: Settings.scalaTestReporter ::
      Nil)

    // process deadlocks in Runner.PassFailReporter.allTestsPassed on runDoneSemaphore.acquire() when
    // something is wrong, e.g. when there's an error.. So we have to run it with a timeout.

    val out = new LimitedStringBuffer()
    var proc: SysProc = null
    try {
      proc = SysProc(cmd).run(ProcessLogger(out.append(_), out.append(_)))
      forkProcess(proc, Settings.scalaTestTimeout)
    } catch {
      case e: TimeoutException =>
        val msg = "Timeout when running ScalaTest\n" + out.toString()
        logError(msg)
        proc.destroy()
        sys.error(msg)

      case e: Throwable =>
        val msg = "Error occurred while running the ScalaTest command\n" + e.toString + "\n" + out.toString()
        logError(msg)
        proc.destroy()
        throw e
    }
    val runLog = out.toString()

    val summaryfileStr = outfileStr + testSummarySuffix
    val summaryCmd = ("java" ::
      "-cp" :: classpathString ::
      "ch.epfl.lamp.grading.GradingSummaryRunner" ::
      outfileStr :: summaryfileStr :: Nil)
    var summaryProc: SysProc = null
    try {
      summaryProc = SysProc(summaryCmd).run()
      summaryProc.exitValue
    } catch {
      case e: Throwable =>
        val msg = "Error occurred while running the test ScalaTest summary command\n" + e.toString
        logError(msg)
        summaryProc.destroy()
        throw e
    }
    val summary = try {
      io.Source.fromFile(summaryfileStr).getLines.mkString("\n").unpickle[GradingSummary]
    } catch {
      case e: Throwable =>
        val msg = "Error occured while reading ScalaTest summary file\n" + e.toString + "\n" + out.toString()
        logError(msg)
        throw e
    }

    (summary.score, summary.maxScore, summary.feedback, runLog)
  }

  def scalaTestGrade(classpath: Classpath, testClasses: File, outfile: File,
    policyFile: File, resourceFiles: List[File], agents: List[File],
    javaSystemProperties: Traversable[(String, String)]) {

    val (score, maxScore, feedback, runLog) =
      runScalaTest(classpath, testClasses, outfile, policyFile,
        resourceFiles, agents, javaSystemProperties,
        GradingFeedback.testExecutionFailed)

    if (score == maxScore) {
      GradingFeedback.allTestsPassed()
    } else {
      val scaledScore = GradingFeedback.maxTestScore * score / maxScore
      GradingFeedback.testsFailed(feedback, scaledScore)
    }

    // The output `out` should in principle be empty: the reporter we use writes its results to a file.
    // however, `out` contains valuable logs in case scalatest fails. We need to put them into the student
    // feedback in order to have a chance of debugging problems.

    if (!runLog.isEmpty) {
      GradingFeedback.testExecutionDebugLog(runLog)
    }
  }
}

