package cromwell.backend.sfs

import akka.actor.{ActorRef, Props}
import cromwell.backend.BackendJobExecutionActor.BackendJobExecutionResponse
import cromwell.backend.{BackendConfigurationDescriptor, BackendInitializationData, BackendJobDescriptor, BackendJobDescriptorKey, BackendLifecycleActorFactory, BackendWorkflowDescriptor}
import cromwell.core.Dispatcher
import cromwell.core.Dispatcher._
import wdl4s.Call
import wdl4s.expression.WdlStandardLibraryFunctions

import scala.concurrent.Promise

/**
  * A factory that can be extended for any shared file system implementation.
  *
  * See the SharedFileSystemAsyncJobExecutionActor for more info.
  */
trait SharedFileSystemBackendLifecycleActorFactory extends BackendLifecycleActorFactory {

  /**
    * Config values for the backend, and a pointer to the global config.
    *
    * This is the single parameter passed into each factory during creation.
    *
    * @return The backend configuration.
    */
  def configurationDescriptor: BackendConfigurationDescriptor

  /**
    * Returns the initialization class, or by default uses the `SharedFileSystemInitializationActor`.
    *
    * @return the initialization class.
    */
  def initializationActorClass: Class[_ <: SharedFileSystemInitializationActor] =
  classOf[SharedFileSystemInitializationActor]

  /**
    * Returns the main engine for async execution.
    *
    * @return the main engine for async execution.
    */
  def asyncJobExecutionActorClass: Class[_ <: SharedFileSystemAsyncJobExecutionActor]

  override def workflowInitializationActorProps(workflowDescriptor: BackendWorkflowDescriptor, calls: Seq[Call],
                                                serviceRegistryActor: ActorRef) = {
    val params = SharedFileSystemInitializationActorParams(serviceRegistryActor, workflowDescriptor,
      configurationDescriptor, calls)
    Option(Props(initializationActorClass, params).withDispatcher(Dispatcher.BackendDispatcher))
  }

  override def jobExecutionActorProps(jobDescriptor: BackendJobDescriptor,
                                      initializationDataOption: Option[BackendInitializationData],
                                      serviceRegistryActor: ActorRef,
                                      backendSingletonActor: Option[ActorRef]) = {
    def propsCreator(completionPromise: Promise[BackendJobExecutionResponse]): Props = {
      val params = SharedFileSystemAsyncJobExecutionActorParams(serviceRegistryActor, jobDescriptor,
        configurationDescriptor, completionPromise, initializationDataOption)
      Props(asyncJobExecutionActorClass, params).withDispatcher(Dispatcher.BackendDispatcher)
    }

    Props(new SharedFileSystemJobExecutionActor(
      jobDescriptor, configurationDescriptor, serviceRegistryActor, propsCreator)
    ).withDispatcher(Dispatcher.BackendDispatcher)
  }

  override def cacheHitCopyingActorProps = Option(cacheHitCopyingActorInner _)

  def cacheHitCopyingActorInner(jobDescriptor: BackendJobDescriptor,
                                initializationDataOption: Option[BackendInitializationData],
                                serviceRegistryActor: ActorRef): Props = {
    Props(
      new SharedFileSystemCacheHitCopyingActor(
        jobDescriptor, configurationDescriptor, initializationDataOption, serviceRegistryActor)
    ).withDispatcher(BackendDispatcher)
  }

  override def expressionLanguageFunctions(workflowDescriptor: BackendWorkflowDescriptor,
                                           jobKey: BackendJobDescriptorKey,
                                           initializationData: Option[BackendInitializationData]):
  WdlStandardLibraryFunctions = {
    SharedFileSystemExpressionFunctions(workflowDescriptor, configurationDescriptor, jobKey, initializationData)
  }
}
