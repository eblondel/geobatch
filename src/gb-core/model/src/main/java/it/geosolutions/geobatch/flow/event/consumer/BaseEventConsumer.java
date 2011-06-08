/*
 *  GeoBatch - Open Source geospatial batch processing system
 *  http://geobatch.codehaus.org/
 *  Copyright (C) 2007-2008-2009 GeoSolutions S.A.S.
 *  http://www.geo-solutions.it
 *
 *  GPLv3 + Classpath exception
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package it.geosolutions.geobatch.flow.event.consumer;

import it.geosolutions.geobatch.catalog.impl.BaseIdentifiable;
import it.geosolutions.geobatch.catalog.impl.BaseResource;
import it.geosolutions.geobatch.configuration.event.consumer.EventConsumerConfiguration;
import it.geosolutions.geobatch.flow.event.IProgressListener;
import it.geosolutions.geobatch.flow.event.ProgressListenerForwarder;
import it.geosolutions.geobatch.flow.event.action.Action;
import it.geosolutions.geobatch.flow.event.action.ActionException;
import it.geosolutions.geobatch.misc.PauseHandler;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.EventObject;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.TimeZone;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Alessio Fabiani, GeoSolutions
 * @author Simone Giannecchini, GeoSolutions
 */
public abstract class BaseEventConsumer<XEO extends EventObject, ECC extends EventConsumerConfiguration>
		extends BaseResource implements Callable<Queue<XEO>>,
		EventConsumer<XEO, ECC> {

	private static Logger LOGGER = LoggerFactory
			.getLogger(BaseEventConsumer.class.toString());

	// private static Counter counter = new Counter();

	private final Calendar creationTimestamp = Calendar.getInstance(TimeZone
			.getTimeZone("UTC"));

	private volatile EventConsumerStatus eventConsumerStatus;

	/**
	 * The MailBox
	 * 
	 * @uml.property name="eventsQueue"
	 */
	protected final Queue<XEO> eventsQueue = new LinkedList<XEO>();

	protected final List<Action<XEO>> actions = new ArrayList<Action<XEO>>();

	protected volatile Action<XEO> currentAction = null;

	// private EventListenerList listeners = new EventListenerList();
	/**
	 * @uml.property name="listenerForwarder"
	 * @uml.associationEnd multiplicity="(1 1)" inverse=
	 *                     "this$0:it.geosolutions.geobatch.flow.event.consumer.BaseEventConsumer$EventConsumerListenerForwarder"
	 */
	final protected EventConsumerListenerForwarder listenerForwarder;

	protected PauseHandler pauseHandler = new PauseHandler(false);

	// public BaseEventConsumer() {
	// super();
	// this.setStatus(EventConsumerStatus.IDLE);
	// this.setId(getClass().getSimpleName() + "_" + counter.getNext());
	// }

	public BaseEventConsumer(String id, String name, String description) {
		super(id, name, description);
		this.listenerForwarder = new EventConsumerListenerForwarder(this);
		this.setStatus(EventConsumerStatus.IDLE);
	}

	public Calendar getCreationTimestamp() {
		return (Calendar) creationTimestamp.clone();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * it.geosolutions.geobatch.flow.event.consumer.EventConsumer#getStatus()
	 */
	public EventConsumerStatus getStatus() {
		return this.eventConsumerStatus;
	}

	/**
	 * Change status and fire events on listeners if status has really changed.
	 */
	protected void setStatus(EventConsumerStatus eventConsumerStatus) {

		EventConsumerStatus old = eventConsumerStatus;

		this.eventConsumerStatus = eventConsumerStatus;

		if (old != eventConsumerStatus) {
			listenerForwarder.fireStatusChanged(old, eventConsumerStatus);
			listenerForwarder.setTask(eventConsumerStatus.toString());
		}
	}

	public Action<XEO> getCurrentAction() {
		return currentAction;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @seeit.geosolutions.geobatch.flow.event.consumer.EventConsumer#put(it.
	 * geosolutions .filesystemmonitor .monitor.FileSystemEvent)
	 */
	public boolean consume(XEO event) {
		if (!eventsQueue.offer(event)) {
			return false;
		}

		return true;
	}

	/**
	 * Once the configuring state has been successfully passed, by collecting
	 * all the necessary Events, the EventConsumer invokes this method in order
	 * to run the related actions.
	 * <P>
	 * <B>FIXME</B>: <I>on action errors the flow used to go on. Now it bails
	 * out from the loop. <BR>
	 * We may need to specify on a per-action basis if an error in the action
	 * should stop the whole flow.</I>
	 */
	protected Queue<XEO> applyActions(Queue<XEO> events) throws ActionException {
		if (LOGGER.isTraceEnabled()) {
			LOGGER.trace("Applying " + actions.size() + " actions on "
					+ events.size() + " events.");
		}

		// apply all the actions
		int step = 0;

		for (Action<XEO> action : this.actions) {
			try {
				pauseHandler.waitUntilResumed();

				float progress = 100f * (float) step / this.actions.size();
				listenerForwarder.setProgress(progress);
				listenerForwarder.setTask("Running "
						+ action.getClass().getSimpleName() + "(" + (step + 1)
						+ "/" + this.actions.size() + ")");
				listenerForwarder.progressing(); // notify there has been some
				// progressing

				currentAction = action;
				events = action.execute(events);

				if (events == null) {
					throw new IllegalArgumentException("Action "
							+ action.getClass().getSimpleName()
							+ " left no event in queue.");
				}
				if (events.isEmpty()) {
					if (LOGGER.isWarnEnabled()) {
						LOGGER.warn("Action "
								+ action.getClass().getSimpleName()
								+ " left no event in queue.");
					}
				}
				step++;

			} catch (ActionException e) {
				if (LOGGER.isErrorEnabled()) {
					LOGGER.error(e.getLocalizedMessage(), e);
				}

				listenerForwarder.setTask("Action "
						+ action.getClass().getSimpleName() + " failed (" + e
						+ ")");
				listenerForwarder.progressing();

				if (!currentAction.isFailIgnored()) {
					events.clear();
					throw e;
				} else {
					// CHECKME: eventlist is not modified in this case. will it
					// work?
				}

			} catch (Exception e) { // exception not handled by the Action
				if (LOGGER.isErrorEnabled()) {
					LOGGER.error(
							"Action threw an unhandled exception: "
									+ e.getLocalizedMessage(), e);
				}

				listenerForwarder.setTask("Action "
						+ action.getClass().getSimpleName() + " failed (" + e
						+ ")");
				listenerForwarder.progressing();

				if (!currentAction.isFailIgnored()) {
					if (events == null) {
						throw new IllegalArgumentException("Action "
								+ action.getClass().getSimpleName()
								+ " left no event in queue.");
					} else {
						events.clear();
					}
					// wrap the unhandled exception
					throw new ActionException(currentAction, e.getMessage(), e);
				} else {
					// CHECKME: eventlist is not modified in this case. will it
					// work?
				}
			} finally {
				// currentAction = null; // don't null the action: we'd like to
				// read which was the last action run
			}
		}

		// end of loop: all actions have been executed
		// checkme: what shall we do with the events left in the queue?
		if (events != null && !events.isEmpty()) {
			LOGGER.info("There are " + events.size()
					+ " events left in the queue after last action ("
					+ currentAction.getClass().getSimpleName() + ")");
		}
		return events;
	}

	public boolean pause() {
		pauseHandler.pause();
		return true; // we'll pause asap
	}

	public boolean pause(boolean sub) {
		if (getStatus().equals(EventConsumerStatus.EXECUTING)
				|| getStatus().equals(EventConsumerStatus.WAITING)
				|| getStatus().equals(EventConsumerStatus.IDLE)) {
			if (LOGGER.isInfoEnabled())
				LOGGER.info("Pausing consumer " + getName() + " ["
						+ creationTimestamp + "]");

			pauseHandler.pause();

			if (currentAction != null) {
				LOGGER.info("Pausing action "
						+ currentAction.getClass().getSimpleName()
						+ " in consumer " + getName() + " ["
						+ creationTimestamp + "]");
				currentAction.pause();
			}
		}

		return true; // we'll pause asap
	}

	public void resume() {
		LOGGER.info("Resuming consumer " + getName() + " [" + creationTimestamp
				+ "]");
		if (currentAction != null) {
			LOGGER.info("Resuming action "
					+ currentAction.getClass().getSimpleName()
					+ " in consumer " + getName() + " [" + creationTimestamp
					+ "]");
			currentAction.resume();
		}

		pauseHandler.resume();
	}

	public boolean isPaused() {
		return pauseHandler.isPaused();
	}

	/**
	 * 
	 * @return the list of the <TT>Action</TT>s associated to this consumer.
	 * 
	 *         TODO: returned list should be unmodifiable
	 */
	public List<Action<XEO>> getActions() {
		return actions;
	}

	protected void addActions(final List<Action<XEO>> actions) {
		this.actions.addAll(actions);
	}

	public void dispose() {
		eventsQueue.clear();
		// actions.clear();
		// currentAction.destroy();
	}

	/**
	 * Add listener to this consumer. If the listener is already registered, it
	 * won't be added again.
	 * 
	 * @param fileListener
	 *            Listener to add.
	 */
	public synchronized void addListener(EventConsumerListener listener) {
		listenerForwarder.addListener(listener);
	}

	/**
	 * Remove listener from this file monitor.
	 * 
	 * @param listener
	 *            Listener to remove.
	 */
	public synchronized void removeListener(EventConsumerListener listener) {
		listenerForwarder.removeListener(listener);
	}

	protected ProgressListenerForwarder getListenerForwarder() {
		return listenerForwarder;
	}

	public IProgressListener getProgressListener(Class<IProgressListener> clazz) {
		for (IProgressListener ipl : getListenerForwarder().getListeners()) {
			if (clazz.isAssignableFrom(ipl.getClass())) {
				return ipl;
			}
		}
		return null;
	}

	protected class EventConsumerListenerForwarder extends
			ProgressListenerForwarder {

		protected EventConsumerListenerForwarder(BaseIdentifiable owner) {
			super(owner);
		}

		public void fireStatusChanged(EventConsumerStatus olds,
				EventConsumerStatus news) {
			for (IProgressListener l : listeners) {
				try {
					if (l instanceof EventConsumerListener) {
						((EventConsumerListener) l).statusChanged(olds, news);
					}
				} catch (Exception e) {
					LOGGER.warn("Exception in event forwarder: " + e);
				}
			}
		}
	}
}
