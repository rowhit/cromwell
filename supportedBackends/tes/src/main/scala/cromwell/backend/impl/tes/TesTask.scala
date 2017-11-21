package cromwell.backend.impl.tes

import cromwell.backend.io.GlobFunctions
import cromwell.backend.{BackendConfigurationDescriptor, BackendJobDescriptor}
import cromwell.core.NoIoFunctionSet
import cromwell.core.logging.JobLogger
import cromwell.core.path.{DefaultPathBuilder, Path}
import wdl.FullyQualifiedName
import wdl4s.parser.MemoryUnit
import wom.InstantiatedCommand
import wom.callable.Callable.OutputDefinition
import wom.values._

import scala.language.postfixOps
import scala.util.Try

final case class TesTask(jobDescriptor: BackendJobDescriptor,
                         configurationDescriptor: BackendConfigurationDescriptor,
                         jobLogger: JobLogger,
                         tesPaths: TesJobPaths,
                         runtimeAttributes: TesRuntimeAttributes,
                         containerWorkDir: Path,
                         commandScriptContents: String,
                         instantiatedCommand: InstantiatedCommand,
                         dockerImageUsed: String) {

  private val workflowDescriptor = jobDescriptor.workflowDescriptor
  private val workflowName = workflowDescriptor.callable.name
  private val fullyQualifiedTaskName = jobDescriptor.call.fullyQualifiedName
  val name: String = fullyQualifiedTaskName
  val description: String = jobDescriptor.toString

  // TODO validate "project" field of workflowOptions
  val project = {
    workflowDescriptor.workflowOptions.getOrElse("project", "")
  }

  // contains the script to be executed
  private val commandScript = TaskParameter(
    Option("commandScript"),
    Option(fullyQualifiedTaskName + ".commandScript"),
    None,
    tesPaths.callExecutionDockerRoot.resolve("script").toString,
    Option("FILE"),
    Option(commandScriptContents)
  )

  private val commandScriptOut = commandScript.copy(
    url = Option(tesPaths.script.toString),
    contents = None
  )

  private def writeFunctionFiles: Map[FullyQualifiedName, Seq[WomFile]] =
    instantiatedCommand.createdFiles map { f => f.value.md5SumShort -> List(f) } toMap

  private val callInputFiles: Map[FullyQualifiedName, Seq[WomFile]] = jobDescriptor
    .fullyQualifiedInputs
    .mapValues {
      _.collectAsSeq { case w: WomFile => w }
    }

  def inputs(commandLineValueMapper: WomValue => WomValue): Seq[TaskParameter] =
    (callInputFiles ++ writeFunctionFiles).flatMap {
      case (fullyQualifiedName, files) => files.zipWithIndex.map {
        case (f, index) => TaskParameter(
          Option(fullyQualifiedName + "." + index),
          Option(workflowName + "." + fullyQualifiedName + "." + index),
          Option(f.value),
          tesPaths.containerInput(f.value),
          Option("FILE"),
          None
        )
      }
    }.toList ++ Seq(commandScript)

  // TODO add TES logs to standard outputs
  private val standardOutputs = Seq("rc", "stdout", "stderr").map {
    f =>
      TaskParameter(
        Option(f),
        Option(fullyQualifiedTaskName + "." + f),
        Option(tesPaths.storageOutput(f)),
        tesPaths.containerOutput(containerWorkDir, f),
        Option("FILE"),
        None
      )
  }

  // TODO extract output file variable names and match with Files below
  // The problem is that we only care about the files CREATED, so stdout and input redirects are ignored and
  // thus we can't directly match the names returned here to the files returned below. Also we have to consider Arrays
  //
  //  private val outputFileNames = jobDescriptor.call.task.outputs
  //    .filter(o => o.womType.toWdlString == "Array[File]" || o.womType.toWdlString == "File")
  //    .map(_.unqualifiedName)

  // extract output files
  // if output paths are absolute we will ignore them here and assume they are redirects
  private val outputWomFiles: Seq[WomFile] = {
    import cats.syntax.validated._
    // TODO WOM: this should be pushed back into WOM.
    // It's also a mess, evaluateFiles returns an ErrorOr but can still throw. We might want to use an EitherT, although
    // if it fails we just want to fallback to an empty list anyway...
    def evaluateFiles(output: OutputDefinition): List[WomFile] = {
      Try (
        output.expression.evaluateFiles(jobDescriptor.localInputs, NoIoFunctionSet, output.womType).map(_.toList)
      ).getOrElse(List.empty[WomFile].validNel)
       .getOrElse(List.empty)
    }
    
    jobDescriptor.call.callable.outputs
      .flatMap(evaluateFiles)
      .filter(o => !DefaultPathBuilder.get(o.valueString).isAbsolute)
  }

  private val womOutputs = outputWomFiles
    .zipWithIndex
    .flatMap {
      case (f: WomSingleFile, index) =>
        val outputFile = f.value
        Seq(
          TaskParameter(
            Option(fullyQualifiedTaskName + ".output." + index),
            Option(fullyQualifiedTaskName + ".output." + index),
            Option(tesPaths.storageOutput(outputFile)),
            tesPaths.containerOutput(containerWorkDir, outputFile),
            Option("FILE"),
            None
          )
        )
      case (g: WomGlobFile, index) =>
        val globName = GlobFunctions.globName(g.value)
        val globDirName = "globDir." + index
        val globDirectory = globName + "/"
        val globListName =  "globList." + index
        val globListFile = globName + ".list"
        Seq(
          TaskParameter(
            Option(globDirName),
            Option(fullyQualifiedTaskName + "." + globDirName),
            Option(tesPaths.storageOutput(globDirectory)),
            tesPaths.containerOutput(containerWorkDir, globDirectory),
            Option("DIRECTORY"),
            None
          ),
          TaskParameter(
            Option(globListName),
            Option(fullyQualifiedTaskName + "." + globListName),
            Option(tesPaths.storageOutput(globListFile)),
            tesPaths.containerOutput(containerWorkDir, globListFile),
            Option("FILE"),
            None
          )
        )
    }

  val outputs: Seq[TaskParameter] = womOutputs ++ standardOutputs ++ Seq(commandScriptOut)

  private val disk :: ram :: _ = Seq(runtimeAttributes.disk, runtimeAttributes.memory) map {
    case Some(x) =>
      Option(x.to(MemoryUnit.GB).amount)
    case None =>
      None
  }

  val resources = Resources(
    runtimeAttributes.cpu,
    ram,
    disk,
    Option(false),
    None
  )

  val executors = Seq(Executor(
    dockerImageUsed,
    Seq("/bin/bash", commandScript.path),
    runtimeAttributes.dockerWorkingDir,
    Option(tesPaths.containerOutput(containerWorkDir, "stdout")),
    Option(tesPaths.containerOutput(containerWorkDir, "stderr")),
    None,
    None,
    None
  ))
}

// Field requirements in classes below based off GA4GH schema
final case class Task(id: Option[String],
                      state: Option[String],
                      name: Option[String],
                      description: Option[String],
                      project: Option[String],
                      inputs: Option[Seq[TaskParameter]],
                      outputs: Option[Seq[TaskParameter]],
                      resources: Option[Resources],
                      executors: Seq[Executor],
                      volumes: Option[Seq[String]],
                      tags: Option[Map[String, String]],
                      logs: Option[Seq[TaskLog]])

final case class Executor(image_name: String,
                          cmd: Seq[String],
                          workdir: Option[String],
                          stdout: Option[String],
                          stderr: Option[String],
                          stdin: Option[String],
                          environ: Option[Map[String, String]],
                          ports: Option[Seq[Ports]])

final case class TaskParameter(name: Option[String],
                               description: Option[String],
                               url: Option[String],
                               path: String,
                               `type`: Option[String],
                               contents: Option[String])

final case class Resources(cpu_cores: Option[Int],
                           ram_gb: Option[Double],
                           size_gb: Option[Double],
                           preemptible: Option[Boolean],
                           zones: Option[Seq[String]])

final case class OutputFileLog(url: String,
                               path: String,
                               size_bytes: Int)

final case class TaskLog(start_time: Option[String],
                         end_time: Option[String],
                         metadata: Option[Map[String, String]],
                         logs: Option[Seq[ExecutorLog]],
                         outputs: Option[Seq[OutputFileLog]])

final case class ExecutorLog(start_time: Option[String],
                             end_time: Option[String],
                             stdout: Option[String],
                             stderr: Option[String],
                             exit_code: Option[Int],
                             host_ip: Option[String],
                             ports: Option[Seq[Ports]])

final case class Ports(host: Option[String],
                       container: String)
