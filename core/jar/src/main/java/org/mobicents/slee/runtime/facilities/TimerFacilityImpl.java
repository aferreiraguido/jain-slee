/***************************************************
 *                                                 *
 *  Mobicents: The Open Source VoIP Platform       *
 *                                                 *
 *  Distributable under LGPL license.              *
 *  See terms of license at gnu.org.               *
 *                                                 *
 ***************************************************/

package org.mobicents.slee.runtime.facilities;

import java.io.Serializable;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.slee.ActivityContextInterface;
import javax.slee.Address;
import javax.slee.TransactionRequiredLocalException;
import javax.slee.TransactionRolledbackLocalException;
import javax.slee.facilities.FacilityException;
import javax.slee.facilities.TimerFacility;
import javax.slee.facilities.TimerID;
import javax.slee.facilities.TimerOptions;
import javax.transaction.SystemException;

import org.jboss.logging.Logger;
import org.mobicents.slee.container.SleeContainer;
import org.mobicents.slee.runtime.activity.ActivityContext;
import org.mobicents.slee.runtime.activity.ActivityContextInterfaceImpl;
import org.mobicents.slee.runtime.cache.TimerFacilityCacheData;
import org.mobicents.slee.runtime.transaction.SleeTransactionManager;
import org.mobicents.slee.runtime.transaction.TransactionalAction;

/*
 * Tim - major refactoring - prevented thrashing, made threadsafe, added
 * transaction checking Ranga - Initial author and cache tweaking.
 * 
 */
/**
 * Implementation of the SLEE timer facility. timer is the timer object
 * currently being examined. timer.scheduleTime is time that the Timer Event is
 * scheduled to fire. timer.timeout is timeout for this timer (from
 * TimerOptions). timer.numRepetitions is the total repetitions for this timer,
 * 0 if infinite, 1 if non-periodic. timer.remainingRepetiton is the remaining
 * repetition count, initially Long.MAX_VALUE for infinite periodic timers,
 * timer.numRepetitions otherwise. timer.period is the timer period
 * (Long.MAX_VALUE if non-periodic). timer.missed is the counter of undelivered
 * late events.
 * 
 * @author Tim
 * @author M. Ranganathan
 * @author Ivelin Ivanov
 * 
 */
public class TimerFacilityImpl implements Serializable, TimerFacility {

	private static Logger logger = Logger.getLogger(TimerFacilityImpl.class);
	
	private static final long serialVersionUID = 5281276761487630957L;

	private static final int DEFAULT_TIMEOUT = 1000;

	public static final String JNDI_NAME = "timer";
    	
	// this is supposed to be the timer resolution in ms of the hosting
	// OS/hardware
	private int timerResolution = 10;

	private final SleeContainer sleeContainer;
	
	private final TimerFacilityCacheData cacheData;
	
	class TimerFacilityAction implements TransactionalAction {

		private static final int TYPE_SET_ONETIME = 0;

		private static final int TYPE_SET_PERIOD = 1;

		private static final int TYPE_CANCEL = 2;

		private TimerTask task;

		private Date startTime;

		private long period;

		private TimerID timerID;

		private int actionType; // Saves having to define a new class when you

		// have several actions
		class TimerStarterTask implements Runnable {

			private TimerFacilityAction timerFacilityAction;

			public TimerStarterTask(TimerFacilityAction timerFacilityAction) {
				this.timerFacilityAction = timerFacilityAction;
			}

			/*
			 * (non-Javadoc)
			 * 
			 * @see java.lang.Runnable#run()
			 */
			public void run() {
				try {
					TimerFacilityImpl tf = TimerFacilityImpl.this;
					if (actionType == TYPE_SET_ONETIME) {
						scheduleOneTimeTask(tf);
					} else if (actionType == TYPE_SET_PERIOD) {
						schedulePeriodicTask(tf);
					} else if (actionType == TYPE_CANCEL) {

						if (logger.isDebugEnabled()) {
							logger.debug("TASK===Cancelling timer");
						}
						timerFacilityAction.task.cancel();
					}
				} catch (RuntimeException e) {
					logger.error("Failed to run timer start task", e);
					throw e;
				}
			}

			private void schedulePeriodicTask(TimerFacilityImpl tf) {
				if (logger.isDebugEnabled()) {
					logger.debug("===Scheduling periodic timer");
				}
				
				sleeContainer.getTimer().scheduleAtFixedRate(task, startTime, period);
				if (logger.isDebugEnabled()) {
					logger
					.debug("Timer task scheduled successfully.");
				}
			}

			private void scheduleOneTimeTask(TimerFacilityImpl tf) {
				if (logger.isDebugEnabled()) {
					logger.debug("===Scheduling one-time timer");
				}				
				sleeContainer.getTimer().schedule(task, startTime);
				if (logger.isDebugEnabled()) {
					logger
					.debug("Timer task scheduled successfully.");
				}				
			}
		}

		public String toString() {
			return this.getClass() + " Type: " + actionType;
		}

		TimerFacilityAction(TimerFacilityTimerTask task, Date startTime,
				long period) {
			this(task, startTime);

			if (task.numRepetitions != 1) {
				this.period = period;
				actionType = TYPE_SET_PERIOD;
			}

		}

		TimerFacilityAction(TimerFacilityTimerTask task, Date startTime) {
			this.task = task;
			this.startTime = startTime;
			actionType = TYPE_SET_ONETIME;

			this.timerID = task.getTimerID();
		}

		TimerFacilityAction(TimerFacilityTimerTask task) {
			this.task = task;
			actionType = TYPE_CANCEL;
		}

		public void execute() {

			try {
				ExecutorService executor = Executors.newSingleThreadExecutor();
				executor.execute(new TimerStarterTask(this));
				executor.shutdown();				
			} catch (Exception e) {
				logger.error("Failed to execute TimerStarterTask", e);
			}
		}
	}

	public TimerFacilityImpl(SleeContainer sleeContainer) {
		this.sleeContainer = sleeContainer;
		// init cache data
		cacheData = sleeContainer.getCache().getTimerFacilityCacheData();
		cacheData.create();
	}

	/*
	 * One shot timer
	 * 
	 * @see javax.slee.facilities.TimerFacility#setTimer(javax.slee.ActivityContextInterface,
	 *      javax.slee.Address, long, javax.slee.facilities.TimerOptions)
	 */
	public TimerID setTimer(ActivityContextInterface aci, Address address,
			long startTime, TimerOptions timerOptions)
			throws NullPointerException, IllegalArgumentException,
			FacilityException {
		return setTimer(aci, address, startTime, Long.MAX_VALUE, 1,
				timerOptions);
	}

	/*
	 * Periodic timer
	 * 
	 * @see javax.slee.facilities.TimerFacility#setTimer(javax.slee.ActivityContextInterface,
	 *      javax.slee.Address, long, long, int,
	 *      javax.slee.facilities.TimerOptions)
	 */
	public TimerID setTimer(ActivityContextInterface aci, Address address,
			long startTime, long period, int numRepetitions,
			TimerOptions timerOptions) throws NullPointerException,
			IllegalArgumentException, TransactionRolledbackLocalException,
			FacilityException {

		if (aci == null)
			throw new NullPointerException("Null ActivityContextInterface");
		if (timerOptions == null)
			throw new NullPointerException("Null TimerOptions");
		if (startTime < 0)
			throw new IllegalArgumentException("startTime < 0");
		if (period <= 0)
			throw new IllegalArgumentException("period <= 0");
		// when numRepetitions == 0 the timer repeats infinitely or until
		// canceled
		if (numRepetitions < 0)
			throw new IllegalArgumentException("numRepetitions < 0");
		if (timerOptions.getTimeout() > period)
			throw new IllegalArgumentException("timeout > period"); // SPEC
		// 13.1.3
		if (timerOptions.getTimeout() < this.getResolution())
			timerOptions.setTimeout(Math.min(period, this.getResolution())); // SPEC
		// 13.1.3

		SleeTransactionManager txMgr = sleeContainer.getTransactionManager();

		boolean startedTx = txMgr.requireTransaction();

		if (logger.isDebugEnabled()) {
			logger.debug("setTimer: startTime = " + startTime + " period = "
					+ period + " numRepetitions = " + numRepetitions
					+ " timeroptions =" + timerOptions);
		}
		TimerIDImpl timerID = new TimerIDImpl();
		if (logger.isDebugEnabled()) {
			logger.debug("Timer id is: " + timerID);
		}

		long now = System.currentTimeMillis();
		if (startTime < now)
			startTime = now;

		if (logger.isDebugEnabled()) {
			logger.debug("START TIME IS " + startTime);
		}

		ActivityContextInterfaceImpl aciImpl = (ActivityContextInterfaceImpl) aci;
		
		TimerFacilityTimerTask task = new TimerFacilityTimerTask(timerID,
				aciImpl.getActivityContext().getActivityContextId(),
				address, startTime, period, numRepetitions, timerOptions);

		try {
			cacheData.addTask(timerID, task);
		} catch (Exception e) {
			throw new FacilityException("Failed to add timer to cache", e);
		}
		if (logger.isDebugEnabled()) {
			logger.debug("Added new timer to cache");
		}

		// Attach to activity context
		aciImpl.getActivityContext().attachTimer(timerID);

		// Create an action that actually schedules the timer - we execute this
		// on commit of the tx
		TimerFacilityAction action = new TimerFacilityAction(task, new Date(
				startTime), period);
		
		try {
			txMgr.addAfterCommitAction(action);
		} catch (SystemException e1) {
			logger.error(e1.getMessage(),e1);
			throw new FacilityException(e1.getMessage());
		}

		// If we started a tx for this operation, we commit it now
		if (startedTx) {
			try {
				txMgr.commit();
			} catch (Exception e) {
				throw new TransactionRolledbackLocalException(
						"Failed to commit transaction");
			}
		}

		return timerID;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.slee.facilities.TimerFacility#cancelTimer(javax.slee.facilities.TimerID)
	 */
	public void cancelTimer(TimerID timerID) throws NullPointerException,
			TransactionRolledbackLocalException, FacilityException {
		
		if (timerID == null)
			throw new NullPointerException("Null TimerID");

		SleeTransactionManager txMgr = sleeContainer.getTransactionManager();

		boolean startedTx = txMgr.requireTransaction();
		if (logger.isDebugEnabled()) {
			logger.debug("Started tx: " + startedTx);
			logger.debug("Cancelling timer");
		}

		try {
			TimerFacilityTimerTask task = removeReferencesToTimer(timerID);
			if (task != null) {
				addPostTimerCancelationAction(timerID, txMgr, task);
			}
		} finally {
			if (startedTx) {
				try {
					if (logger.isDebugEnabled()) {
						logger.debug("started tx so committing it");
					}
					txMgr.commit();
				} catch (Exception e) {
					logger
							.error(
									"Failed to commit tx in cancelTimer(). Rolling back tx.",
									e);
					try {
						txMgr.rollback();
					} catch (SystemException e1) {
						logger.error("Failed to rollback tx in cancelTimer().",
								e1);
						throw new TransactionRolledbackLocalException(
								"Failed to rollback transaction");
					}
					throw new TransactionRolledbackLocalException(
							"Failed to commit transaction");
				}
			}
		}
	}

	private void addPostTimerCancelationAction(TimerID timerID,
			SleeTransactionManager txMgr, TimerFacilityTimerTask task) {
		/*
		 * Hack It seems that, if there's a transactional action to set a timer
		 * to start immediately, and there's a cancel for that timer in the same
		 * transaction. Then, when the transaction commits, the timer should
		 * never start ticking, even thought the cancel action is executed after
		 * the setimer action (see TCK test 1170) This means, when we add a
		 * cancel timer action to the transaction, we only actually add it if
		 * the corresponding settimer method isn't in the transaction -
		 * otherwise we just remove the first settime
		 * 
		 */
		boolean needToAdd = true;

		try {
			List actions = txMgr.getTransactionContext().getAfterCommitActions();
			if (actions != null) {
				Iterator iter = actions.iterator();
				while (iter.hasNext()) {
					TransactionalAction action = (TransactionalAction) iter
							.next();

					if (action instanceof TimerFacilityAction) {
						if (logger.isDebugEnabled()) {
							logger.debug("Timerfacilityaction");
						}
						TimerFacilityAction tfAction = (TimerFacilityAction) action;
						if (logger.isDebugEnabled()) {
							logger.debug("Action 2 is: " + tfAction);
							logger.debug("timerid is: " + tfAction.timerID);
							logger.debug("actiontype is: "
									+ tfAction.actionType);
							logger.debug("timerid is: " + timerID);
						}
						if (((tfAction.actionType == TimerFacilityAction.TYPE_SET_ONETIME) || (tfAction.actionType == TimerFacilityAction.TYPE_SET_PERIOD))
								&& tfAction.timerID.equals(timerID)) {
							if (logger.isDebugEnabled()) {
								logger.debug("Removing it:");
							}
							// Remove it
							iter.remove();
							needToAdd = false;

							break;
						}
					}
				}
			}
		} catch (SystemException e) {
			throw new FacilityException("Failed to get actions", e);
		}

		if (logger.isDebugEnabled()) {
			logger.debug("Need to add: " + needToAdd);
		}

		// Add an action representing the cancelling of the timer task
		if (needToAdd) {
			TimerFacilityAction action = new TimerFacilityAction(task);

			try {
				cacheData.removeTask(task.getTimerID());
				txMgr.addAfterCommitAction(
						action);
				if (logger.isDebugEnabled()) {
					logger.debug("Added cancel timer commit action");
				}

			} catch (Exception e) {
				// This only happens if it is not in a transaction - this should
				// never happen
				logger.error(e.getMessage(),e);
			}
		}
	}

	private TimerFacilityTimerTask removeReferencesToTimer(TimerID timerID) {
		// Remove the timer from the tree cache
		// String fqn = FQN_TIMERS_PREFIX + timerID.toString();
		// logger.debug("Removing node: " + fqn);
		TimerFacilityTimerTask task = null;
		try {
			// ---
			// task = (TimerFacilityTimerTask)txMgr.getObject(tcache,fqn,
			// "task");
			task = (TimerFacilityTimerTask) cacheData.getTask(timerID);
			if (task == null) {
				if (logger.isDebugEnabled()) {
					logger.debug("TASK================ Can't find timer["
						+ timerID.toString()
						+ "] task in cache! ===============");
				}
				return null;
			} else {
				if (logger.isDebugEnabled()) {
					logger.debug("removeReferencesToTimer TASK[" + task + "]");
				}
			}

			// Detach this timer from the ac
			ActivityContext ac = sleeContainer.getActivityContextFactory()
					.getActivityContext(task.getActivityContextId(),true);
			if (ac == null)
				throw new FacilityException("Can't find ac in cache!");

			ac.detachTimer(timerID);
			cacheData.removeTask(timerID);			
		} catch (Exception e) {
			throw new FacilityException("Failed to remove timer from cache", e);
		}
		return task;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.slee.facilities.TimerFacility#getResolution()
	 */
	public long getResolution() throws FacilityException {
		return this.timerResolution;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.slee.facilities.TimerFacility#getDefaultTimeout()
	 */
	public long getDefaultTimeout() throws FacilityException {
		return DEFAULT_TIMEOUT;
	}

	/**
	 * Call this on facility restart.
	 * 
	 */
	public void restart() {

		sleeContainer.getTransactionManager().mandateTransaction();
		try {
			
			Set tasks = cacheData.getTasks();
			
			if (logger.isDebugEnabled())
				logger.debug("TimeFacility.restart() cmap size " + tasks.size());

			for (Object obj : tasks) {
				
				TimerFacilityTimerTask task = (TimerFacilityTimerTask) obj;
				if (logger.isDebugEnabled())
					logger
							.debug("TimerFacility.restart(): restarting timer task \n"
									+ task);
				long period = task.period;
				long startTime;
				long now = System.currentTimeMillis();

				// For a persistent timer, note the timeout.
				if (task.getTimerOptions().isPersistent()) {
					long lastTick = task.getLastTick();
					if (lastTick + period < now)
						startTime = now;
					else
						startTime = lastTick + period;
				} else
					startTime = now;

				TimerFacilityAction action = new TimerFacilityAction(task,
						new Date(startTime), period);

				sleeContainer.getTransactionManager().addAfterCommitAction(
							action);
			}
		} catch (Exception ex) {

			logger.error("Bad startup !", ex);
		}
	}

	/**
	 * Set the timer resolution. This is for jmx interfaces.
	 * 
	 * @throws Exception
	 *             if the resolution is smaller than 10 milisecods
	 */
	public void setTimerResolution(int resolution) {
		if (resolution < 10)
			throw new IllegalArgumentException(
					"min resolution is 10 miliseconds");
		else
			this.timerResolution = resolution;
	}

	/**
	 * Get the timer resolution. This is for jmx interfaces.
	 * 
	 */
	public int getTimerResolution() {
		return this.timerResolution;
	}

	/**
	 * @param task
	 */
	public void persistTimer(TimerFacilityTimerTask task) throws Exception {
		cacheData.addTask(task.getTimerID(), task);		
	}

	@Override
	public String toString() {
		return 	"Timer Facility: " +
				"\n+-- Timer tasks: " + cacheData.getTasks().size();
	}

	public ActivityContextInterface getActivityContextInterface(TimerID timerID)
			throws NullPointerException, TransactionRequiredLocalException,
			FacilityException {
		if (timerID == null) {
			throw new NullPointerException("null timerID");
		}
		
		sleeContainer.getTransactionManager().mandateTransaction();
		
		TimerFacilityTimerTask task = (TimerFacilityTimerTask) cacheData.getTask(timerID);
		
		if (task != null) {
			try {
				return new ActivityContextInterfaceImpl(sleeContainer.getActivityContextFactory().getActivityContext(task.getActivityContextId(), true));
			} catch (Exception e) {
				throw new FacilityException(e.getMessage(),e);
			}
		}
		return null;		
	}
}
