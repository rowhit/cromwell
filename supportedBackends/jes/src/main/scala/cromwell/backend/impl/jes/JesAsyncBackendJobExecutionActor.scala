package cromwell.backend.impl.jes

import java.net.SocketTimeoutException

import akka.actor.ActorRef
import cats.data.Validated.{Invalid, Valid}
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.genomics.model.RunPipelineRequest
import com.google.cloud.storage.contrib.nio.CloudStorageOptions
import cromwell.backend._
import cromwell.backend.impl.jes.RunStatus.TerminalRunStatus
import cromwell.backend.async.{AbortedExecutionHandle, ExecutionHandle, FailedNonRetryableExecutionHandle, FailedRetryableExecutionHandle, PendingExecutionHandle}
import cromwell.backend.impl.jes.errors.FailedToDelocalizeFailure
import cromwell.backend.impl.jes.io._
import cromwell.backend.impl.jes.statuspolling.{JesRunCreationClient, JesStatusRequestClient}
import cromwell.backend.standard.{StandardAsyncExecutionActor, StandardAsyncExecutionActorParams, StandardAsyncJob}
import cromwell.core._
import cromwell.core.logging.JobLogger
import cromwell.core.path.{DefaultPathBuilder, Path}
import cromwell.core.retry.SimpleExponentialBackoff
import cromwell.services.keyvalue.KeyValueServiceActor._
import cromwell.filesystems.gcs.GcsPath
import lenthall.validation.ErrorOr.ErrorOr
import cromwell.filesystems.gcs.batch.GcsBatchCommandBuilder
import cromwell.services.keyvalue.KvClient
import org.slf4j.LoggerFactory
import wdl4s.wdl._
import wdl4s.wdl.expression.PureStandardLibraryFunctions
import wdl4s.wdl.values._

import _root_.io.grpc.Status

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.{Success, Try}

object JesAsyncBackendJobExecutionActor {
  val JesOperationIdKey = "__jes_operation_id"

  object WorkflowOptionKeys {
    val MonitoringScript = "monitoring_script"
    val GoogleProject = "google_project"
    val GoogleComputeServiceAccount = "google_compute_service_account"
  }

  type JesPendingExecutionHandle = PendingExecutionHandle[StandardAsyncJob, Run, RunStatus]

  private val ExtraConfigParamName = "__extra_config_gcs_path"

  private def stringifyMap(m: Map[String, String]): String = m map { case(k, v) => s"  $k -> $v"} mkString "\n"

  val maxUnexpectedRetries = 2

  val JesFailedToDelocalize = 5
  val JesUnexpectedTermination = 13
  val JesPreemption = 14

  def StandardException(errorCode: Status, message: String, jobTag: String) = {
    new Exception(s"Task $jobTag failed. JES error code ${errorCode.getCode.value}. $message")
  }
}

class JesAsyncBackendJobExecutionActor(override val standardParams: StandardAsyncExecutionActorParams)
  extends BackendJobLifecycleActor with StandardAsyncExecutionActor with JesJobCachingActorHelper
    with JesStatusRequestClient with JesRunCreationClient with GcsBatchCommandBuilder with KvClient {

  import JesAsyncBackendJobExecutionActor._

  val slf4jLogger = LoggerFactory.getLogger(JesAsyncBackendJobExecutionActor.getClass)
  val logger = new JobLogger("JesRun", jobDescriptor.workflowDescriptor.id, jobDescriptor.key.tag, None, Set(slf4jLogger))

  val jesBackendSingletonActor: ActorRef =
    standardParams.backendSingletonActorOption.getOrElse(
      throw new RuntimeException("JES Backend actor cannot exist without the JES backend singleton actor"))

  override type StandardAsyncRunInfo = Run

  override type StandardAsyncRunStatus = RunStatus

  override val pollingActor: ActorRef = jesBackendSingletonActor

  override lazy val pollBackOff = SimpleExponentialBackoff(
    initialInterval = 30 seconds, maxInterval = jesAttributes.maxPollingInterval seconds, multiplier = 1.1)

  override lazy val executeOrRecoverBackOff = SimpleExponentialBackoff(
    initialInterval = 3 seconds, maxInterval = 20 seconds, multiplier = 1.1)

  private lazy val cmdInput =
    JesFileInput(JesJobPaths.JesExecParamName, jesCallPaths.script.pathAsString, DefaultPathBuilder.get(jesCallPaths.scriptFilename), workingDisk)
  private lazy val jesCommandLine = s"/bin/bash ${cmdInput.containerPath}"
  private lazy val rcJesOutput = JesFileOutput(returnCodeFilename, returnCodeGcsPath.pathAsString, DefaultPathBuilder.get(returnCodeFilename), workingDisk)

  private lazy val standardParameters = Seq(rcJesOutput)

  private lazy val dockerConfiguration = jesConfiguration.dockerCredentials

  private val previousRetryReasons: ErrorOr[PreviousRetryReasons] = PreviousRetryReasons.tryApply(jobDescriptor.prefetchedKvStoreEntries, jobDescriptor.key.attempt)

  private lazy val jobDockerImage = jobDescriptor.maybeCallCachingEligible.dockerHash.getOrElse(runtimeAttributes.dockerImage)
  
  override lazy val dockerImageUsed: Option[String] = Option(jobDockerImage)
  
  override val preemptible: Boolean = previousRetryReasons match {
    case Valid(PreviousRetryReasons(p, _)) => p < maxPreemption
    case _ => false
  }

  override def tryAbort(job: StandardAsyncJob): Unit = {
    Run(job, initializationData.genomics).abort()
  }

  override def requestsAbortAndDiesImmediately: Boolean = true

  override def receive: Receive = pollingActorClientReceive orElse runCreationClientReceive orElse ioReceive orElse kvClientReceive orElse super.receive

  private def gcsAuthParameter: Option[JesInput] = {
    if (jesAttributes.auths.gcs.requiresAuthFile || dockerConfiguration.isDefined)
      Option(JesLiteralInput(ExtraConfigParamName, jesCallPaths.workflowPaths.gcsAuthFilePath.pathAsString))
    else None
  }

  /**
    * Takes two arrays of remote and local WDL File paths and generates the necessary JesInputs.
    */
  private def jesInputsFromWdlFiles(jesNamePrefix: String,
                                    remotePathArray: Seq[WdlFile],
                                    localPathArray: Seq[WdlFile],
                                    jobDescriptor: BackendJobDescriptor): Iterable[JesInput] = {
    (remotePathArray zip localPathArray zipWithIndex) flatMap {
      case ((remotePath, localPath), index) =>
        Seq(JesFileInput(s"$jesNamePrefix-$index", remotePath.valueString, DefaultPathBuilder.get(localPath.valueString), workingDisk))
    }
  }

  /**
    * Turns WdlFiles into relative paths.  These paths are relative to the working disk
    *
    * relativeLocalizationPath("foo/bar.txt") -> "foo/bar.txt"
    * relativeLocalizationPath("gs://some/bucket/foo.txt") -> "some/bucket/foo.txt"
    */
  private def relativeLocalizationPath(file: WdlFile): WdlFile = {
    getPath(file.value) match {
      case Success(path) => WdlFile(path.pathWithoutScheme, file.isGlob)
      case _ => file
    }
  }

  private[jes] def generateJesInputs(jobDescriptor: BackendJobDescriptor): Set[JesInput] = {

    val fullyQualifiedPreprocessedInputs = jobDescriptor.inputDeclarations map { case (declaration, value) => declaration.fullyQualifiedName -> commandLineValueMapper(value) }
    val writeFunctionFiles = call.task.evaluateFilesFromCommand(fullyQualifiedPreprocessedInputs, backendEngineFunctions) map {
      case (expression, file) => expression.toWdlString.md5SumShort -> Seq(file)
    }

    /* Collect all WdlFiles from inputs to the call */
    val callInputFiles: Map[FullyQualifiedName, Seq[WdlFile]] = jobDescriptor.fullyQualifiedInputs mapValues {
      _.collectAsSeq { case w: WdlFile => w }
    }

    val inputs = (callInputFiles ++ writeFunctionFiles) flatMap {
      case (name, files) => jesInputsFromWdlFiles(name, files, files.map(relativeLocalizationPath), jobDescriptor)
    }
    inputs.toSet
  }

  /**
    * Given a path (relative or absolute), returns a (Path, JesAttachedDisk) tuple where the Path is
    * relative to the AttachedDisk's mount point
    *
    * @throws Exception if the `path` does not live in one of the supplied `disks`
    */
  private def relativePathAndAttachedDisk(path: String, disks: Seq[JesAttachedDisk]): (Path, JesAttachedDisk) = {
    val absolutePath = DefaultPathBuilder.get(path) match {
      case p if !p.isAbsolute => JesWorkingDisk.MountPoint.resolve(p)
      case p => p
    }

    disks.find(d => absolutePath.startsWith(d.mountPoint)) match {
      case Some(disk) => (disk.mountPoint.relativize(absolutePath), disk)
      case None =>
        throw new Exception(s"Absolute path $path doesn't appear to be under any mount points: ${disks.map(_.toString).mkString(", ")}")
    }
  }

  /**
    * If the desired reference name is too long, we don't want to break JES or risk collisions by arbitrary truncation. So,
    * just use a hash. We only do this when needed to give better traceability in the normal case.
    */
  private def makeSafeJesReferenceName(referenceName: String) = {
    if (referenceName.length <= 127) referenceName else referenceName.md5Sum
  }

  private[jes] def generateJesOutputs(jobDescriptor: BackendJobDescriptor): Set[JesFileOutput] = {
    val wdlFileOutputs = call.task.findOutputFiles(jobDescriptor.fullyQualifiedInputs, PureStandardLibraryFunctions) map relativeLocalizationPath

    val outputs = wdlFileOutputs.distinct flatMap { wdlFile =>
      wdlFile match {
        case singleFile: WdlSingleFile => List(generateJesSingleFileOutputs(singleFile))
        case globFile: WdlGlobFile => generateJesGlobFileOutputs(globFile)
      }
    }

    outputs.toSet
  }

  private def generateJesSingleFileOutputs(wdlFile: WdlSingleFile): JesFileOutput = {
    val destination = callRootPath.resolve(wdlFile.value.stripPrefix("/")).pathAsString
    val (relpath, disk) = relativePathAndAttachedDisk(wdlFile.value, runtimeAttributes.disks)
    JesFileOutput(makeSafeJesReferenceName(wdlFile.value), destination, relpath, disk)
  }

  private def generateJesGlobFileOutputs(wdlFile: WdlGlobFile): List[JesFileOutput] = {
    val globName = backendEngineFunctions.globName(wdlFile.value)
    val globDirectory = globName + "/"
    val globListFile = globName + ".list"
    val gcsGlobDirectoryDestinationPath = callRootPath.resolve(globDirectory).pathAsString
    val gcsGlobListFileDestinationPath = callRootPath.resolve(globListFile).pathAsString

    val (_, globDirectoryDisk) = relativePathAndAttachedDisk(wdlFile.value, runtimeAttributes.disks)

    // We need both the glob directory and the glob list:
    List(
      // The glob directory:
      JesFileOutput(makeSafeJesReferenceName(globDirectory), gcsGlobDirectoryDestinationPath, DefaultPathBuilder.get(globDirectory + "*"), globDirectoryDisk),
      // The glob list file:
      JesFileOutput(makeSafeJesReferenceName(globListFile), gcsGlobListFileDestinationPath, DefaultPathBuilder.get(globListFile), globDirectoryDisk)
    )
  }

  lazy val jesMonitoringParamName: String = JesJobPaths.JesMonitoringKey
  lazy val localMonitoringLogPath: Path = DefaultPathBuilder.get(jesCallPaths.jesMonitoringLogFilename)
  lazy val localMonitoringScriptPath: Path =  DefaultPathBuilder.get(jesCallPaths.jesMonitoringScriptFilename)

  lazy val monitoringScript: Option[JesInput] = {
    jesCallPaths.workflowPaths.monitoringScriptPath map { path =>
      JesFileInput(s"$jesMonitoringParamName-in", path.pathAsString, localMonitoringScriptPath, workingDisk)
    }
  }

  lazy val monitoringOutput: Option[JesFileOutput] = monitoringScript map { _ => JesFileOutput(s"$jesMonitoringParamName-out",
    jesCallPaths.jesMonitoringLogPath.pathAsString, localMonitoringLogPath, workingDisk)
  }

  override lazy val commandDirectory: Path = JesWorkingDisk.MountPoint

  private val DockerMonitoringLogPath: Path = JesWorkingDisk.MountPoint.resolve(jesCallPaths.jesMonitoringLogFilename)
  private val DockerMonitoringScriptPath: Path = JesWorkingDisk.MountPoint.resolve(jesCallPaths.jesMonitoringScriptFilename)

  override def commandScriptPreamble: String = {
    if (monitoringOutput.isDefined) {
      s"""|touch $DockerMonitoringLogPath
          |chmod u+x $DockerMonitoringScriptPath
          |$DockerMonitoringScriptPath > $DockerMonitoringLogPath &""".stripMargin
    } else ""
  }

  override def globParentDirectory(wdlGlobFile: WdlGlobFile): Path = {
    val (_, disk) = relativePathAndAttachedDisk(wdlGlobFile.value, runtimeAttributes.disks)
    disk.mountPoint
  }

  private def googleProject(descriptor: BackendWorkflowDescriptor): String = {
    descriptor.workflowOptions.getOrElse(WorkflowOptionKeys.GoogleProject, jesAttributes.project)
  }

  private def computeServiceAccount(descriptor: BackendWorkflowDescriptor): String = {
    descriptor.workflowOptions.getOrElse(WorkflowOptionKeys.GoogleComputeServiceAccount, jesAttributes.computeServiceAccount)
  }

  override def isTerminal(runStatus: RunStatus): Boolean = {
    runStatus match {
      case _: TerminalRunStatus => true
      case _ => false
    }
  }

  private def createJesRunPipelineRequest(jesParameters: Seq[JesParameter]): RunPipelineRequest = {
    val runPipelineParameters = Run.makeRunPipelineRequest(
      jobDescriptor = jobDescriptor,
      runtimeAttributes = runtimeAttributes,
      dockerImage = jobDockerImage,
      callRootPath = callRootPath.pathAsString,
      commandLine = jesCommandLine,
      logFileName = jesLogFilename,
      jesParameters,
      googleProject(jobDescriptor.workflowDescriptor),
      computeServiceAccount(jobDescriptor.workflowDescriptor),
      backendLabels,
      preemptible,
      initializationData.genomics
    )
    logger.debug(s"Inputs:\n${stringifyMap(runPipelineParameters.getPipelineArgs.getInputs.asScala.toMap)}")
    logger.debug(s"Outputs:\n${stringifyMap(runPipelineParameters.getPipelineArgs.getOutputs.asScala.toMap)}")
    runPipelineParameters
  }

  override def isFatal(throwable: Throwable): Boolean = super.isFatal(throwable) || isFatalJesException(throwable)

  override def isTransient(throwable: Throwable): Boolean = isTransientJesException(throwable)

  override def executeAsync(): Future[ExecutionHandle] = runWithJes(None)

  val futureKvJobKey = KvJobKey(jobDescriptor.key.call.fullyQualifiedName, jobDescriptor.key.index, jobDescriptor.key.attempt + 1)

  override def recoverAsync(jobId: StandardAsyncJob): Future[ExecutionHandle] = runWithJes(Option(jobId))

  private def runWithJes(jobForResumption: Option[StandardAsyncJob]): Future[ExecutionHandle] = {
    // Want to force runtimeAttributes to evaluate so we can fail quickly now if we need to:
    def evaluateRuntimeAttributes = Future.fromTry(Try(runtimeAttributes))

    def generateJesParameters = Future.fromTry( Try {
      val generatedJesInputs = generateJesInputs(jobDescriptor)
      val jesInputs: Set[JesInput] = generatedJesInputs ++ monitoringScript + cmdInput
      val jesOutputs: Set[JesFileOutput] = generateJesOutputs(jobDescriptor) ++ monitoringOutput

      standardParameters ++ gcsAuthParameter ++ jesInputs ++ jesOutputs
    })

    def uploadScriptFile = writeAsync(jobPaths.script, commandScriptContents, Seq(CloudStorageOptions.withMimeType("text/plain")))

    def makeRpr(jesParameters: Seq[JesParameter]) = Future.fromTry(Try {
      createJesRunPipelineRequest(jesParameters)
    })

    jobForResumption match {
      case Some(job) =>
        val run = Run(job, initializationData.genomics)
        Future.successful(PendingExecutionHandle(jobDescriptor, job, Option(run), previousStatus = None))
      case None =>
        for {
          _ <- evaluateRuntimeAttributes
          jesParameters <- generateJesParameters
          _ <- uploadScriptFile
          rpr <- makeRpr(jesParameters)
          runId <- runPipeline(initializationData.genomics, rpr)
          run = Run(runId, initializationData.genomics)
        } yield PendingExecutionHandle(jobDescriptor, runId, Option(run), previousStatus = None)
    }
  }

  override def pollStatusAsync(handle: JesPendingExecutionHandle): Future[RunStatus] = super[JesStatusRequestClient].pollStatus(handle.runInfo.get)

  override def customPollStatusFailure: PartialFunction[(ExecutionHandle, Exception), ExecutionHandle] = {
    case (oldHandle: JesPendingExecutionHandle@unchecked, e: GoogleJsonResponseException) if e.getStatusCode == 404 =>
      jobLogger.error(s"JES Job ID ${oldHandle.runInfo.get.job} has not been found, failing call")
      FailedNonRetryableExecutionHandle(e)
  }

  override lazy val startMetadataKeyValues: Map[String, Any] = super[JesJobCachingActorHelper].startMetadataKeyValues

  override def getTerminalMetadata(runStatus: RunStatus): Map[String, Any] = {
    runStatus match {
      case terminalRunStatus: TerminalRunStatus =>
        Map(
          JesMetadataKeys.MachineType -> terminalRunStatus.machineType.getOrElse("unknown"),
          JesMetadataKeys.InstanceName -> terminalRunStatus.instanceName.getOrElse("unknown"),
          JesMetadataKeys.Zone -> terminalRunStatus.zone.getOrElse("unknown")
        )
      case unknown => throw new RuntimeException(s"Attempt to get terminal metadata from non terminal status: $unknown")
    }
  }

  override def mapOutputWdlFile(wdlFile: WdlFile): WdlFile = {
    wdlFileToGcsPath(generateJesOutputs(jobDescriptor))(wdlFile)
  }

  private[jes] def wdlFileToGcsPath(jesOutputs: Set[JesFileOutput])(wdlFile: WdlFile): WdlFile = {
    jesOutputs collectFirst {
      case jesOutput if jesOutput.name == makeSafeJesReferenceName(wdlFile.valueString) => WdlFile(jesOutput.gcs)
    } getOrElse wdlFile
  }

  override def isSuccess(runStatus: RunStatus): Boolean = {
    runStatus match {
      case _: RunStatus.Success => true
      case _: RunStatus.UnsuccessfulRunStatus => false
      case _ => throw new RuntimeException(s"Cromwell programmer blunder: isSuccess was called on an incomplete RunStatus ($runStatus).")
    }
  }

  override def getTerminalEvents(runStatus: RunStatus): Seq[ExecutionEvent] = {
    runStatus match {
      case successStatus: RunStatus.Success => successStatus.eventList
      case unknown =>
        throw new RuntimeException(s"handleExecutionSuccess not called with RunStatus.Success. Instead got $unknown")
    }
  }

  override def retryEvaluateOutputs(exception: Exception): Boolean = {
    exception match {
      case aggregated: CromwellAggregatedException =>
        aggregated.throwables.collectFirst { case s: SocketTimeoutException => s }.isDefined
      case _ => false
    }
  }

  // If one exists, extract the JES error code (not the google RPC) from the error message
  private[jes] def getJesErrorCode(errorMessage: String): Option[Int] = {
    Try { errorMessage.substring(0, errorMessage.indexOf(':')).toInt } toOption
  }

  override def handleExecutionFailure(runStatus: RunStatus,
                                      handle: StandardAsyncPendingExecutionHandle,
                                      returnCode: Option[Int]): Future[ExecutionHandle] = {
    // Inner function: Handles a 'Failed' runStatus (or Preempted if preemptible was false)
    def handleFailedRunStatus(runStatus: RunStatus.UnsuccessfulRunStatus,
                              handle: StandardAsyncPendingExecutionHandle,
                              returnCode: Option[Int]): Future[ExecutionHandle] = {
      (runStatus.errorCode, runStatus.jesCode) match {
        case (Status.CANCELLED, None) => Future.successful(AbortedExecutionHandle)
        case (Status.NOT_FOUND, Some(JesFailedToDelocalize)) => Future.successful(FailedNonRetryableExecutionHandle(FailedToDelocalizeFailure(runStatus.prettyPrintedError, jobTag, Option(jobPaths.stderr))))
        case (Status.ABORTED, Some(JesUnexpectedTermination)) => handleUnexpectedTermination(runStatus.errorCode, runStatus.prettyPrintedError, returnCode)
        case _ => Future.successful(FailedNonRetryableExecutionHandle(StandardException(runStatus.errorCode, runStatus.prettyPrintedError, jobTag), returnCode))
      }
    }

    runStatus match {
      case preemptedStatus: RunStatus.Preempted if preemptible => handlePreemption(preemptedStatus, returnCode)
      case failedStatus: RunStatus.UnsuccessfulRunStatus => handleFailedRunStatus(failedStatus, handle, returnCode)
      case unknown => throw new RuntimeException(s"handleExecutionFailure not called with RunStatus.Failed or RunStatus.Preempted. Instead got $unknown")
    }
  }

  private def writeFuturePreemptedAndUnexpectedRetryCounts(p: Int, ur: Int): Future[Unit] = {
    val updateRequests = Seq(
      KvPut(KvPair(ScopedKey(workflowId, futureKvJobKey, JesBackendLifecycleActorFactory.unexpectedRetryCountKey), Option(ur.toString))),
      KvPut(KvPair(ScopedKey(workflowId, futureKvJobKey, JesBackendLifecycleActorFactory.preemptionCountKey), Option(p.toString)))
    )

    makeKvRequest(updateRequests).map(_ => ())
  }

  private def handleUnexpectedTermination(errorCode: Status, errorMessage: String, jobReturnCode: Option[Int]): Future[ExecutionHandle] = {

    val msg = s"Retrying. $errorMessage"

    previousRetryReasons match {
      case Valid(PreviousRetryReasons(p, ur)) =>
        val thisUnexpectedRetry = ur + 1
        if (thisUnexpectedRetry <= maxUnexpectedRetries) {
          // Increment unexpected retry count and preemption count stays the same
          writeFuturePreemptedAndUnexpectedRetryCounts(p, thisUnexpectedRetry).map { _ =>
            FailedRetryableExecutionHandle(StandardException(errorCode, msg, jobTag), jobReturnCode)
          }
        }
        else {
          Future.successful(FailedNonRetryableExecutionHandle(StandardException(errorCode, errorMessage, jobTag), jobReturnCode))
        }
      case Invalid(_) =>
        Future.successful(FailedNonRetryableExecutionHandle(StandardException(errorCode, errorMessage, jobTag), jobReturnCode))
    }
  }

  private def handlePreemption(runStatus: RunStatus.Preempted, jobReturnCode: Option[Int]): Future[ExecutionHandle] = {
    import lenthall.numeric.IntegerUtil._

    val errorCode: Status = runStatus.errorCode
    val prettyPrintedError: String = runStatus.prettyPrintedError
    previousRetryReasons match {
      case Valid(PreviousRetryReasons(p, ur)) =>
        val thisPreemption = p + 1
        val taskName = s"${workflowDescriptor.id}:${call.unqualifiedName}"
        val baseMsg = s"Task $taskName was preempted for the ${thisPreemption.toOrdinal} time."

        writeFuturePreemptedAndUnexpectedRetryCounts(thisPreemption, ur).map { _ =>
          if (thisPreemption < maxPreemption) {
            // Increment preemption count and unexpectedRetryCount stays the same
            val msg = s"""$baseMsg The call will be restarted with another preemptible VM (max preemptible attempts number is $maxPreemption). Error code $errorCode.$prettyPrintedError""".stripMargin
            FailedRetryableExecutionHandle(StandardException(errorCode, msg, jobTag), jobReturnCode)
          }
          else {
            val msg = s"""$baseMsg The maximum number of preemptible attempts ($maxPreemption) has been reached. The call will be restarted with a non-preemptible VM. Error code $errorCode.$prettyPrintedError)""".stripMargin
            FailedRetryableExecutionHandle(StandardException(errorCode, msg, jobTag), jobReturnCode)
          }
        }
      case Invalid(_) =>
        Future.successful(FailedNonRetryableExecutionHandle(StandardException(errorCode, prettyPrintedError, jobTag), jobReturnCode))
    }
  }

  override def mapCommandLineWdlFile(wdlFile: WdlFile): WdlFile = {
    getPath(wdlFile.valueString) match {
      case Success(gcsPath: GcsPath) =>
        val localPath = workingDisk.mountPoint.resolve(gcsPath.pathWithoutScheme).pathAsString
        WdlFile(localPath, wdlFile.isGlob)
      case _ => wdlFile
    }
  }
}
