/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.rmcontainer;

import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.apache.commons.lang.time.DateUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerExitStatus;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerReport;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.server.api.protocolrecords.NMContainerStatus;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppRunningOnNodeEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttempt;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.event.RMAppAttemptContainerAllocatedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.event.RMAppAttemptContainerFinishedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNodeCleanContainerEvent;
import org.apache.hadoop.yarn.state.InvalidStateTransitonException;
import org.apache.hadoop.yarn.state.MultipleArcTransition;
import org.apache.hadoop.yarn.state.SingleArcTransition;
import org.apache.hadoop.yarn.state.StateMachine;
import org.apache.hadoop.yarn.state.StateMachineFactory;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.apache.hadoop.yarn.webapp.util.WebAppUtils;

@SuppressWarnings({"unchecked", "rawtypes"})
public class RMContainerImpl implements RMContainer {

	private static final Log LOG = LogFactory.getLog(RMContainerImpl.class);

	private static final StateMachineFactory<RMContainerImpl, RMContainerState,
		RMContainerEventType, RMContainerEvent>
		stateMachineFactory = new StateMachineFactory<RMContainerImpl,
		RMContainerState, RMContainerEventType, RMContainerEvent>(
		RMContainerState.NEW)

		// Transitions from NEW state
		.addTransition(RMContainerState.NEW, RMContainerState.ALLOCATED,
			RMContainerEventType.START, new ContainerStartedTransition())
		.addTransition(RMContainerState.NEW, RMContainerState.KILLED,
			RMContainerEventType.KILL)
		.addTransition(RMContainerState.NEW, RMContainerState.RESERVED,
			RMContainerEventType.RESERVED, new ContainerReservedTransition())
		.addTransition(RMContainerState.NEW,
			EnumSet.of(RMContainerState.RUNNING, RMContainerState.COMPLETED),
			RMContainerEventType.RECOVER, new ContainerRecoveredTransition())

		// Transitions from RESERVED state
		.addTransition(RMContainerState.RESERVED, RMContainerState.RESERVED,
			RMContainerEventType.RESERVED, new ContainerReservedTransition())
		.addTransition(RMContainerState.RESERVED, RMContainerState.ALLOCATED,
			RMContainerEventType.START, new ContainerStartedTransition())
		.addTransition(RMContainerState.RESERVED, RMContainerState.KILLED,
			RMContainerEventType.KILL) // nothing to do
		.addTransition(RMContainerState.RESERVED, RMContainerState.RELEASED,
			RMContainerEventType.RELEASED) // nothing to do


		// Transitions from ALLOCATED state
		.addTransition(RMContainerState.ALLOCATED, RMContainerState.ACQUIRED,
			RMContainerEventType.ACQUIRED, new AcquiredTransition())
		.addTransition(RMContainerState.ALLOCATED, RMContainerState.EXPIRED,
			RMContainerEventType.EXPIRE, new FinishedTransition())
		.addTransition(RMContainerState.ALLOCATED, RMContainerState.KILLED,
			RMContainerEventType.KILL, new FinishedTransition())

		// Transitions from ACQUIRED state
		.addTransition(RMContainerState.ACQUIRED, RMContainerState.RUNNING,
			RMContainerEventType.LAUNCHED, new LaunchedTransition())
		.addTransition(RMContainerState.ACQUIRED, RMContainerState.COMPLETED,
			RMContainerEventType.FINISHED, new ContainerFinishedAtAcquiredState())
		.addTransition(RMContainerState.ACQUIRED, RMContainerState.RELEASED,
			RMContainerEventType.RELEASED, new KillTransition())
		.addTransition(RMContainerState.ACQUIRED, RMContainerState.EXPIRED,
			RMContainerEventType.EXPIRE, new KillTransition())
		.addTransition(RMContainerState.ACQUIRED, RMContainerState.KILLED,
			RMContainerEventType.KILL, new KillTransition())

		// Transitions from RUNNING state
		.addTransition(RMContainerState.RUNNING, RMContainerState.COMPLETED,
			RMContainerEventType.FINISHED, new FinishedTransition())
		.addTransition(RMContainerState.RUNNING, RMContainerState.DEHYDRATED,
			RMContainerEventType.SUSPEND, new ContainerSuspendTransition())
		.addTransition(RMContainerState.RUNNING, RMContainerState.KILLED,
			RMContainerEventType.KILL, new KillTransition())
		.addTransition(RMContainerState.RUNNING, RMContainerState.RELEASED,
			RMContainerEventType.RELEASED, new KillTransition())
		.addTransition(RMContainerState.RUNNING, RMContainerState.RUNNING,
			RMContainerEventType.EXPIRE)

		//Transition from DEHYDRATED state
		.addTransition(RMContainerState.DEHYDRATED, EnumSet.of(RMContainerState.RUNNING,
			RMContainerState.DEHYDRATED),
			RMContainerEventType.RESUME, new ContainerResumeTransition())
		.addTransition(RMContainerState.DEHYDRATED, RMContainerState.DEHYDRATED,
			RMContainerEventType.SUSPEND, new ContainerSuspendTransition())
		.addTransition(RMContainerState.DEHYDRATED, RMContainerState.COMPLETED,
			RMContainerEventType.FINISHED, new FinishedTransition())
		.addTransition(RMContainerState.DEHYDRATED, RMContainerState.KILLED,
			RMContainerEventType.KILL, new KillTransition())
		.addTransition(RMContainerState.DEHYDRATED, RMContainerState.RELEASED,
			RMContainerEventType.RELEASED, new KillTransition())
		.addTransition(RMContainerState.DEHYDRATED, RMContainerState.DEHYDRATED,
			RMContainerEventType.EXPIRE)

		// Transitions from COMPLETED state
		.addTransition(RMContainerState.COMPLETED, RMContainerState.COMPLETED,
			EnumSet.of(RMContainerEventType.EXPIRE, RMContainerEventType.RELEASED,
				RMContainerEventType.KILL))

		// Transitions from EXPIRED state
		.addTransition(RMContainerState.EXPIRED, RMContainerState.EXPIRED,
			EnumSet.of(RMContainerEventType.RELEASED, RMContainerEventType.KILL))

		// Transitions from RELEASED state
		.addTransition(RMContainerState.RELEASED, RMContainerState.RELEASED,
			EnumSet.of(RMContainerEventType.EXPIRE, RMContainerEventType.RELEASED,
				RMContainerEventType.KILL, RMContainerEventType.FINISHED))

		// Transitions from KILLED state
		.addTransition(RMContainerState.KILLED, RMContainerState.KILLED,
			EnumSet.of(RMContainerEventType.EXPIRE, RMContainerEventType.RELEASED,
				RMContainerEventType.KILL, RMContainerEventType.FINISHED))

		// create the topology tables
		.installTopology();

	/**
	 * 状态机的意思吧
	 */
	private final StateMachine<RMContainerState, RMContainerEventType,
		RMContainerEvent> stateMachine;
	private final ReadLock readLock;
	private final WriteLock writeLock;
	private final ContainerId containerId;
	private final ApplicationAttemptId appAttemptId;
	private final NodeId nodeId;
	private final Container container;
	private final RMContext rmContext;
	private final EventHandler eventHandler;
	private final ContainerAllocationExpirer containerAllocationExpirer;
	private final String user;

	/**
	 * preempted Resource
	 */
	private Resource preempted = Resource.newInstance(0, 0);
	private Resource lastPreempted;
	private Resource lastResumed;
	private Resource reservedResource;
	private NodeId reservedNode;
	private Priority reservedPriority;
	private long creationTime;
	private long finishTime;
	private int resumeOpportunity;

	static public int PR_NUMBER = 2;

	private boolean isSuspending = false;

	/**
	 * record suspend time(may preempt for many times)
	 */
	private List<Long> suspendTime;
	/**
	 * record resume time
	 */
	private List<Long> resumeTime;
	/**
	 * record container utilization
	 */
	private double utilization;

	private ContainerStatus finishedStatus;
	private boolean isAMContainer;
	private List<ResourceRequest> resourceRequests;

	public RMContainerImpl(Container container,
	                       ApplicationAttemptId appAttemptId, NodeId nodeId, String user,
	                       RMContext rmContext) {
		this(container, appAttemptId, nodeId, user, rmContext, System
			.currentTimeMillis());
	}

	public RMContainerImpl(Container container,
	                       ApplicationAttemptId appAttemptId, NodeId nodeId,
	                       String user, RMContext rmContext, long creationTime) {
		this.stateMachine = stateMachineFactory.make(this);
		this.containerId = container.getId();
		this.nodeId = nodeId;
		this.container = container;
		this.appAttemptId = appAttemptId;
		this.user = user;
		this.creationTime = creationTime;
		this.rmContext = rmContext;
		this.eventHandler = rmContext.getDispatcher().getEventHandler();
		this.containerAllocationExpirer = rmContext.getContainerAllocationExpirer();
		this.isAMContainer = false;
		this.resourceRequests = null;
		this.resumeOpportunity = 0;

		this.utilization = 1;
		this.suspendTime = new LinkedList<Long>();
		this.resumeTime = new LinkedList<Long>();

		ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
		this.readLock = lock.readLock();
		this.writeLock = lock.writeLock();

		this.PR_NUMBER = rmContext.getYarnConfiguration().getInt(
			"yarn.resourcemanager.monitor.capacity.preemption.pr_number", 2);


		rmContext.getRMApplicationHistoryWriter().containerStarted(this);
		rmContext.getSystemMetricsPublisher().containerCreated(
			this, this.creationTime);
	}

	//in case of suspending a container
	public Resource getCurrentUsedResource() {
		if (isSuspending) {
			return Resources.subtract(container.getResource(), preempted);
		} else {
			return container.getResource();
		}
	}

	public boolean isSuspending() {
		return this.isSuspending;
	}

	@Override
	public List<Long> getSuspendTime() {
		return suspendTime;
	}

	@Override
	public List<Long> getResumeTime() {
		return resumeTime;
	}

	@Override
	public long getDeadline() {
		return container.getDeadline();
	}

	@Override
	public long getArrivalTime() {
		return container.getAppArrivalTime();
	}

	@Override
	public int getNumOfBeingPreempted() {
		return container.getNumOfBeingPreempted();
	}

	@Override
	public float getPreemptionPriority() {
		return container.getPreemptionPriority();
	}

	@Override
	public void setDeadline(long deadline) {
		container.setDeadline(deadline);
	}

	@Override
	public void setArrivalTime(long arrivalTime) {
		container.setAppArrivalTime(arrivalTime);
	}

	@Override
	public void setNumOfBeingPreempted(int numOfBeingPreempted) {
		container.setNumOfBeingPreempted(numOfBeingPreempted);
	}

	@Override
	public void setPreemptionPriority(float preemptionPriority) {
		container.setPreemptionPriority(preemptionPriority);
	}

	public double getUtilization() {
		return utilization;
	}

	@Override
	public ContainerId getContainerId() {
		return this.containerId;
	}

	@Override
	public ApplicationAttemptId getApplicationAttemptId() {
		return this.appAttemptId;
	}

	@Override
	public Container getContainer() {
		return this.container;
	}

	@Override
	public RMContainerState getState() {
		this.readLock.lock();

		try {
			return this.stateMachine.getCurrentState();
		} finally {
			this.readLock.unlock();
		}
	}

	@Override
	public Resource getReservedResource() {
		return reservedResource;
	}

	@Override
	public NodeId getReservedNode() {
		return reservedNode;
	}

	@Override
	public Priority getReservedPriority() {
		return reservedPriority;
	}

	@Override
	public Resource getAllocatedResource() {
		return container.getResource();
	}

	@Override
	public NodeId getAllocatedNode() {
		return container.getNodeId();
	}

	@Override
	public Priority getAllocatedPriority() {
		return container.getPriority();
	}

	@Override
	public long getCreationTime() {
		return creationTime;
	}

	@Override
	public long getFinishTime() {
		try {
			readLock.lock();
			return finishTime;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public String getDiagnosticsInfo() {
		try {
			readLock.lock();
			if (getFinishedStatus() != null) {
				return getFinishedStatus().getDiagnostics();
			} else {
				return null;
			}
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public String getLogURL() {
		try {
			readLock.lock();
			StringBuilder logURL = new StringBuilder();
			logURL.append(WebAppUtils.getHttpSchemePrefix(rmContext
				.getYarnConfiguration()));
			logURL.append(WebAppUtils.getRunningLogURL(
				container.getNodeHttpAddress(), ConverterUtils.toString(containerId),
				user));
			return logURL.toString();
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public int getContainerExitStatus() {
		try {
			readLock.lock();
			if (getFinishedStatus() != null) {
				return getFinishedStatus().getExitStatus();
			} else {
				return 0;
			}
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public ContainerState getContainerState() {
		try {
			readLock.lock();
			if (getFinishedStatus() != null) {
				return getFinishedStatus().getState();
			} else {
				return ContainerState.RUNNING;
			}
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public List<ResourceRequest> getResourceRequests() {
		try {
			readLock.lock();
			return resourceRequests;
		} finally {
			readLock.unlock();
		}
	}

	public void setResourceRequests(List<ResourceRequest> requests) {
		try {
			writeLock.lock();
			this.resourceRequests = requests;
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public String toString() {
		return containerId.toString();
	}

	@Override
	public boolean isAMContainer() {
		try {
			readLock.lock();
			return isAMContainer;
		} finally {
			readLock.unlock();
		}
	}

	public void setAMContainer(boolean isAMContainer) {
		try {
			writeLock.lock();
			this.isAMContainer = isAMContainer;
		} finally {
			writeLock.unlock();
		}
	}

	@Override
	public void handle(RMContainerEvent event) {
		LOG.debug("Processing " + event.getContainerId() + " of type " + event.getType());
		try {
			writeLock.lock();
			RMContainerState oldState = getState();
			try {
				stateMachine.doTransition(event.getType(), event);
			} catch (InvalidStateTransitonException e) {
				LOG.error("Can't handle this event at current state", e);
				LOG.error("Invalid event " + event.getType() +
					" on container " + this.containerId);
			}
			if (oldState != getState()) {
				LOG.info(event.getContainerId() + " Container Transitioned from "
					+ oldState + " to " + getState());
			}
		} finally {
			writeLock.unlock();
		}
	}

	public ContainerStatus getFinishedStatus() {
		return finishedStatus;
	}

	private static class BaseTransition implements
		SingleArcTransition<RMContainerImpl, RMContainerEvent> {

		@Override
		public void transition(RMContainerImpl cont, RMContainerEvent event) {

		}

	}

	private static final class ContainerRecoveredTransition
		implements
		MultipleArcTransition<RMContainerImpl, RMContainerEvent, RMContainerState> {
		@Override
		public RMContainerState transition(RMContainerImpl container,
		                                   RMContainerEvent event) {
			NMContainerStatus report =
				((RMContainerRecoverEvent) event).getContainerReport();
			if (report.getContainerState().equals(ContainerState.COMPLETE)) {
				ContainerStatus status =
					ContainerStatus.newInstance(report.getContainerId(),
						report.getContainerState(), report.getDiagnostics(),
						report.getContainerExitStatus());

				new FinishedTransition().transition(container,
					new RMContainerFinishedEvent(container.containerId, status,
						RMContainerEventType.FINISHED));
				return RMContainerState.COMPLETED;
			} else if (report.getContainerState().equals(ContainerState.RUNNING)) {
				// Tell the app
				container.eventHandler.handle(new RMAppRunningOnNodeEvent(container
					.getApplicationAttemptId().getApplicationId(), container.nodeId));
				return RMContainerState.RUNNING;
			} else {
				// This can never happen.
				LOG.warn("RMContainer received unexpected recover event with container"
					+ " state " + report.getContainerState() + " while recovering.");
				return RMContainerState.RUNNING;
			}
		}
	}


	private static final class ContainerResumeTransition implements
		MultipleArcTransition<RMContainerImpl, RMContainerEvent, RMContainerState> {
		@Override
		public RMContainerState transition(RMContainerImpl container,
		                                   RMContainerEvent event) {
			container.resumeTime.add(System.currentTimeMillis());
			if (Resources.equals(container.getPreemptedResource(), Resources.none())) {
				//if all the preempted resource has been resumed
				container.isSuspending = false;
				return RMContainerState.RUNNING;
			} else {
				return RMContainerState.DEHYDRATED;
			}

		}
	}

	private static final class ContainerSuspendTransition extends
		BaseTransition {
		@Override
		public void transition(RMContainerImpl container, RMContainerEvent event) {
			RMContainerFinishedEvent finishedEvent = (RMContainerFinishedEvent) event;
			//add the suspend time
			container.suspendTime.add(System.currentTimeMillis());
			Resource resource = container.getLastPreemptedResource();
			container.finishedStatus = finishedEvent.getRemoteContainerStatus();
			container.isSuspending = true;

			//update preempt metrics
			RMAppAttempt rmAttempt = container.rmContext.getRMApps()
				.get(container.getApplicationAttemptId().getApplicationId())
				.getCurrentAppAttempt();

			if (ContainerExitStatus.PREEMPTED == container.finishedStatus.getExitStatus()) {
				rmAttempt.getRMAppAttemptMetrics().updatePreemptionInfo(resource, container);
			}
		}
	}


	private static final class ContainerReservedTransition extends
		BaseTransition {
		@Override
		public void transition(RMContainerImpl container, RMContainerEvent event) {
			RMContainerReservedEvent e = (RMContainerReservedEvent) event;
			container.reservedResource = e.getReservedResource();
			container.reservedNode = e.getReservedNode();
			container.reservedPriority = e.getReservedPriority();
		}
	}


	private static final class ContainerStartedTransition extends
		BaseTransition {
		@Override
		public void transition(RMContainerImpl container, RMContainerEvent event) {
			container.eventHandler.handle(new RMAppAttemptContainerAllocatedEvent(
				container.appAttemptId));
		}
	}

	private static final class AcquiredTransition extends BaseTransition {

		@Override
		public void transition(RMContainerImpl container, RMContainerEvent event) {
			// Clear ResourceRequest stored in RMContainer
			container.setResourceRequests(null);

			// Register with containerAllocationExpirer.
			container.containerAllocationExpirer.register(container.getContainerId());

			// Tell the app
			container.eventHandler.handle(new RMAppRunningOnNodeEvent(container
				.getApplicationAttemptId().getApplicationId(), container.nodeId));
		}
	}

	private static final class LaunchedTransition extends BaseTransition {

		@Override
		public void transition(RMContainerImpl container, RMContainerEvent event) {
			// Unregister from containerAllocationExpirer.
			container.containerAllocationExpirer.unregister(container
				.getContainerId());
		}
	}

	private static class ResourceUpdateTransition extends BaseTransition {

		@Override
		public void transition(RMContainerImpl container, RMContainerEvent event) {

			RMContainerResourceUpdateEvent resourceUpdateEvent = (RMContainerResourceUpdateEvent) event;
			Resource resource = resourceUpdateEvent.getResource();
			//once triguer this event, we update its resource to new value
			container.getContainer().setResource(resource);
		}
	}

	private static class FinishedTransition extends BaseTransition {

		@Override
		public void transition(RMContainerImpl container, RMContainerEvent event) {
			RMContainerFinishedEvent finishedEvent = (RMContainerFinishedEvent) event;

			container.finishTime = System.currentTimeMillis();
			container.finishedStatus = finishedEvent.getRemoteContainerStatus();
			// Inform AppAttempt
			// container.getContainer() can return null when a RMContainer is a
			// reserved container
			updateAttemptMetrics(container);

			container.eventHandler.handle(new RMAppAttemptContainerFinishedEvent(
				container.appAttemptId, finishedEvent.getRemoteContainerStatus(),
				container.getAllocatedNode()));

			container.rmContext.getRMApplicationHistoryWriter().containerFinished(
				container);
			container.rmContext.getSystemMetricsPublisher().containerFinished(
				container, container.finishTime);
		}

		private static void updateAttemptMetrics(RMContainerImpl container) {
			// If this is a preempted container, update preemption metrics
			Resource resource = container.getContainer().getResource();
			RMAppAttempt rmAttempt = container.rmContext.getRMApps()
				.get(container.getApplicationAttemptId().getApplicationId())
				.getCurrentAppAttempt();
			if (ContainerExitStatus.PREEMPTED == container.finishedStatus
				.getExitStatus()) {
				rmAttempt.getRMAppAttemptMetrics().updatePreemptionInfo(resource,
					container);
			}

			if (rmAttempt != null) {
				long usedMillis = container.finishTime - container.creationTime;
				long memorySeconds = (long) (resource.getMemory() * container.utilization)
					* usedMillis / DateUtils.MILLIS_PER_SECOND;
				long vcoreSeconds = (long) (resource.getVirtualCores() * container.utilization)
					* usedMillis / DateUtils.MILLIS_PER_SECOND;

				if (container.suspendTime.size() > 0 && container.resumeTime.size() > 0 && container.suspendTime.size() == container.resumeTime.size()) {
					double acc = 0;
					for (int i = 0; i < container.suspendTime.size(); i++) {

						acc = acc + (container.resumeTime.get(i) - container.suspendTime.get(i));
					}
					container.utilization = acc / usedMillis;
				}
				rmAttempt.getRMAppAttemptMetrics()
					.updateAggregateAppResourceUsage(memorySeconds, vcoreSeconds);
			}
		}
	}

	private static final class ContainerFinishedAtAcquiredState extends
		FinishedTransition {
		@Override
		public void transition(RMContainerImpl container, RMContainerEvent event) {
			// Unregister from containerAllocationExpirer.
			container.containerAllocationExpirer.unregister(container
				.getContainerId());

			// Inform AppAttempt
			super.transition(container, event);
		}
	}

	private static final class KillTransition extends FinishedTransition {

		@Override
		public void transition(RMContainerImpl container, RMContainerEvent event) {

			// Unregister from containerAllocationExpirer.
			container.containerAllocationExpirer.unregister(container
				.getContainerId());

			// Inform node
			container.eventHandler.handle(new RMNodeCleanContainerEvent(
				container.nodeId, container.containerId));

			// Inform appAttempt
			super.transition(container, event);
		}
	}

	@Override
	public ContainerReport createContainerReport() {
		this.readLock.lock();
		ContainerReport containerReport = null;
		try {
			containerReport = ContainerReport.newInstance(this.getContainerId(),
				this.getAllocatedResource(), this.getAllocatedNode(),
				this.getAllocatedPriority(), this.getCreationTime(),
				this.getFinishTime(), this.getDiagnosticsInfo(), this.getLogURL(),
				this.getContainerExitStatus(), this.getContainerState(),
				this.getNodeHttpAddress());
		} finally {
			this.readLock.unlock();
		}
		return containerReport;
	}

	@Override
	public String getNodeHttpAddress() {
		try {
			readLock.lock();
			if (container.getNodeHttpAddress() != null) {
				StringBuilder httpAddress = new StringBuilder();
				httpAddress.append(WebAppUtils.getHttpSchemePrefix(rmContext
					.getYarnConfiguration()));
				httpAddress.append(container.getNodeHttpAddress());
				return httpAddress.toString();
			} else {
				return null;
			}
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public void addPreemptedResource(Resource resource) {
		try {
			readLock.lock();
			this.lastPreempted = Resources.clone(resource);
			Resources.addTo(preempted, resource);
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public Resource getPreemptedResource() {
		try {
			readLock.lock();
			return this.preempted;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public Resource getLastPreemptedResource() {
		try {
			readLock.lock();
			return this.lastPreempted;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public Resource getLastResumeResource() {
		try {
			readLock.lock();
			return this.lastResumed;
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public void addResumedResource(Resource resource) {
		try {
			readLock.lock();
			this.lastResumed = Resources.clone(resource);
			Resources.subtractFrom(preempted, resource);
		} finally {
			readLock.unlock();
		}
	}

	@Override
	public Resource getSRResourceUnit() {

		Resource resource = Resource.newInstance(container.getResource().getMemory() / container.getResource().getVirtualCores(),
			1);
		return Resources.multiplyTo(resource, this.PR_NUMBER);
	}

	@Override
	public int getResumeOpportunity() {
		return this.resumeOpportunity;
	}

	@Override
	public void incResumeOpportunity() {
		this.resumeOpportunity++;
	}

	@Override
	public void resetResumeOpportunity() {
		this.resumeOpportunity = 0;
	}

}
