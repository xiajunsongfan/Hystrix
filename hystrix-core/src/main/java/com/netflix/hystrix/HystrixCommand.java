/**
 * Copyright 2012 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.hystrix;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Operator;
import rx.Observer;
import rx.Scheduler;
import rx.Subscriber;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;
import rx.subscriptions.Subscriptions;
import rx.util.functions.Action0;
import rx.util.functions.Func1;

import com.netflix.config.ConfigurationManager;
import com.netflix.hystrix.HystrixCircuitBreaker.TestCircuitBreaker;
import com.netflix.hystrix.HystrixCommandProperties.ExecutionIsolationStrategy;
import com.netflix.hystrix.exception.HystrixBadRequestException;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.exception.HystrixRuntimeException.FailureType;
import com.netflix.hystrix.strategy.concurrency.HystrixConcurrencyStrategyDefault;
import com.netflix.hystrix.strategy.concurrency.HystrixContextCallable;
import com.netflix.hystrix.strategy.concurrency.HystrixContextRunnable;
import com.netflix.hystrix.strategy.concurrency.HystrixContextScheduler;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;
import com.netflix.hystrix.strategy.executionhook.HystrixCommandExecutionHook;
import com.netflix.hystrix.strategy.properties.HystrixPropertiesStrategy;
import com.netflix.hystrix.strategy.properties.HystrixProperty;
import com.netflix.hystrix.util.ExceptionThreadingUtility;
import com.netflix.hystrix.util.HystrixRollingNumberEvent;
import com.netflix.hystrix.util.HystrixTimer;
import com.netflix.hystrix.util.HystrixTimer.TimerListener;

/**
 * Used to wrap code that will execute potentially risky functionality (typically meaning a service call over the network)
 * with fault and latency tolerance, statistics and performance metrics capture, circuit breaker and bulkhead functionality.
 * This command is essentially a blocking command but provides an Observable facade if used with  observe()
 * 
 * @param <R>
 *            the return type
 */
@ThreadSafe
public abstract class HystrixCommand<R> extends AbstractHystrixCommand<R> implements HystrixExecutable<R> {

    private static final Logger logger = LoggerFactory.getLogger(HystrixCommand.class);

   
    /**
     * Construct a {@link HystrixCommand} with defined {@link HystrixCommandGroupKey}.
     * <p>
     * The {@link HystrixCommandKey} will be derived from the implementing class name.
     * 
     * @param group
     *            {@link HystrixCommandGroupKey} used to group together multiple {@link HystrixCommand} objects.
     *            <p>
     *            The {@link HystrixCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interace with,
     *            common business purpose etc.
     */
    protected HystrixCommand(HystrixCommandGroupKey group) {
        // use 'null' to specify use the default
        this(new Setter(group));
    }

    /**
     * Construct a {@link HystrixCommand} with defined {@link Setter} that allows injecting property and strategy overrides and other optional arguments.
     * <p>
     * NOTE: The {@link HystrixCommandKey} is used to associate a {@link HystrixCommand} with {@link HystrixCircuitBreaker}, {@link HystrixCommandMetrics} and other objects.
     * <p>
     * Do not create multiple {@link HystrixCommand} implementations with the same {@link HystrixCommandKey} but different injected default properties as the first instantiated will win.
     * <p>
     * Properties passed in via {@link Setter#andCommandPropertiesDefaults} or {@link Setter#andThreadPoolPropertiesDefaults} are cached for the given {@link HystrixCommandKey} for the life of the JVM
     * or until {@link Hystrix#reset()} is called. Dynamic properties allow runtime changes. Read more on the <a href="https://github.com/Netflix/Hystrix/wiki/Configuration">Hystrix Wiki</a>.
     * 
     * @param setter
     *            Fluent interface for constructor arguments
     */
    protected HystrixCommand(Setter setter) {
        // use 'null' to specify use the default
        this(setter.groupKey, setter.commandKey, setter.threadPoolKey, null, null, setter.commandPropertiesDefaults, setter.threadPoolPropertiesDefaults, null, null, null, null, null);
    }

    /**
     * Allow constructing a {@link HystrixCommand} with injection of most aspects of its functionality.
     * <p>
     * Some of these never have a legitimate reason for injection except in unit testing.
     * <p>
     * Most of the args will revert to a valid default if 'null' is passed in.
     */
    private HystrixCommand(HystrixCommandGroupKey group, HystrixCommandKey key, HystrixThreadPoolKey threadPoolKey, HystrixCircuitBreaker circuitBreaker, HystrixThreadPool threadPool,
            HystrixCommandProperties.Setter commandPropertiesDefaults, HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults,
            HystrixCommandMetrics metrics, TryableSemaphore fallbackSemaphore, TryableSemaphore executionSemaphore,
            HystrixPropertiesStrategy propertiesStrategy, HystrixCommandExecutionHook executionHook) {
    	super(group, key, threadPoolKey, circuitBreaker, threadPool, commandPropertiesDefaults, threadPoolPropertiesDefaults, metrics, fallbackSemaphore, executionSemaphore, propertiesStrategy, executionHook);
    }

    /**
     * Fluent interface for arguments to the {@link HystrixNonBlockingCommand} constructor.
     * <p>
     * The required arguments are set via the 'with' factory method and optional arguments via the 'and' chained methods.
     * <p>
     * Example:
     * <pre> {@code
     *  Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("GroupName"))
                .andCommandKey(HystrixCommandKey.Factory.asKey("CommandName"))
                .andEventNotifier(notifier);
     * } </pre>
     */
    @NotThreadSafe
    public static class Setter {

        protected final HystrixCommandGroupKey groupKey;
        protected HystrixCommandKey commandKey;
        protected HystrixThreadPoolKey threadPoolKey;
        protected HystrixCommandProperties.Setter commandPropertiesDefaults;
        protected HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults;

        /**
         * Setter factory method containing required values.
         * <p>
         * All optional arguments can be set via the chained methods.
         * 
         * @param groupKey
         *            {@link HystrixCommandGroupKey} used to group together multiple {@link HystrixNonBlockingCommand} objects.
         *            <p>
         *            The {@link HystrixCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interace
         *            with,
         *            common business purpose etc.
         */
        protected Setter(HystrixCommandGroupKey groupKey) {
            this.groupKey = groupKey;
        }

        /**
         * Setter factory method with required values.
         * <p>
         * All optional arguments can be set via the chained methods.
         * 
         * @param groupKey
         *            {@link HystrixCommandGroupKey} used to group together multiple {@link HystrixNonBlockingCommand} objects.
         *            <p>
         *            The {@link HystrixCommandGroupKey} is used to represent a common relationship between commands. For example, a library or team name, the system all related commands interace
         *            with,
         *            common business purpose etc.
         */
        public static Setter withGroupKey(HystrixCommandGroupKey groupKey) {
            return new Setter(groupKey);
        }

        /**
         * @param commandKey
         *            {@link HystrixCommandKey} used to identify a {@link HystrixNonBlockingCommand} instance for statistics, circuit-breaker, properties, etc.
         *            <p>
         *            By default this will be derived from the instance class name.
         *            <p>
         *            NOTE: Every unique {@link HystrixCommandKey} will result in new instances of {@link HystrixCircuitBreaker}, {@link HystrixCommandMetrics} and {@link HystrixCommandProperties}.
         *            Thus,
         *            the number of variants should be kept to a finite and reasonable number to avoid high-memory usage or memory leacks.
         *            <p>
         *            Hundreds of keys is fine, tens of thousands is probably not.
         * @return Setter for fluent interface via method chaining
         */
        public Setter andCommandKey(HystrixCommandKey commandKey) {
            this.commandKey = commandKey;
            return this;
        }

        /**
         * @param threadPoolKey
         *            {@link HystrixThreadPoolKey} used to define which thread-pool this command should run in (when configured to run on separate threads via
         *            {@link HystrixCommandProperties#executionIsolationStrategy()}).
         *            <p>
         *            By default this is derived from the {@link HystrixCommandGroupKey} but if injected this allows multiple commands to have the same {@link HystrixCommandGroupKey} but different
         *            thread-pools.
         * @return Setter for fluent interface via method chaining
         */
        public Setter andThreadPoolKey(HystrixThreadPoolKey threadPoolKey) {
            this.threadPoolKey = threadPoolKey;
            return this;
        }

        /**
         * Optional
         * 
         * @param commandPropertiesDefaults
         *            {@link HystrixCommandProperties.Setter} with property overrides for this specific instance of {@link HystrixNonBlockingCommand}.
         *            <p>
         *            See the {@link HystrixPropertiesStrategy} JavaDocs for more information on properties and order of precedence.
         * @return Setter for fluent interface via method chaining
         */
        public Setter andCommandPropertiesDefaults(HystrixCommandProperties.Setter commandPropertiesDefaults) {
            this.commandPropertiesDefaults = commandPropertiesDefaults;
            return this;
        }

        /**
         * Optional
         * 
         * @param threadPoolPropertiesDefaults
         *            {@link HystrixThreadPoolProperties.Setter} with property overrides for the {@link HystrixThreadPool} used by this specific instance of {@link HystrixNonBlockingCommand}.
         *            <p>
         *            See the {@link HystrixPropertiesStrategy} JavaDocs for more information on properties and order of precedence.
         * @return Setter for fluent interface via method chaining
         */
        public Setter andThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults) {
            this.threadPoolPropertiesDefaults = threadPoolPropertiesDefaults;
            return this;
        }

    }

   
    /**
     * Implement this method with code to be executed when {@link #execute()} or {@link #queue()} are invoked.
     * 
     * @return R response type
     * @throws Exception
     *             if command execution fails
     */
    protected abstract R run() throws Exception;

    /**
     * If {@link #execute()} or {@link #queue()} fails in any way then this method will be invoked to provide an opportunity to return a fallback response.
     * <p>
     * This should do work that does not require network transport to produce.
     * <p>
     * In other words, this should be a static or cached result that can immediately be returned upon failure.
     * <p>
     * If network traffic is wanted for fallback (such as going to MemCache) then the fallback implementation should invoke another {@link HystrixCommand} instance that protects against that network
     * access and possibly has another level of fallback that does not involve network access.
     * <p>
     * DEFAULT BEHAVIOR: It throws UnsupportedOperationException.
     * 
     * @return R or throw UnsupportedOperationException if not implemented
     */
    protected R getFallback() {
        throw new UnsupportedOperationException("No fallback available.");
    }

    
    /**
     * A lazy {@link Observable} that will execute the command when subscribed to.
     * <p>
     * <b>Callback Scheduling</b>
     * <p>
     * <ul>
     * <li>When using {@link ExecutionIsolationStrategy#THREAD} this defaults to using {@link Schedulers#threadPoolForComputation()} for callbacks.</li>
     * <li>When using {@link ExecutionIsolationStrategy#SEMAPHORE} this defaults to using {@link Schedulers#immediate()} for callbacks.</li>
     * </ul>
     * <p>
     * See https://github.com/Netflix/RxJava/wiki for more information.
     * 
     * @return {@code Observable<R>} that lazily executes and calls back with the result of {@link #run()} execution or a fallback from {@link #getFallback()} if the command fails for any reason.
     * 
     * @throws HystrixRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Observer#onError} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HystrixBadRequestException
     *             via {@code Observer#onError} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     */
    public Observable<R> toObservable() {
        if (properties.executionIsolationStrategy().get().equals(ExecutionIsolationStrategy.THREAD)) {
            return toObservable(Schedulers.threadPoolForComputation());
        } else {
            // semaphore isolation is all blocking, no new threads involved
            // so we'll use the calling thread
            return toObservable(Schedulers.immediate());
        }
    }

    /**
     * A lazy {@link Observable} that will execute the command when subscribed to.
     * <p>
     * See https://github.com/Netflix/RxJava/wiki for more information.
     * 
     * @param observeOn
     *            The {@link Scheduler} to execute callbacks on.
     * @return {@code Observable<R>} that lazily executes and calls back with the result of {@link #run()} execution or a fallback from {@link #getFallback()} if the command fails for any reason.
     * @throws HystrixRuntimeException
     *             if a fallback does not exist
     *             <p>
     *             <ul>
     *             <li>via {@code Observer#onError} if a failure occurs</li>
     *             <li>or immediately if the command can not be queued (such as short-circuited, thread-pool/semaphore rejected)</li>
     *             </ul>
     * @throws HystrixBadRequestException
     *             via {@code Observer#onError} if invalid arguments or state were used representing a user failure, not a system failure
     * @throws IllegalStateException
     *             if invoked more than once
     */
    public Observable<R> toObservable(Scheduler observeOn) {
        return toObservable(observeOn, true);
    }

    protected ObservableCommand<R> toObservable(Scheduler observeOn, boolean performAsyncTimeout) {
        /* this is a stateful object so can only be used once */
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("This instance can only be executed once. Please instantiate a new instance.");
        }

        /* try from cache first */
        if (isRequestCachingEnabled()) {
            Observable<R> fromCache = requestCache.get(getCacheKey());
            if (fromCache != null) {
                /* mark that we received this response from cache */
                metrics.markResponseFromCache();
                return new CachedObservableResponse<R>((CachedObservableOriginal<R>) fromCache, this);
            }
        }

        final HystrixCommand<R> _this = this;

        // create an Observable that will lazily execute when subscribed to
        Observable<R> o = Observable.create(new OnSubscribe<R>() {

            @Override
            public void call(Subscriber<? super R> observer) {
                try {
                    /* used to track userThreadExecutionTime */
                    invocationStartTime = System.currentTimeMillis();

                    // mark that we're starting execution on the ExecutionHook
                    executionHook.onStart(_this);

                    /* determine if we're allowed to execute */
                    if (!circuitBreaker.allowRequest()) {
                        // record that we are returning a short-circuited fallback
                        metrics.markShortCircuited();
                        // short-circuit and go directly to fallback (or throw an exception if no fallback implemented)
                        try {
                            observer.onNext(getFallbackOrThrowException(HystrixEventType.SHORT_CIRCUITED, FailureType.SHORTCIRCUIT, "short-circuited"));
                            observer.onCompleted();
                        } catch (Exception e) {
                            observer.onError(e);
                        }
                    } else {
                        /* not short-circuited so proceed with queuing the execution */
                        try {
                            if (properties.executionIsolationStrategy().get().equals(ExecutionIsolationStrategy.THREAD)) {
                                subscribeWithThreadIsolation(observer);
                            } else {
                                subscribeWithSemaphoreIsolation(observer);
                            }
                        } catch (RuntimeException e) {
                            observer.onError(e);
                        }
                    }
                } finally {
                    recordExecutedCommand();
                }
            }
        });

        if (properties.executionIsolationStrategy().get().equals(ExecutionIsolationStrategy.THREAD)) {
            // wrap for timeout support
            o = o.lift(new TimeoutOperator<R>(_this, performAsyncTimeout));
        }

        // error handling
        o = o.onErrorResumeNext(new Func1<Throwable, Observable<R>>() {

            @Override
            public Observable<R> call(Throwable e) {
                // count that we are throwing an exception and re-throw it
                metrics.markExceptionThrown();
                return Observable.error(e);
            }
        });

        // we want to hand off work to a different scheduler so we don't tie up the Hystrix thread
        if (!Schedulers.immediate().equals(observeOn)) {
            // don't waste overhead if it's the 'immediate' scheduler
            // otherwise we'll 'observeOn' and wrap with the HystrixContextScheduler
            // to copy state across threads (if threads are involved)
            o = o.observeOn(new HystrixContextScheduler(concurrencyStrategy, observeOn));
        }

        o = o.finallyDo(new Action0() {

            @Override
            public void call() {
                Reference<TimerListener> tl = timeoutTimer.get();
                if (tl != null) {
                    tl.clear();
                }
            }

        });

        // put in cache
        if (isRequestCachingEnabled()) {
            // wrap it for caching
            o = new CachedObservableOriginal<R>(o.cache(), this);
            Observable<R> fromCache = requestCache.putIfAbsent(getCacheKey(), o);
            if (fromCache != null) {
                // another thread beat us so we'll use the cached value instead
                o = new CachedObservableResponse<R>((CachedObservableOriginal<R>) fromCache, this);
            }
            // we just created an ObservableCommand so we cast and return it
            return (ObservableCommand<R>) o;
        } else {
            // no request caching so a simple wrapper just to pass 'this' along with the Observable
            return new ObservableCommand<R>(o, this);
        }
    }

   
    private static class TimeoutOperator<R> implements Operator<R, R> {

        final HystrixCommand<R> originalCommand;
        final boolean isNonBlocking;
        
        public TimeoutOperator(final HystrixCommand<R> originalCommand, final boolean isNonBlocking) {
            this.originalCommand = originalCommand;
            this.isNonBlocking = isNonBlocking;
        }
        @Override
        public Subscriber<R> call(final Subscriber<? super R> child) {
            // onSubscribe setup the timer
            final CompositeSubscription s = new CompositeSubscription();

            TimerListener listener = new TimerListener() {

                @Override
                public void tick() {
                    // if we can go from NOT_EXECUTED to TIMED_OUT then we do the timeout codepath
                    // otherwise it means we lost a race and the run() execution completed
                    if (originalCommand.isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.TIMED_OUT)) {
                        // do fallback logic

                        // report timeout failure
                        originalCommand.metrics.markTimeout(System.currentTimeMillis() - originalCommand.invocationStartTime);

                        // we record execution time because we are returning before 
                        originalCommand.recordTotalExecutionTime(originalCommand.invocationStartTime);

                        try {
                            R v = originalCommand.getFallbackOrThrowException(HystrixEventType.TIMEOUT, FailureType.TIMEOUT, "timed-out", new TimeoutException());
                            child.onNext(v);
                            child.onCompleted();
                        } catch (HystrixRuntimeException re) {
                            child.onError(re);
                        }
                    }

                    s.unsubscribe();
                }

                @Override
                public int getIntervalTimeInMilliseconds() {
                    return originalCommand.properties.executionIsolationThreadTimeoutInMilliseconds().get();
                }
            };

            Reference<TimerListener> _tl = null;
            if (isNonBlocking) {
                /*
                 * Scheduling a separate timer to do timeouts is more expensive
                 * so we'll only do it if we're being used in a non-blocking manner.
                 */
                _tl = HystrixTimer.getInstance().addTimerListener(listener);
            } else {
                /*
                 * Otherwise we just set the hook that queue().get() can trigger if a timeout occurs.
                 * 
                 * This allows the blocking and non-blocking approaches to be coded basically the same way
                 * though it is admittedly awkward if we were just blocking (the use of Reference annoys me for example)
                 */
                _tl = new SoftReference<TimerListener>(listener);
            }
            final Reference<TimerListener> tl = _tl;

            // set externally so execute/queue can see this
            originalCommand.timeoutTimer.set(tl);
            
            
            return new Subscriber<R>(child) {

                @Override
                public void onCompleted() {
                    tl.clear();
                    child.onCompleted();
                }

                @Override
                public void onError(Throwable e) {
                    tl.clear();
                    child.onError(e);
                }

                @Override
                public void onNext(R t) {
                    // as soon as we receive data we will turn off the timer
                    tl.clear();
                    child.onNext(t);
                }
                
            };
        }
        

    }

    private void subscribeWithSemaphoreIsolation(final Subscriber<? super R> observer) {
        TryableSemaphore executionSemaphore = getExecutionSemaphore();
        // acquire a permit
        if (executionSemaphore.tryAcquire()) {
            try {
                try {
                    // store the command that is being run
                    Hystrix.startCurrentThreadExecutingCommand(getCommandKey());

                    // execute outside of future so that fireAndForget will still work (ie. someone calls queue() but not get()) and so that multiple requests can be deduped through request caching
                    R r = executeCommand();
                    r = executionHook.onComplete(this, r);
                    observer.onNext(r);
                    /* execution time (must occur before terminal state otherwise a race condition can occur if requested by client) */
                    recordTotalExecutionTime(invocationStartTime);
                    /* now complete which releases the consumer */
                    observer.onCompleted();
                } catch (Exception e) {
                    /* execution time (must occur before terminal state otherwise a race condition can occur if requested by client) */
                    recordTotalExecutionTime(invocationStartTime);
                    observer.onError(e);
                } finally {
                    // pop the command that is being run
                    Hystrix.endCurrentThreadExecutingCommand();
                }

            } finally {
                // release the semaphore
                executionSemaphore.release();
            }
        } else {
            metrics.markSemaphoreRejection();
            logger.debug("HystrixCommand Execution Rejection by Semaphore."); // debug only since we're throwing the exception and someone higher will do something with it
            // retrieve a fallback or throw an exception if no fallback available
            observer.onNext(getFallbackOrThrowException(HystrixEventType.SEMAPHORE_REJECTED, FailureType.REJECTED_SEMAPHORE_EXECUTION, "could not acquire a semaphore for execution"));
            observer.onCompleted();
        }
    }

    private void subscribeWithThreadIsolation(final Subscriber<? super R> observer) {
        // mark that we are executing in a thread (even if we end up being rejected we still were a THREAD execution and not SEMAPHORE)
        isExecutedInThread.set(true);

        // final reference to the current calling thread so the child thread can access it if needed
        final Thread callingThread = Thread.currentThread();

        final HystrixCommand<R> _this = this;

        try {
            if (!threadPool.isQueueSpaceAvailable()) {
                // we are at the property defined max so want to throw a RejectedExecutionException to simulate reaching the real max 
                throw new RejectedExecutionException("Rejected command because thread-pool queueSize is at rejection threshold.");
            }

            // wrap the synchronous execute() method in a Callable and execute in the threadpool
            final Future<R> f = threadPool.getExecutor().submit(concurrencyStrategy.wrapCallable(new HystrixContextCallable<R>(new Callable<R>() {

                @Override
                public R call() throws Exception {
                	boolean recordDuration = true;
                    try {
                        // assign 'callingThread' to our NFExceptionThreadingUtility ThreadLocal variable so that if we blow up
                        // anywhere along the way the exception knows who the calling thread is and can include it in the stacktrace
                        ExceptionThreadingUtility.assignCallingThread(callingThread);

                        // execution hook
                        executionHook.onThreadStart(_this);

                        // count the active thread
                        threadPool.markThreadExecution();

                        try {
                            // store the command that is being run
                            Hystrix.startCurrentThreadExecutingCommand(getCommandKey());
                            // execute the command
                            R r = executeCommand();
                            // if we can go from NOT_EXECUTED to COMPLETED then we did not timeout
                            if (isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.COMPLETED)) {
                                // give the hook an opportunity to modify it
                                r = executionHook.onComplete(_this, r);
                                // pass to the observer
                                observer.onNext(r);
                                // state changes before termination
                                preTerminationWork(recordDuration);
                                /* now complete which releases the consumer */
                                observer.onCompleted();
                                return r;
                            } else {
                                // this means we lost the race and the timeout logic has or is being executed
                                // state changes before termination
                            	// do not recordDuration as this is a timeout and the tick would have set the duration already.
                            	recordDuration = false;
                                preTerminationWork(recordDuration);
                                return null;
                            }
                        } finally {
                            // pop this off the thread now that it's done
                            Hystrix.endCurrentThreadExecutingCommand();
                        }
                    } catch (Exception e) {
                        // state changes before termination
                        preTerminationWork(recordDuration);
                        // if we can go from NOT_EXECUTED to COMPLETED then we did not timeout
                        if (isCommandTimedOut.compareAndSet(TimedOutStatus.NOT_EXECUTED, TimedOutStatus.COMPLETED)) {
                            observer.onError(e);
                        }
                        throw e;
                    }
                }

                private void preTerminationWork(boolean recordDuration) {
                	if(recordDuration) {
                		/* execution time (must occur before terminal state otherwise a race condition can occur if requested by client) */
                		recordTotalExecutionTime(invocationStartTime);
                	}
                    threadPool.markThreadCompletion();

                    try {
                        executionHook.onThreadComplete(_this);
                    } catch (Exception e) {
                        logger.warn("ExecutionHook.onThreadComplete threw an exception that will be ignored.", e);
                    }
                }

            })));


            observer.add(Subscriptions.create(new Action0() {

                @Override
                public void call() {
                    f.cancel(properties.executionIsolationThreadInterruptOnTimeout().get());
                }

            }));
            
        } catch (RejectedExecutionException e) {
            // mark on counter
            metrics.markThreadPoolRejection();
            // use a fallback instead (or throw exception if not implemented)
            observer.onNext(getFallbackOrThrowException(HystrixEventType.THREAD_POOL_REJECTED, FailureType.REJECTED_THREAD_EXECUTION, "could not be queued for execution", e));
            observer.onCompleted();
        } catch (Exception e) {
            // unknown exception
            logger.error(getLogMessagePrefix() + ": Unexpected exception while submitting to queue.", e);
            observer.onNext(getFallbackOrThrowException(HystrixEventType.THREAD_POOL_REJECTED, FailureType.REJECTED_THREAD_EXECUTION, "had unexpected exception while attempting to queue for execution.", e));
            observer.onCompleted();
        }
    }

    /**
     * Executes the command and marks success/failure on the circuit-breaker and calls <code>getFallback</code> if a failure occurs.
     * <p>
     * This does NOT use the circuit-breaker to determine if the command should be executed, use <code>execute()</code> for that. This method will ALWAYS attempt to execute the method.
     * 
     * @return R
     */
    private R executeCommand() {
        /**
         * NOTE: Be very careful about what goes in this method. It gets invoked within another thread in most circumstances.
         * 
         * The modifications of booleans 'isResponseFromFallback' etc are going across thread-boundaries thus those
         * variables MUST be volatile otherwise they are not guaranteed to be seen by the user thread when the executing thread modifies them.
         */

        /* capture start time for logging */
        long startTime = System.currentTimeMillis();
        // allow tracking how many concurrent threads are executing
        metrics.incrementConcurrentExecutionCount();
        try {
            executionHook.onRunStart(this);
            R response = executionHook.onRunSuccess(this, run());
            long duration = System.currentTimeMillis() - startTime;
            metrics.addCommandExecutionTime(duration);

            if (isCommandTimedOut.get() == TimedOutStatus.TIMED_OUT) {
                // the command timed out in the wrapping thread so we will return immediately
                // and not increment any of the counters below or other such logic
                return null;
            } else {
                // report success
                executionResult = executionResult.addEvents(HystrixEventType.SUCCESS);
                metrics.markSuccess(duration);
                circuitBreaker.markSuccess();
                eventNotifier.markCommandExecution(getCommandKey(), properties.executionIsolationStrategy().get(), (int) duration, executionResult.events);
                return response;
            }
        } catch (HystrixBadRequestException e) {
            try {
                Exception decorated = executionHook.onRunError(this, e);
                if (decorated instanceof HystrixBadRequestException) {
                    e = (HystrixBadRequestException) decorated;
                } else {
                    logger.warn("ExecutionHook.onRunError returned an exception that was not an instance of HystrixBadRequestException so will be ignored.", decorated);
                }
            } catch (Exception hookException) {
                logger.warn("Error calling ExecutionHook.onRunError", hookException);
            }

            /*
             * HystrixBadRequestException is treated differently and allowed to propagate without any stats tracking or fallback logic
             */
            throw e;
        } catch (Throwable t) {
            Exception e = null;
            if (t instanceof Exception) {
                e = (Exception) t;
            } else {
                // Hystrix 1.x uses Exception, not Throwable so to prevent a breaking change Throwable will be wrapped in Exception
                e = new Exception("Throwable caught while executing.", t);
            }
            try {
                e = executionHook.onRunError(this, e);
            } catch (Exception hookException) {
                logger.warn("Error calling ExecutionHook.endRunFailure", hookException);
            }

            if (isCommandTimedOut.get() == TimedOutStatus.TIMED_OUT) {
                // http://jira/browse/API-4905 HystrixCommand: Error/Timeout Double-count if both occur
                // this means we have already timed out then we don't count this error stat and we just return
                // as this means the user-thread has already returned, we've already done fallback logic
                // and we've already counted the timeout stat
                logger.debug("Error executing HystrixCommand.run() [TimedOut]. Proceeding to fallback logic ...", e);
                return null;
            } else {
                logger.debug("Error executing HystrixCommand.run(). Proceeding to fallback logic ...", e);
            }
            // report failure
            metrics.markFailure(System.currentTimeMillis() - startTime);
            // record the exception
            executionResult = executionResult.setException(e);
            return getFallbackOrThrowException(HystrixEventType.FAILURE, FailureType.COMMAND_EXCEPTION, "failed", e);
        } finally {
            metrics.decrementConcurrentExecutionCount();
            // record that we're completed
            isExecutionComplete.set(true);
        }
    }

    /**
     * Execute <code>getFallback()</code> within protection of a semaphore that limits number of concurrent executions.
     * <p>
     * Fallback implementations shouldn't perform anything that can be blocking, but we protect against it anyways in case someone doesn't abide by the contract.
     * <p>
     * If something in the <code>getFallback()</code> implementation is latent (such as a network call) then the semaphore will cause us to start rejecting requests rather than allowing potentially
     * all threads to pile up and block.
     * 
     * @return K
     * @throws UnsupportedOperationException
     *             if getFallback() not implemented
     * @throws HystrixException
     *             if getFallback() fails (throws an Exception) or is rejected by the semaphore
     */
    private R getFallbackWithProtection() {
        TryableSemaphore fallbackSemaphore = getFallbackSemaphore();
        // acquire a permit
        if (fallbackSemaphore.tryAcquire()) {
            try {
                executionHook.onFallbackStart(this);
                return executionHook.onFallbackSuccess(this, getFallback());
            } catch (RuntimeException e) {
                Exception decorated = executionHook.onFallbackError(this, e);
                if (decorated instanceof RuntimeException) {
                    e = (RuntimeException) decorated;
                } else {
                    logger.warn("ExecutionHook.onFallbackError returned an exception that was not an instance of RuntimeException so will be ignored.", decorated);
                }

                // re-throw to calling method
                throw e;
            } finally {
                fallbackSemaphore.release();
            }
        } else {
            metrics.markFallbackRejection();
            logger.debug("HystrixCommand Fallback Rejection."); // debug only since we're throwing the exception and someone higher will do something with it
            // if we couldn't acquire a permit, we "fail fast" by throwing an exception
            throw new HystrixRuntimeException(FailureType.REJECTED_SEMAPHORE_FALLBACK, this.getClass(), getLogMessagePrefix() + " fallback execution rejected.", null, null);
        }
    }

   
    /**
     * @throws HystrixRuntimeException
     */
    private R getFallbackOrThrowException(HystrixEventType eventType, FailureType failureType, String message) {
        return getFallbackOrThrowException(eventType, failureType, message, null);
    }

    /**
     * @throws HystrixRuntimeException
     */
    private R getFallbackOrThrowException(HystrixEventType eventType, FailureType failureType, String message, Exception e) {
        try {
            if (properties.fallbackEnabled().get()) {
                /* fallback behavior is permitted so attempt */
                try {
                    // record the executionResult
                    // do this before executing fallback so it can be queried from within getFallback (see See https://github.com/Netflix/Hystrix/pull/144)
                    executionResult = executionResult.addEvents(eventType);

                    // retrieve the fallback
                    R fallback = getFallbackWithProtection();
                    // mark fallback on counter
                    metrics.markFallbackSuccess();
                    // record the executionResult
                    executionResult = executionResult.addEvents(HystrixEventType.FALLBACK_SUCCESS);
                    return executionHook.onComplete(this, fallback);
                } catch (UnsupportedOperationException fe) {
                    logger.debug("No fallback for HystrixCommand. ", fe); // debug only since we're throwing the exception and someone higher will do something with it

                    /* executionHook for all errors */
                    try {
                        e = executionHook.onError(this, failureType, e);
                    } catch (Exception hookException) {
                        logger.warn("Error calling ExecutionHook.onError", hookException);
                    }

                    throw new HystrixRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and no fallback available.", e, fe);
                } catch (Exception fe) {
                    logger.debug("HystrixCommand execution " + failureType.name() + " and fallback retrieval failed.", fe);
                    metrics.markFallbackFailure();
                    // record the executionResult
                    executionResult = executionResult.addEvents(HystrixEventType.FALLBACK_FAILURE);

                    /* executionHook for all errors */
                    try {
                        e = executionHook.onError(this, failureType, e);
                    } catch (Exception hookException) {
                        logger.warn("Error calling ExecutionHook.onError", hookException);
                    }

                    throw new HystrixRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and failed retrieving fallback.", e, fe);
                }
            } else {
                /* fallback is disabled so throw HystrixRuntimeException */

                logger.debug("Fallback disabled for HystrixCommand so will throw HystrixRuntimeException. ", e); // debug only since we're throwing the exception and someone higher will do something with it
                // record the executionResult
                executionResult = executionResult.addEvents(eventType);

                /* executionHook for all errors */
                try {
                    e = executionHook.onError(this, failureType, e);
                } catch (Exception hookException) {
                    logger.warn("Error calling ExecutionHook.onError", hookException);
                }
                throw new HystrixRuntimeException(failureType, this.getClass(), getLogMessagePrefix() + " " + message + " and fallback disabled.", e, null);
            }
        } finally {
            // record that we're completed (to handle non-successful events we do it here as well as at the end of executeCommand
            isExecutionComplete.set(true);
        }
    }

    /**
     * Record that this command was executed in the HystrixRequestLog.
     * <p>
     * This can be treated as an async operation as it just adds a references to "this" in the log even if the current command is still executing.
     */
    protected void recordExecutedCommand() {
        if (properties.requestLogEnabled().get()) {
            // log this command execution regardless of what happened
            if (concurrencyStrategy instanceof HystrixConcurrencyStrategyDefault) {
                // if we're using the default we support only optionally using a request context
                if (HystrixRequestContext.isCurrentThreadInitialized()) {
                    HystrixRequestLog.getCurrentRequest(concurrencyStrategy).addExecutedCommand(this);
                }
            } else {
                // if it's a custom strategy it must ensure the context is initialized
                if (HystrixRequestLog.getCurrentRequest(concurrencyStrategy) != null) {
                    HystrixRequestLog.getCurrentRequest(concurrencyStrategy).addExecutedCommand(this);
                }
            }
        }
    }
    
    public static class UnitTest {

        @Before
        public void prepareForTest() {
            /* we must call this to simulate a new request lifecycle running and clearing caches */
            HystrixRequestContext.initializeContext();
        }

        @After
        public void cleanup() {
            // instead of storing the reference from initialize we'll just get the current state and shutdown
            if (HystrixRequestContext.getContextForCurrentThread() != null) {
                // it could have been set NULL by the test
                HystrixRequestContext.getContextForCurrentThread().shutdown();
            }

            // force properties to be clean as well
            ConfigurationManager.getConfigInstance().clear();
        }

        /**
         * Test a successful command execution.
         */
        @Test
        public void testExecutionSuccess() {
            try {
                TestHystrixCommand<Boolean> command = new SuccessfulTestCommand();
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
                assertEquals(true, command.execute());
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
                assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));

                assertEquals(null, command.getFailedExecutionException());

                assertTrue(command.getExecutionTimeInMilliseconds() > -1);
                assertTrue(command.isSuccessfulExecution());

                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

                assertEquals(0, command.builder.metrics.getHealthCounts().getErrorPercentage());

                assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());

            } catch (Exception e) {
                e.printStackTrace();
                fail("We received an exception.");
            }
        }

        /**
         * Test that a command can not be executed multiple times.
         */
        @Test
        public void testExecutionMultipleTimes() {
            SuccessfulTestCommand command = new SuccessfulTestCommand();
            assertFalse(command.isExecutionComplete());
            // first should succeed
            assertEquals(true, command.execute());
            assertTrue(command.isExecutionComplete());
            assertTrue(command.isExecutedInThread());
            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isSuccessfulExecution());
            try {
                // second should fail
                command.execute();
                fail("we should not allow this ... it breaks the state of request logs");
            } catch (IllegalStateException e) {
                e.printStackTrace();
                // we want to get here
            }

            try {
                // queue should also fail
                command.queue();
                fail("we should not allow this ... it breaks the state of request logs");
            } catch (IllegalStateException e) {
                e.printStackTrace();
                // we want to get here
            }
        }

        /**
         * Test a command execution that throws an HystrixException and didn't implement getFallback.
         */
        @Test
        public void testExecutionKnownFailureWithNoFallback() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            TestHystrixCommand<Boolean> command = new KnownFailureTestCommandWithoutFallback(circuitBreaker);
            try {
                command.execute();
                fail("we shouldn't get here");
            } catch (HystrixRuntimeException e) {
                e.printStackTrace();
                assertNotNull(e.getFallbackException());
                assertNotNull(e.getImplementingClass());
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
                assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            } catch (Exception e) {
                e.printStackTrace();
                fail("We should always get an HystrixRuntimeException when an error occurs.");
            }
            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a command execution that throws an unknown exception (not HystrixException) and didn't implement getFallback.
         */
        @Test
        public void testExecutionUnknownFailureWithNoFallback() {
            TestHystrixCommand<Boolean> command = new UnknownFailureTestCommandWithoutFallback();
            try {
                command.execute();
                fail("we shouldn't get here");
            } catch (HystrixRuntimeException e) {
                e.printStackTrace();
                assertNotNull(e.getFallbackException());
                assertNotNull(e.getImplementingClass());

            } catch (Exception e) {
                e.printStackTrace();
                fail("We should always get an HystrixRuntimeException when an error occurs.");
            }

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a command execution that fails but has a fallback.
         */
        @Test
        public void testExecutionFailureWithFallback() {
            TestHystrixCommand<Boolean> command = new KnownFailureTestCommandWithFallback(new TestCircuitBreaker());
            try {
                assertEquals(false, command.execute());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received a response from the fallback.");
            }

            assertEquals("we failed with a simulated issue", command.getFailedExecutionException().getMessage());

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a command execution that fails, has getFallback implemented but that fails as well.
         */
        @Test
        public void testExecutionFailureWithFallbackFailure() {
            TestHystrixCommand<Boolean> command = new KnownFailureTestCommandWithFallbackFailure();
            try {
                command.execute();
                fail("we shouldn't get here");
            } catch (HystrixRuntimeException e) {
                System.out.println("------------------------------------------------");
                e.printStackTrace();
                System.out.println("------------------------------------------------");
                assertNotNull(e.getFallbackException());
            }

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a successful command execution (asynchronously).
         */
        @Test
        public void testQueueSuccess() {
            TestHystrixCommand<Boolean> command = new SuccessfulTestCommand();
            try {
                Future<Boolean> future = command.queue();
                assertEquals(true, future.get());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We received an exception.");
            }

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isSuccessfulExecution());

            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a command execution (asynchronously) that throws an HystrixException and didn't implement getFallback.
         */
        @Test
        public void testQueueKnownFailureWithNoFallback() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            TestHystrixCommand<Boolean> command = new KnownFailureTestCommandWithoutFallback(circuitBreaker);
            try {
                command.queue().get();
                fail("we shouldn't get here");
            } catch (Exception e) {
                e.printStackTrace();
                if (e.getCause() instanceof HystrixRuntimeException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e.getCause();

                    assertNotNull(de.getFallbackException());
                    assertNotNull(de.getImplementingClass());
                } else {
                    fail("the cause should be HystrixRuntimeException");
                }
            }

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a command execution (asynchronously) that throws an unknown exception (not HystrixException) and didn't implement getFallback.
         */
        @Test
        public void testQueueUnknownFailureWithNoFallback() {
            TestHystrixCommand<Boolean> command = new UnknownFailureTestCommandWithoutFallback();
            try {
                command.queue().get();
                fail("we shouldn't get here");
            } catch (Exception e) {
                e.printStackTrace();
                if (e.getCause() instanceof HystrixRuntimeException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e.getCause();
                    assertNotNull(de.getFallbackException());
                    assertNotNull(de.getImplementingClass());
                } else {
                    fail("the cause should be HystrixRuntimeException");
                }
            }

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a command execution (asynchronously) that fails but has a fallback.
         */
        @Test
        public void testQueueFailureWithFallback() {
            TestHystrixCommand<Boolean> command = new KnownFailureTestCommandWithFallback(new TestCircuitBreaker());
            try {
                Future<Boolean> future = command.queue();
                assertEquals(false, future.get());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received a response from the fallback.");
            }

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a command execution (asynchronously) that fails, has getFallback implemented but that fails as well.
         */
        @Test
        public void testQueueFailureWithFallbackFailure() {
            TestHystrixCommand<Boolean> command = new KnownFailureTestCommandWithFallbackFailure();
            try {
                command.queue().get();
                fail("we shouldn't get here");
            } catch (Exception e) {
                if (e.getCause() instanceof HystrixRuntimeException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e.getCause();
                    e.printStackTrace();
                    assertNotNull(de.getFallbackException());
                } else {
                    fail("the cause should be HystrixRuntimeException");
                }
            }

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a successful command execution.
         */
        @Test
        public void testObserveSuccess() {
            try {
                TestHystrixCommand<Boolean> command = new SuccessfulTestCommand();
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
                assertEquals(true, command.observe().toBlockingObservable().single());
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
                assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));

                assertEquals(null, command.getFailedExecutionException());

                assertTrue(command.getExecutionTimeInMilliseconds() > -1);
                assertTrue(command.isSuccessfulExecution());

                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

                assertEquals(0, command.builder.metrics.getHealthCounts().getErrorPercentage());

                assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());

            } catch (Exception e) {
                e.printStackTrace();
                fail("We received an exception.");
            }
        }

        /**
         * Test a successful command execution.
         */
        @Test
        public void testObserveOnScheduler() throws Exception {
            for (int i = 0; i < 5; i++) {
                final AtomicReference<Thread> commandThread = new AtomicReference<Thread>();
                final AtomicReference<Thread> subscribeThread = new AtomicReference<Thread>();

                TestHystrixCommand<Boolean> command = new TestHystrixCommand<Boolean>(TestHystrixCommand.testPropsBuilder()) {

                    @Override
                    protected Boolean run() {
                        commandThread.set(Thread.currentThread());
                        return true;
                    }
                };

                final CountDownLatch latch = new CountDownLatch(1);

                command.toObservable(Schedulers.newThread()).subscribe(new Observer<Boolean>() {

                    @Override
                    public void onCompleted() {
                        latch.countDown();

                    }

                    @Override
                    public void onError(Throwable e) {
                        latch.countDown();
                        e.printStackTrace();

                    }

                    @Override
                    public void onNext(Boolean args) {
                        subscribeThread.set(Thread.currentThread());
                    }
                });

                if (!latch.await(2000, TimeUnit.MILLISECONDS)) {
                    fail("timed out");
                }

                assertNotNull(commandThread.get());
                assertNotNull(subscribeThread.get());

                System.out.println("Command Thread: " + commandThread.get());
                System.out.println("Subscribe Thread: " + subscribeThread.get());

                assertTrue(commandThread.get().getName().startsWith("hystrix-"));
                assertFalse(subscribeThread.get().getName().startsWith("hystrix-"));
                assertTrue(subscribeThread.get().getName().startsWith("Rx"));
            }
        }

        /**
         * Test a successful command execution.
         */
        @Test
        public void testObserveOnComputationSchedulerByDefaultForThreadIsolation() throws Exception {

            final AtomicReference<Thread> commandThread = new AtomicReference<Thread>();
            final AtomicReference<Thread> subscribeThread = new AtomicReference<Thread>();

            TestHystrixCommand<Boolean> command = new TestHystrixCommand<Boolean>(TestHystrixCommand.testPropsBuilder()) {

                @Override
                protected Boolean run() {
                    commandThread.set(Thread.currentThread());
                    return true;
                }
            };

            final CountDownLatch latch = new CountDownLatch(1);

            command.toObservable().subscribe(new Observer<Boolean>() {

                @Override
                public void onCompleted() {
                    latch.countDown();

                }

                @Override
                public void onError(Throwable e) {
                    latch.countDown();
                    e.printStackTrace();

                }

                @Override
                public void onNext(Boolean args) {
                    subscribeThread.set(Thread.currentThread());
                }
            });

            if (!latch.await(2000, TimeUnit.MILLISECONDS)) {
                fail("timed out");
            }

            assertNotNull(commandThread.get());
            assertNotNull(subscribeThread.get());

            System.out.println("Command Thread: " + commandThread.get());
            System.out.println("Subscribe Thread: " + subscribeThread.get());

            assertTrue(commandThread.get().getName().startsWith("hystrix-"));
            assertTrue(subscribeThread.get().getName().startsWith("RxComputationThreadPool"));
        }

        /**
         * Test a successful command execution.
         */
        @Test
        public void testObserveOnImmediateSchedulerByDefaultForSemaphoreIsolation() throws Exception {

            final AtomicReference<Thread> commandThread = new AtomicReference<Thread>();
            final AtomicReference<Thread> subscribeThread = new AtomicReference<Thread>();

            TestHystrixCommand<Boolean> command = new TestHystrixCommand<Boolean>(TestHystrixCommand.testPropsBuilder()
                    .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE))) {

                @Override
                protected Boolean run() {
                    commandThread.set(Thread.currentThread());
                    return true;
                }
            };

            final CountDownLatch latch = new CountDownLatch(1);

            command.toObservable().subscribe(new Observer<Boolean>() {

                @Override
                public void onCompleted() {
                    latch.countDown();

                }

                @Override
                public void onError(Throwable e) {
                    latch.countDown();
                    e.printStackTrace();

                }

                @Override
                public void onNext(Boolean args) {
                    subscribeThread.set(Thread.currentThread());
                }
            });

            if (!latch.await(2000, TimeUnit.MILLISECONDS)) {
                fail("timed out");
            }

            assertNotNull(commandThread.get());
            assertNotNull(subscribeThread.get());

            System.out.println("Command Thread: " + commandThread.get());
            System.out.println("Subscribe Thread: " + subscribeThread.get());

            String mainThreadName = Thread.currentThread().getName();

            // semaphore should be on the calling thread
            assertTrue(commandThread.get().getName().equals(mainThreadName));
            assertTrue(subscribeThread.get().getName().equals(mainThreadName));
        }

        /**
         * Test that the circuit-breaker will 'trip' and prevent command execution on subsequent calls.
         */
        @Test
        public void testCircuitBreakerTripsAfterFailures() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            /* fail 3 times and then it should trip the circuit and stop executing */
            // failure 1
            KnownFailureTestCommandWithFallback attempt1 = new KnownFailureTestCommandWithFallback(circuitBreaker);
            attempt1.execute();
            assertTrue(attempt1.isResponseFromFallback());
            assertFalse(attempt1.isCircuitBreakerOpen());
            assertFalse(attempt1.isResponseShortCircuited());

            // failure 2
            KnownFailureTestCommandWithFallback attempt2 = new KnownFailureTestCommandWithFallback(circuitBreaker);
            attempt2.execute();
            assertTrue(attempt2.isResponseFromFallback());
            assertFalse(attempt2.isCircuitBreakerOpen());
            assertFalse(attempt2.isResponseShortCircuited());

            // failure 3
            KnownFailureTestCommandWithFallback attempt3 = new KnownFailureTestCommandWithFallback(circuitBreaker);
            attempt3.execute();
            assertTrue(attempt3.isResponseFromFallback());
            assertFalse(attempt3.isResponseShortCircuited());
            // it should now be 'open' and prevent further executions
            assertTrue(attempt3.isCircuitBreakerOpen());

            // attempt 4
            KnownFailureTestCommandWithFallback attempt4 = new KnownFailureTestCommandWithFallback(circuitBreaker);
            attempt4.execute();
            assertTrue(attempt4.isResponseFromFallback());
            // this should now be true as the response will be short-circuited
            assertTrue(attempt4.isResponseShortCircuited());
            // this should remain open
            assertTrue(attempt4.isCircuitBreakerOpen());

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(4, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(4, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test that the circuit-breaker will 'trip' and prevent command execution on subsequent calls.
         */
        @Test
        public void testCircuitBreakerTripsAfterFailuresViaQueue() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            try {
                /* fail 3 times and then it should trip the circuit and stop executing */
                // failure 1
                KnownFailureTestCommandWithFallback attempt1 = new KnownFailureTestCommandWithFallback(circuitBreaker);
                attempt1.queue().get();
                assertTrue(attempt1.isResponseFromFallback());
                assertFalse(attempt1.isCircuitBreakerOpen());
                assertFalse(attempt1.isResponseShortCircuited());

                // failure 2
                KnownFailureTestCommandWithFallback attempt2 = new KnownFailureTestCommandWithFallback(circuitBreaker);
                attempt2.queue().get();
                assertTrue(attempt2.isResponseFromFallback());
                assertFalse(attempt2.isCircuitBreakerOpen());
                assertFalse(attempt2.isResponseShortCircuited());

                // failure 3
                KnownFailureTestCommandWithFallback attempt3 = new KnownFailureTestCommandWithFallback(circuitBreaker);
                attempt3.queue().get();
                assertTrue(attempt3.isResponseFromFallback());
                assertFalse(attempt3.isResponseShortCircuited());
                // it should now be 'open' and prevent further executions
                assertTrue(attempt3.isCircuitBreakerOpen());

                // attempt 4
                KnownFailureTestCommandWithFallback attempt4 = new KnownFailureTestCommandWithFallback(circuitBreaker);
                attempt4.queue().get();
                assertTrue(attempt4.isResponseFromFallback());
                // this should now be true as the response will be short-circuited
                assertTrue(attempt4.isResponseShortCircuited());
                // this should remain open
                assertTrue(attempt4.isCircuitBreakerOpen());

                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
                assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
                assertEquals(4, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
                assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

                assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

                assertEquals(4, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received fallbacks.");
            }
        }

        /**
         * Test that the circuit-breaker is shared across HystrixCommand objects with the same CommandKey.
         * <p>
         * This will test HystrixCommand objects with a single circuit-breaker (as if each injected with same CommandKey)
         * <p>
         * Multiple HystrixCommand objects with the same dependency use the same circuit-breaker.
         */
        @Test
        public void testCircuitBreakerAcrossMultipleCommandsButSameCircuitBreaker() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            /* fail 3 times and then it should trip the circuit and stop executing */
            // failure 1
            KnownFailureTestCommandWithFallback attempt1 = new KnownFailureTestCommandWithFallback(circuitBreaker);
            attempt1.execute();
            assertTrue(attempt1.isResponseFromFallback());
            assertFalse(attempt1.isCircuitBreakerOpen());
            assertFalse(attempt1.isResponseShortCircuited());

            // failure 2 with a different command, same circuit breaker
            KnownFailureTestCommandWithoutFallback attempt2 = new KnownFailureTestCommandWithoutFallback(circuitBreaker);
            try {
                attempt2.execute();
            } catch (Exception e) {
                // ignore ... this doesn't have a fallback so will throw an exception
            }
            assertTrue(attempt2.isFailedExecution());
            assertFalse(attempt2.isResponseFromFallback()); // false because no fallback
            assertFalse(attempt2.isCircuitBreakerOpen());
            assertFalse(attempt2.isResponseShortCircuited());

            // failure 3 of the Hystrix, 2nd for this particular HystrixCommand
            KnownFailureTestCommandWithFallback attempt3 = new KnownFailureTestCommandWithFallback(circuitBreaker);
            attempt3.execute();
            assertTrue(attempt2.isFailedExecution());
            assertTrue(attempt3.isResponseFromFallback());
            assertFalse(attempt3.isResponseShortCircuited());

            // it should now be 'open' and prevent further executions
            // after having 3 failures on the Hystrix that these 2 different HystrixCommand objects are for
            assertTrue(attempt3.isCircuitBreakerOpen());

            // attempt 4
            KnownFailureTestCommandWithFallback attempt4 = new KnownFailureTestCommandWithFallback(circuitBreaker);
            attempt4.execute();
            assertTrue(attempt4.isResponseFromFallback());
            // this should now be true as the response will be short-circuited
            assertTrue(attempt4.isResponseShortCircuited());
            // this should remain open
            assertTrue(attempt4.isCircuitBreakerOpen());

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(4, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test that the circuit-breaker is different between HystrixCommand objects with a different Hystrix.
         */
        @Test
        public void testCircuitBreakerAcrossMultipleCommandsAndDifferentDependency() {
            TestCircuitBreaker circuitBreaker_one = new TestCircuitBreaker();
            TestCircuitBreaker circuitBreaker_two = new TestCircuitBreaker();
            /* fail 3 times, twice on one Hystrix, once on a different Hystrix ... circuit-breaker should NOT open */

            // failure 1
            KnownFailureTestCommandWithFallback attempt1 = new KnownFailureTestCommandWithFallback(circuitBreaker_one);
            attempt1.execute();
            assertTrue(attempt1.isResponseFromFallback());
            assertFalse(attempt1.isCircuitBreakerOpen());
            assertFalse(attempt1.isResponseShortCircuited());

            // failure 2 with a different HystrixCommand implementation and different Hystrix
            KnownFailureTestCommandWithFallback attempt2 = new KnownFailureTestCommandWithFallback(circuitBreaker_two);
            attempt2.execute();
            assertTrue(attempt2.isResponseFromFallback());
            assertFalse(attempt2.isCircuitBreakerOpen());
            assertFalse(attempt2.isResponseShortCircuited());

            // failure 3 but only 2nd of the Hystrix.ONE
            KnownFailureTestCommandWithFallback attempt3 = new KnownFailureTestCommandWithFallback(circuitBreaker_one);
            attempt3.execute();
            assertTrue(attempt3.isResponseFromFallback());
            assertFalse(attempt3.isResponseShortCircuited());

            // it should remain 'closed' since we have only had 2 failures on Hystrix.ONE
            assertFalse(attempt3.isCircuitBreakerOpen());

            // this one should also remain closed as it only had 1 failure for Hystrix.TWO
            assertFalse(attempt2.isCircuitBreakerOpen());

            // attempt 4 (3rd attempt for Hystrix.ONE)
            KnownFailureTestCommandWithFallback attempt4 = new KnownFailureTestCommandWithFallback(circuitBreaker_one);
            attempt4.execute();
            // this should NOW flip to true as this is the 3rd failure for Hystrix.ONE
            assertTrue(attempt3.isCircuitBreakerOpen());
            assertTrue(attempt3.isResponseFromFallback());
            assertFalse(attempt3.isResponseShortCircuited());

            // Hystrix.TWO should still remain closed
            assertFalse(attempt2.isCircuitBreakerOpen());

            assertEquals(0, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(3, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(3, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker_one.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker_one.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(0, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(1, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(1, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker_two.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker_two.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(4, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test that the circuit-breaker being disabled doesn't wreak havoc.
         */
        @Test
        public void testExecutionSuccessWithCircuitBreakerDisabled() {
            TestHystrixCommand<Boolean> command = new TestCommandWithoutCircuitBreaker();
            try {
                assertEquals(true, command.execute());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We received an exception.");
            }

            // we'll still get metrics ... just not the circuit breaker opening/closing
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a command execution timeout where the command didn't implement getFallback.
         */
        @Test
        public void testExecutionTimeoutWithNoFallback() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_NOT_IMPLEMENTED);
            try {
                command.execute();
                fail("we shouldn't get here");
            } catch (Exception e) {
                //                e.printStackTrace();
                if (e instanceof HystrixRuntimeException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e;
                    assertNotNull(de.getFallbackException());
                    assertTrue(de.getFallbackException() instanceof UnsupportedOperationException);
                    assertNotNull(de.getImplementingClass());
                    assertNotNull(de.getCause());
                    assertTrue(de.getCause() instanceof TimeoutException);
                } else {
                    fail("the exception should be HystrixRuntimeException");
                }
            }
            // the time should be 50+ since we timeout at 50ms
            assertTrue("Execution Time is: " + command.getExecutionTimeInMilliseconds(), command.getExecutionTimeInMilliseconds() >= 50);

            assertTrue(command.isResponseTimedOut());
            assertFalse(command.isResponseFromFallback());
            assertFalse(command.isResponseRejected());

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a command execution timeout where the command implemented getFallback.
         */
        @Test
        public void testExecutionTimeoutWithFallback() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_SUCCESS);
            try {
                assertEquals(false, command.execute());
                // the time should be 50+ since we timeout at 50ms
                assertTrue("Execution Time is: " + command.getExecutionTimeInMilliseconds(), command.getExecutionTimeInMilliseconds() >= 50);
                assertTrue(command.isResponseTimedOut());
                assertTrue(command.isResponseFromFallback());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received a response from the fallback.");
            }

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a command execution timeout where the command implemented getFallback but it fails.
         */
        @Test
        public void testExecutionTimeoutFallbackFailure() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_FAILURE);
            try {
                command.execute();
                fail("we shouldn't get here");
            } catch (Exception e) {
                if (e instanceof HystrixRuntimeException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e;
                    assertNotNull(de.getFallbackException());
                    assertFalse(de.getFallbackException() instanceof UnsupportedOperationException);
                    assertNotNull(de.getImplementingClass());
                    assertNotNull(de.getCause());
                    assertTrue(de.getCause() instanceof TimeoutException);
                } else {
                    fail("the exception should be HystrixRuntimeException");
                }
            }
            // the time should be 50+ since we timeout at 50ms
            assertTrue("Execution Time is: " + command.getExecutionTimeInMilliseconds(), command.getExecutionTimeInMilliseconds() >= 50);
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test that the circuit-breaker counts a command execution timeout as a 'timeout' and not just failure.
         */
        @Test
        public void testCircuitBreakerOnExecutionTimeout() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_SUCCESS);
            try {
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));

                command.execute();

                assertTrue(command.isResponseFromFallback());
                assertFalse(command.isCircuitBreakerOpen());
                assertFalse(command.isResponseShortCircuited());

                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));

            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received a response from the fallback.");
            }

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isResponseTimedOut());

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test that the command finishing AFTER a timeout (because thread continues in background) does not register a SUCCESS
         */
        @Test
        public void testCountersOnExecutionTimeout() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_SUCCESS);
            try {
                command.execute();

                /* wait long enough for the command to have finished */
                Thread.sleep(200);

                /* response should still be the same as 'testCircuitBreakerOnExecutionTimeout' */
                assertTrue(command.isResponseFromFallback());
                assertFalse(command.isCircuitBreakerOpen());
                assertFalse(command.isResponseShortCircuited());

                assertTrue(command.getExecutionTimeInMilliseconds() > -1);
                assertTrue(command.isResponseTimedOut());
                assertFalse(command.isSuccessfulExecution());

                /* failure and timeout count should be the same as 'testCircuitBreakerOnExecutionTimeout' */
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));

                /* we should NOT have a 'success' counter */
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));

            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received a response from the fallback.");
            }

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a queued command execution timeout where the command didn't implement getFallback.
         * <p>
         * We specifically want to protect against developers queuing commands and using queue().get() without a timeout (such as queue().get(3000, TimeUnit.Milliseconds)) and ending up blocking
         * indefinitely by skipping the timeout protection of the execute() command.
         */
        @Test
        public void testQueuedExecutionTimeoutWithNoFallback() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_NOT_IMPLEMENTED);
            try {
                command.queue().get();
                fail("we shouldn't get here");
            } catch (Exception e) {
                e.printStackTrace();
                if (e instanceof ExecutionException && e.getCause() instanceof HystrixRuntimeException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e.getCause();
                    assertNotNull(de.getFallbackException());
                    assertTrue(de.getFallbackException() instanceof UnsupportedOperationException);
                    assertNotNull(de.getImplementingClass());
                    assertNotNull(de.getCause());
                    assertTrue(de.getCause() instanceof TimeoutException);
                } else {
                    fail("the exception should be ExecutionException with cause as HystrixRuntimeException");
                }
            }

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isResponseTimedOut());

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a queued command execution timeout where the command implemented getFallback.
         * <p>
         * We specifically want to protect against developers queuing commands and using queue().get() without a timeout (such as queue().get(3000, TimeUnit.Milliseconds)) and ending up blocking
         * indefinitely by skipping the timeout protection of the execute() command.
         */
        @Test
        public void testQueuedExecutionTimeoutWithFallback() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_SUCCESS);
            try {
                assertEquals(false, command.queue().get());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received a response from the fallback.");
            }

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a queued command execution timeout where the command implemented getFallback but it fails.
         * <p>
         * We specifically want to protect against developers queuing commands and using queue().get() without a timeout (such as queue().get(3000, TimeUnit.Milliseconds)) and ending up blocking
         * indefinitely by skipping the timeout protection of the execute() command.
         */
        @Test
        public void testQueuedExecutionTimeoutFallbackFailure() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_FAILURE);
            try {
                command.queue().get();
                fail("we shouldn't get here");
            } catch (Exception e) {
                if (e instanceof ExecutionException && e.getCause() instanceof HystrixRuntimeException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e.getCause();
                    assertNotNull(de.getFallbackException());
                    assertFalse(de.getFallbackException() instanceof UnsupportedOperationException);
                    assertNotNull(de.getImplementingClass());
                    assertNotNull(de.getCause());
                    assertTrue(de.getCause() instanceof TimeoutException);
                } else {
                    fail("the exception should be ExecutionException with cause as HystrixRuntimeException");
                }
            }

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a queued command execution timeout where the command didn't implement getFallback.
         * <p>
         * We specifically want to protect against developers queuing commands and using queue().get() without a timeout (such as queue().get(3000, TimeUnit.Milliseconds)) and ending up blocking
         * indefinitely by skipping the timeout protection of the execute() command.
         */
        @Test
        public void testObservedExecutionTimeoutWithNoFallback() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_NOT_IMPLEMENTED);
            try {
                command.observe().toBlockingObservable().single();
                fail("we shouldn't get here");
            } catch (Exception e) {
                e.printStackTrace();
                if (e instanceof HystrixRuntimeException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e;
                    assertNotNull(de.getFallbackException());
                    assertTrue(de.getFallbackException() instanceof UnsupportedOperationException);
                    assertNotNull(de.getImplementingClass());
                    assertNotNull(de.getCause());
                    assertTrue(de.getCause() instanceof TimeoutException);
                } else {
                    fail("the exception should be ExecutionException with cause as HystrixRuntimeException");
                }
            }

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isResponseTimedOut());

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a queued command execution timeout where the command implemented getFallback.
         * <p>
         * We specifically want to protect against developers queuing commands and using queue().get() without a timeout (such as queue().get(3000, TimeUnit.Milliseconds)) and ending up blocking
         * indefinitely by skipping the timeout protection of the execute() command.
         */
        @Test
        public void testObservedExecutionTimeoutWithFallback() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_SUCCESS);
            try {
                assertEquals(false, command.observe().toBlockingObservable().single());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received a response from the fallback.");
            }

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test a queued command execution timeout where the command implemented getFallback but it fails.
         * <p>
         * We specifically want to protect against developers queuing commands and using queue().get() without a timeout (such as queue().get(3000, TimeUnit.Milliseconds)) and ending up blocking
         * indefinitely by skipping the timeout protection of the execute() command.
         */
        @Test
        public void testObservedExecutionTimeoutFallbackFailure() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_FAILURE);
            try {
                command.observe().toBlockingObservable().single();
                fail("we shouldn't get here");
            } catch (Exception e) {
                if (e instanceof HystrixRuntimeException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e;
                    assertNotNull(de.getFallbackException());
                    assertFalse(de.getFallbackException() instanceof UnsupportedOperationException);
                    assertNotNull(de.getImplementingClass());
                    assertNotNull(de.getCause());
                    assertTrue(de.getCause() instanceof TimeoutException);
                } else {
                    fail("the exception should be ExecutionException with cause as HystrixRuntimeException");
                }
            }

            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, command.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test that the circuit-breaker counts a command execution timeout as a 'timeout' and not just failure.
         */
        @Test
        public void testShortCircuitFallbackCounter() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker().setForceShortCircuit(true);
            try {
                new KnownFailureTestCommandWithFallback(circuitBreaker).execute();

                assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));

                KnownFailureTestCommandWithFallback command = new KnownFailureTestCommandWithFallback(circuitBreaker);
                command.execute();
                assertEquals(2, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));

                // will be -1 because it never attempted execution
                assertTrue(command.getExecutionTimeInMilliseconds() == -1);
                assertTrue(command.isResponseShortCircuited());
                assertFalse(command.isResponseTimedOut());

                // because it was short-circuited to a fallback we don't count an error
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));

            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received a response from the fallback.");
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(2, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(2, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(2, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test when a command fails to get queued up in the threadpool where the command didn't implement getFallback.
         * <p>
         * We specifically want to protect against developers getting random thread exceptions and instead just correctly receiving HystrixRuntimeException when no fallback exists.
         */
        @Test
        public void testRejectedThreadWithNoFallback() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SingleThreadedPool pool = new SingleThreadedPool(1);
            // fill up the queue
            pool.queue.add(new Runnable() {

                @Override
                public void run() {
                    System.out.println("**** queue filler1 ****");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            });

            Future<Boolean> f = null;
            TestCommandRejection command = null;
            try {
                f = new TestCommandRejection(circuitBreaker, pool, 500, 600, TestCommandRejection.FALLBACK_NOT_IMPLEMENTED).queue();
                command = new TestCommandRejection(circuitBreaker, pool, 500, 600, TestCommandRejection.FALLBACK_NOT_IMPLEMENTED);
                command.queue();
                fail("we shouldn't get here");
            } catch (Exception e) {
                e.printStackTrace();

                // will be -1 because it never attempted execution
                assertTrue(command.getExecutionTimeInMilliseconds() == -1);
                assertTrue(command.isResponseRejected());
                assertFalse(command.isResponseShortCircuited());
                assertFalse(command.isResponseTimedOut());

                assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
                if (e instanceof HystrixRuntimeException && e.getCause() instanceof RejectedExecutionException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e;
                    assertNotNull(de.getFallbackException());
                    assertTrue(de.getFallbackException() instanceof UnsupportedOperationException);
                    assertNotNull(de.getImplementingClass());
                    assertNotNull(de.getCause());
                    assertTrue(de.getCause() instanceof RejectedExecutionException);
                } else {
                    fail("the exception should be HystrixRuntimeException with cause as RejectedExecutionException");
                }
            }

            try {
                f.get();
            } catch (Exception e) {
                e.printStackTrace();
                fail("The first one should succeed.");
            }

            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(50, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(2, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test when a command fails to get queued up in the threadpool where the command implemented getFallback.
         * <p>
         * We specifically want to protect against developers getting random thread exceptions and instead just correctly receives a fallback.
         */
        @Test
        public void testRejectedThreadWithFallback() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SingleThreadedPool pool = new SingleThreadedPool(1);
            // fill up the queue
            pool.queue.add(new Runnable() {

                @Override
                public void run() {
                    System.out.println("**** queue filler1 ****");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            });

            try {
                TestCommandRejection command1 = new TestCommandRejection(circuitBreaker, pool, 500, 600, TestCommandRejection.FALLBACK_SUCCESS);
                command1.queue();
                TestCommandRejection command2 = new TestCommandRejection(circuitBreaker, pool, 500, 600, TestCommandRejection.FALLBACK_SUCCESS);
                assertEquals(false, command2.queue().get());
                assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
                assertFalse(command1.isResponseRejected());
                assertFalse(command1.isResponseFromFallback());
                assertTrue(command2.isResponseRejected());
                assertTrue(command2.isResponseFromFallback());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received a response from the fallback.");
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(2, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test when a command fails to get queued up in the threadpool where the command implemented getFallback but it fails.
         * <p>
         * We specifically want to protect against developers getting random thread exceptions and instead just correctly receives an HystrixRuntimeException.
         */
        @Test
        public void testRejectedThreadWithFallbackFailure() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SingleThreadedPool pool = new SingleThreadedPool(1);
            // fill up the queue
            pool.queue.add(new Runnable() {

                @Override
                public void run() {
                    System.out.println("**** queue filler1 ****");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            });

            try {
                new TestCommandRejection(circuitBreaker, pool, 500, 600, TestCommandRejection.FALLBACK_FAILURE).queue();
                assertEquals(false, new TestCommandRejection(circuitBreaker, pool, 500, 600, TestCommandRejection.FALLBACK_FAILURE).queue().get());
                fail("we shouldn't get here");
            } catch (Exception e) {
                e.printStackTrace();
                assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
                if (e instanceof HystrixRuntimeException && e.getCause() instanceof RejectedExecutionException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e;
                    assertNotNull(de.getFallbackException());
                    assertFalse(de.getFallbackException() instanceof UnsupportedOperationException);
                    assertNotNull(de.getImplementingClass());
                    assertNotNull(de.getCause());
                    assertTrue(de.getCause() instanceof RejectedExecutionException);
                } else {
                    fail("the exception should be HystrixRuntimeException with cause as RejectedExecutionException");
                }
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(2, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test that we can reject a thread using isQueueSpaceAvailable() instead of just when the pool rejects.
         * <p>
         * For example, we have queue size set to 100 but want to reject when we hit 10.
         * <p>
         * This allows us to use FastProperties to control our rejection point whereas we can't resize a queue after it's created.
         */
        @Test
        public void testRejectedThreadUsingQueueSize() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SingleThreadedPool pool = new SingleThreadedPool(10, 1);
            // put 1 item in the queue
            // the thread pool won't pick it up because we're bypassing the pool and adding to the queue directly so this will keep the queue full
            pool.queue.add(new Runnable() {

                @Override
                public void run() {
                    System.out.println("**** queue filler1 ****");
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            });

            TestCommandRejection command = null;
            try {
                // this should fail as we already have 1 in the queue
                command = new TestCommandRejection(circuitBreaker, pool, 500, 600, TestCommandRejection.FALLBACK_NOT_IMPLEMENTED);
                command.queue();
                fail("we shouldn't get here");
            } catch (Exception e) {
                e.printStackTrace();

                // will be -1 because it never attempted execution
                assertTrue(command.getExecutionTimeInMilliseconds() == -1);
                assertTrue(command.isResponseRejected());
                assertFalse(command.isResponseShortCircuited());
                assertFalse(command.isResponseTimedOut());

                assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
                if (e instanceof HystrixRuntimeException && e.getCause() instanceof RejectedExecutionException) {
                    HystrixRuntimeException de = (HystrixRuntimeException) e;
                    assertNotNull(de.getFallbackException());
                    assertTrue(de.getFallbackException() instanceof UnsupportedOperationException);
                    assertNotNull(de.getImplementingClass());
                    assertNotNull(de.getCause());
                    assertTrue(de.getCause() instanceof RejectedExecutionException);
                } else {
                    fail("the exception should be HystrixRuntimeException with cause as RejectedExecutionException");
                }
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        @Test
        public void testTimedOutCommandDoesNotExecute() {
            SingleThreadedPool pool = new SingleThreadedPool(5);

            TestCircuitBreaker s1 = new TestCircuitBreaker();
            TestCircuitBreaker s2 = new TestCircuitBreaker();

            // execution will take 100ms, thread pool has a 600ms timeout
            CommandWithCustomThreadPool c1 = new CommandWithCustomThreadPool(s1, pool, 100, HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationThreadTimeoutInMilliseconds(600));
            // execution will take 200ms, thread pool has a 20ms timeout
            CommandWithCustomThreadPool c2 = new CommandWithCustomThreadPool(s2, pool, 200, HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationThreadTimeoutInMilliseconds(20));
            // queue up c1 first
            Future<Boolean> c1f = c1.queue();
            // now queue up c2 and wait on it
            boolean receivedException = false;
            try {
                c2.queue().get();
            } catch (Exception e) {
                // we expect to get an exception here
                receivedException = true;
            }

            if (!receivedException) {
                fail("We expect to receive an exception for c2 as it's supposed to timeout.");
            }

            // c1 will complete after 100ms
            try {
                c1f.get();
            } catch (Exception e1) {
                e1.printStackTrace();
                fail("we should not have failed while getting c1");
            }
            assertTrue("c1 is expected to executed but didn't", c1.didExecute);

            // c2 will timeout after 20 ms ... we'll wait longer than the 200ms time to make sure
            // the thread doesn't keep running in the background and execute
            try {
                Thread.sleep(400);
            } catch (Exception e) {
                throw new RuntimeException("Failed to sleep");
            }
            assertFalse("c2 is not expected to execute, but did", c2.didExecute);

            assertEquals(1, s1.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, s1.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, s1.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, s1.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, s1.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, s1.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, s1.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, s1.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, s1.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, s1.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, s1.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, s1.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(0, s2.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, s2.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, s2.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, s2.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, s2.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, s2.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, s2.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, s2.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, s2.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, s2.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, s2.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, s2.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(2, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        @Test
        public void testFallbackSemaphore() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            // single thread should work
            try {
                boolean result = new TestSemaphoreCommandWithSlowFallback(circuitBreaker, 1, 200).queue().get();
                assertTrue(result);
            } catch (Exception e) {
                // we shouldn't fail on this one
                throw new RuntimeException(e);
            }

            // 2 threads, the second should be rejected by the fallback semaphore
            boolean exceptionReceived = false;
            Future<Boolean> result = null;
            try {
            	System.out.println("c2 start: " + System.currentTimeMillis());
                result = new TestSemaphoreCommandWithSlowFallback(circuitBreaker, 1, 800).queue();
                System.out.println("c2 after queue: " + System.currentTimeMillis());
                // make sure that thread gets a chance to run before queuing the next one
                Thread.sleep(50);
                System.out.println("c3 start: " + System.currentTimeMillis());
                Future<Boolean> result2 = new TestSemaphoreCommandWithSlowFallback(circuitBreaker, 1, 200).queue();
                System.out.println("c3 after queue: " + System.currentTimeMillis());
                result2.get();
            } catch (Exception e) {
                e.printStackTrace();
                exceptionReceived = true;
            }

            try {
                assertTrue(result.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            if (!exceptionReceived) {
                fail("We expected an exception on the 2nd get");
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            // TestSemaphoreCommandWithSlowFallback always fails so all 3 should show failure
            assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            // the 1st thread executes single-threaded and gets a fallback, the next 2 are concurrent so only 1 of them is permitted by the fallback semaphore so 1 is rejected
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            // whenever a fallback_rejection occurs it is also a fallback_failure
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(2, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            // we should not have rejected any via the "execution semaphore" but instead via the "fallback semaphore"
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            // the rest should not be involved in this test
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(3, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        @Test
        public void testExecutionSemaphoreWithQueue() {
            final TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            // single thread should work
            try {
                boolean result = new TestSemaphoreCommand(circuitBreaker, 1, 200).queue().get();
                assertTrue(result);
            } catch (Exception e) {
                // we shouldn't fail on this one
                throw new RuntimeException(e);
            }

            final AtomicBoolean exceptionReceived = new AtomicBoolean();

            final TryableSemaphore semaphore =
                    new TryableSemaphore(HystrixProperty.Factory.asProperty(1));

            Runnable r = new HystrixContextRunnable(new Runnable() {

                @Override
                public void run() {
                    try {
                        new TestSemaphoreCommand(circuitBreaker, semaphore, 200).queue().get();
                    } catch (Exception e) {
                        e.printStackTrace();
                        exceptionReceived.set(true);
                    }
                }

            });
            // 2 threads, the second should be rejected by the semaphore
            Thread t1 = new Thread(r);
            Thread t2 = new Thread(r);

            t1.start();
            t2.start();
            try {
                t1.join();
                t2.join();
            } catch (Exception e) {
                e.printStackTrace();
                fail("failed waiting on threads");
            }

            if (!exceptionReceived.get()) {
                fail("We expected an exception on the 2nd get");
            }

            assertEquals(2, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            // we don't have a fallback so threw an exception when rejected
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            // not a failure as the command never executed so can't fail
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            // no fallback failure as there isn't a fallback implemented
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            // we should have rejected via semaphore
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            // the rest should not be involved in this test
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(3, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        @Test
        public void testExecutionSemaphoreWithExecution() {
            final TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            // single thread should work
            try {
                TestSemaphoreCommand command = new TestSemaphoreCommand(circuitBreaker, 1, 200);
                boolean result = command.execute();
                assertFalse(command.isExecutedInThread());
                assertTrue(result);
            } catch (Exception e) {
                // we shouldn't fail on this one
                throw new RuntimeException(e);
            }

            final ArrayBlockingQueue<Boolean> results = new ArrayBlockingQueue<Boolean>(2);

            final AtomicBoolean exceptionReceived = new AtomicBoolean();

            final TryableSemaphore semaphore =
                    new TryableSemaphore(HystrixProperty.Factory.asProperty(1));

            Runnable r = new HystrixContextRunnable(new Runnable() {

                @Override
                public void run() {
                    try {
                        results.add(new TestSemaphoreCommand(circuitBreaker, semaphore, 200).execute());
                    } catch (Exception e) {
                        e.printStackTrace();
                        exceptionReceived.set(true);
                    }
                }

            });
            // 2 threads, the second should be rejected by the semaphore
            Thread t1 = new Thread(r);
            Thread t2 = new Thread(r);

            t1.start();
            t2.start();
            try {
                t1.join();
                t2.join();
            } catch (Exception e) {
                e.printStackTrace();
                fail("failed waiting on threads");
            }

            if (!exceptionReceived.get()) {
                fail("We expected an exception on the 2nd get");
            }

            // only 1 value is expected as the other should have thrown an exception
            assertEquals(1, results.size());
            // should contain only a true result
            assertTrue(results.contains(Boolean.TRUE));
            assertFalse(results.contains(Boolean.FALSE));

            assertEquals(2, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            // no failure ... we throw an exception because of rejection but the command does not fail execution
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            // there is no fallback implemented so no failure can occur on it
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            // we rejected via semaphore
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            // the rest should not be involved in this test
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(3, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        @Test
        public void testRejectedExecutionSemaphoreWithFallback() {
            final TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            final ArrayBlockingQueue<Boolean> results = new ArrayBlockingQueue<Boolean>(2);

            final AtomicBoolean exceptionReceived = new AtomicBoolean();

            Runnable r = new HystrixContextRunnable(new Runnable() {

                @Override
                public void run() {
                    try {
                        results.add(new TestSemaphoreCommandWithFallback(circuitBreaker, 1, 200, false).execute());
                    } catch (Exception e) {
                        e.printStackTrace();
                        exceptionReceived.set(true);
                    }
                }

            });

            // 2 threads, the second should be rejected by the semaphore and return fallback
            Thread t1 = new Thread(r);
            Thread t2 = new Thread(r);

            t1.start();
            t2.start();
            try {
                t1.join();
                t2.join();
            } catch (Exception e) {
                e.printStackTrace();
                fail("failed waiting on threads");
            }

            if (exceptionReceived.get()) {
                fail("We should have received a fallback response");
            }

            // both threads should have returned values
            assertEquals(2, results.size());
            // should contain both a true and false result
            assertTrue(results.contains(Boolean.TRUE));
            assertTrue(results.contains(Boolean.FALSE));

            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            // the rest should not be involved in this test
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            System.out.println("**** DONE");

            assertEquals(2, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Tests that semaphores are counted separately for commands with unique keys
         */
        @Test
        public void testSemaphorePermitsInUse() {
            final TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();

            // this semaphore will be shared across multiple command instances
            final TryableSemaphore sharedSemaphore =
                    new TryableSemaphore(HystrixProperty.Factory.asProperty(3));

            // used to wait until all commands have started
            final CountDownLatch startLatch = new CountDownLatch(sharedSemaphore.numberOfPermits.get() + 1);

            // used to signal that all command can finish
            final CountDownLatch sharedLatch = new CountDownLatch(1);

            final Runnable sharedSemaphoreRunnable = new HystrixContextRunnable(new Runnable() {
                public void run() {
                    try {
                        new LatchedSemaphoreCommand(circuitBreaker, sharedSemaphore, startLatch, sharedLatch).execute();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });

            // creates group of threads each using command sharing a single semaphore

            // I create extra threads and commands so that I can verify that some of them fail to obtain a semaphore
            final int sharedThreadCount = sharedSemaphore.numberOfPermits.get() * 2;
            final Thread[] sharedSemaphoreThreads = new Thread[sharedThreadCount];
            for (int i = 0; i < sharedThreadCount; i++) {
                sharedSemaphoreThreads[i] = new Thread(sharedSemaphoreRunnable);
            }

            // creates thread using isolated semaphore
            final TryableSemaphore isolatedSemaphore =
                    new TryableSemaphore(HystrixProperty.Factory.asProperty(1));

            final CountDownLatch isolatedLatch = new CountDownLatch(1);

            // tracks failures to obtain semaphores
            final AtomicInteger failureCount = new AtomicInteger();

            final Thread isolatedThread = new Thread(new HystrixContextRunnable(new Runnable() {
                public void run() {
                    try {
                        new LatchedSemaphoreCommand(circuitBreaker, isolatedSemaphore, startLatch, isolatedLatch).execute();
                    } catch (Exception e) {
                        e.printStackTrace();
                        failureCount.incrementAndGet();
                    }
                }
            }));

            // verifies no permits in use before starting threads
            assertEquals("wrong number of permits for shared semaphore", 0, sharedSemaphore.getNumberOfPermitsUsed());
            assertEquals("wrong number of permits for isolated semaphore", 0, isolatedSemaphore.getNumberOfPermitsUsed());

            for (int i = 0; i < sharedThreadCount; i++) {
                sharedSemaphoreThreads[i].start();
            }
            isolatedThread.start();

            // waits until all commands have started
            try {
                startLatch.await(1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            // verifies that all semaphores are in use
            assertEquals("wrong number of permits for shared semaphore",
                    sharedSemaphore.numberOfPermits.get().longValue(), sharedSemaphore.getNumberOfPermitsUsed());
            assertEquals("wrong number of permits for isolated semaphore",
                    isolatedSemaphore.numberOfPermits.get().longValue(), isolatedSemaphore.getNumberOfPermitsUsed());

            // signals commands to finish
            sharedLatch.countDown();
            isolatedLatch.countDown();

            try {
                for (int i = 0; i < sharedThreadCount; i++) {
                    sharedSemaphoreThreads[i].join();
                }
                isolatedThread.join();
            } catch (Exception e) {
                e.printStackTrace();
                fail("failed waiting on threads");
            }

            // verifies no permits in use after finishing threads
            assertEquals("wrong number of permits for shared semaphore", 0, sharedSemaphore.getNumberOfPermitsUsed());
            assertEquals("wrong number of permits for isolated semaphore", 0, isolatedSemaphore.getNumberOfPermitsUsed());

            // verifies that some executions failed
            final int expectedFailures = sharedSemaphore.getNumberOfPermitsUsed();
            assertEquals("failures expected but did not happen", expectedFailures, failureCount.get());
        }

        /**
         * Test that HystrixOwner can be passed in dynamically.
         */
        @Test
        public void testDynamicOwner() {
            try {
                TestHystrixCommand<Boolean> command = new DynamicOwnerTestCommand(CommandGroupForUnitTest.OWNER_ONE);
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
                assertEquals(true, command.execute());
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
                assertEquals(1, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            } catch (Exception e) {
                e.printStackTrace();
                fail("We received an exception.");
            }
        }

        /**
         * Test a successful command execution.
         */
        @Test
        public void testDynamicOwnerFails() {
            try {
                TestHystrixCommand<Boolean> command = new DynamicOwnerTestCommand(null);
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
                assertEquals(0, command.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
                assertEquals(true, command.execute());
                fail("we should have thrown an exception as we need an owner");
            } catch (Exception e) {
                // success if we get here
            }
        }

        /**
         * Test that HystrixCommandKey can be passed in dynamically.
         */
        @Test
        public void testDynamicKey() {
            try {
                DynamicOwnerAndKeyTestCommand command1 = new DynamicOwnerAndKeyTestCommand(CommandGroupForUnitTest.OWNER_ONE, CommandKeyForUnitTest.KEY_ONE);
                assertEquals(true, command1.execute());
                DynamicOwnerAndKeyTestCommand command2 = new DynamicOwnerAndKeyTestCommand(CommandGroupForUnitTest.OWNER_ONE, CommandKeyForUnitTest.KEY_TWO);
                assertEquals(true, command2.execute());

                // 2 different circuit breakers should be created
                assertNotSame(command1.getCircuitBreaker(), command2.getCircuitBreaker());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We received an exception.");
            }
        }

        /**
         * Test Request scoped caching of commands so that a 2nd duplicate call doesn't execute but returns the previous Future
         */
        @Test
        public void testRequestCache1() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SuccessfulCacheableCommand command1 = new SuccessfulCacheableCommand(circuitBreaker, true, "A");
            SuccessfulCacheableCommand command2 = new SuccessfulCacheableCommand(circuitBreaker, true, "A");

            assertTrue(command1.isCommandRunningInThread());

            Future<String> f1 = command1.queue();
            Future<String> f2 = command2.queue();

            try {
                assertEquals("A", f1.get());
                assertEquals("A", f2.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertTrue(command1.executed);
            // the second one should not have executed as it should have received the cached value instead
            assertFalse(command2.executed);

            // the execution log for command1 should show a SUCCESS
            assertEquals(1, command1.getExecutionEvents().size());
            assertTrue(command1.getExecutionEvents().contains(HystrixEventType.SUCCESS));
            assertTrue(command1.getExecutionTimeInMilliseconds() > -1);
            assertFalse(command1.isResponseFromCache());

            // the execution log for command2 should show it came from cache
            assertEquals(2, command2.getExecutionEvents().size()); // it will include the SUCCESS + RESPONSE_FROM_CACHE
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.SUCCESS));
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.RESPONSE_FROM_CACHE));
            assertTrue(command2.getExecutionTimeInMilliseconds() == -1);
            assertTrue(command2.isResponseFromCache());

            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(2, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test Request scoped caching doesn't prevent different ones from executing
         */
        @Test
        public void testRequestCache2() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SuccessfulCacheableCommand command1 = new SuccessfulCacheableCommand(circuitBreaker, true, "A");
            SuccessfulCacheableCommand command2 = new SuccessfulCacheableCommand(circuitBreaker, true, "B");

            assertTrue(command1.isCommandRunningInThread());

            Future<String> f1 = command1.queue();
            Future<String> f2 = command2.queue();

            try {
                assertEquals("A", f1.get());
                assertEquals("B", f2.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertTrue(command1.executed);
            // both should execute as they are different
            assertTrue(command2.executed);

            // the execution log for command1 should show a SUCCESS
            assertEquals(1, command1.getExecutionEvents().size());
            assertTrue(command1.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command2 should show a SUCCESS
            assertEquals(1, command2.getExecutionEvents().size());
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.SUCCESS));
            assertTrue(command2.getExecutionTimeInMilliseconds() > -1);
            assertFalse(command2.isResponseFromCache());

            assertEquals(2, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(2, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test Request scoped caching with a mixture of commands
         */
        @Test
        public void testRequestCache3() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SuccessfulCacheableCommand command1 = new SuccessfulCacheableCommand(circuitBreaker, true, "A");
            SuccessfulCacheableCommand command2 = new SuccessfulCacheableCommand(circuitBreaker, true, "B");
            SuccessfulCacheableCommand command3 = new SuccessfulCacheableCommand(circuitBreaker, true, "A");

            assertTrue(command1.isCommandRunningInThread());

            Future<String> f1 = command1.queue();
            Future<String> f2 = command2.queue();
            Future<String> f3 = command3.queue();

            try {
                assertEquals("A", f1.get());
                assertEquals("B", f2.get());
                assertEquals("A", f3.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertTrue(command1.executed);
            // both should execute as they are different
            assertTrue(command2.executed);
            // but the 3rd should come from cache
            assertFalse(command3.executed);

            // the execution log for command1 should show a SUCCESS
            assertEquals(1, command1.getExecutionEvents().size());
            assertTrue(command1.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command2 should show a SUCCESS
            assertEquals(1, command2.getExecutionEvents().size());
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command3 should show it came from cache
            assertEquals(2, command3.getExecutionEvents().size()); // it will include the SUCCESS + RESPONSE_FROM_CACHE
            assertTrue(command3.getExecutionEvents().contains(HystrixEventType.RESPONSE_FROM_CACHE));
            assertTrue(command3.getExecutionTimeInMilliseconds() == -1);
            assertTrue(command3.isResponseFromCache());

            assertEquals(2, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(3, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test Request scoped caching of commands so that a 2nd duplicate call doesn't execute but returns the previous Future
         */
        @Test
        public void testRequestCacheWithSlowExecution() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SlowCacheableCommand command1 = new SlowCacheableCommand(circuitBreaker, "A", 200);
            SlowCacheableCommand command2 = new SlowCacheableCommand(circuitBreaker, "A", 100);
            SlowCacheableCommand command3 = new SlowCacheableCommand(circuitBreaker, "A", 100);
            SlowCacheableCommand command4 = new SlowCacheableCommand(circuitBreaker, "A", 100);

            Future<String> f1 = command1.queue();
            Future<String> f2 = command2.queue();
            Future<String> f3 = command3.queue();
            Future<String> f4 = command4.queue();

            try {
                assertEquals("A", f2.get());
                assertEquals("A", f3.get());
                assertEquals("A", f4.get());

                assertEquals("A", f1.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertTrue(command1.executed);
            // the second one should not have executed as it should have received the cached value instead
            assertFalse(command2.executed);
            assertFalse(command3.executed);
            assertFalse(command4.executed);

            // the execution log for command1 should show a SUCCESS
            assertEquals(1, command1.getExecutionEvents().size());
            assertTrue(command1.getExecutionEvents().contains(HystrixEventType.SUCCESS));
            assertTrue(command1.getExecutionTimeInMilliseconds() > -1);
            assertFalse(command1.isResponseFromCache());

            // the execution log for command2 should show it came from cache
            assertEquals(2, command2.getExecutionEvents().size()); // it will include the SUCCESS + RESPONSE_FROM_CACHE
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.SUCCESS));
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.RESPONSE_FROM_CACHE));
            assertTrue(command2.getExecutionTimeInMilliseconds() == -1);
            assertTrue(command2.isResponseFromCache());

            assertTrue(command3.isResponseFromCache());
            assertTrue(command3.getExecutionTimeInMilliseconds() == -1);
            assertTrue(command4.isResponseFromCache());
            assertTrue(command4.getExecutionTimeInMilliseconds() == -1);

            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(4, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());

            System.out.println("HystrixRequestLog: " + HystrixRequestLog.getCurrentRequest().getExecutedCommandsAsString());
        }

        /**
         * Test Request scoped caching with a mixture of commands
         */
        @Test
        public void testNoRequestCache3() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SuccessfulCacheableCommand command1 = new SuccessfulCacheableCommand(circuitBreaker, false, "A");
            SuccessfulCacheableCommand command2 = new SuccessfulCacheableCommand(circuitBreaker, false, "B");
            SuccessfulCacheableCommand command3 = new SuccessfulCacheableCommand(circuitBreaker, false, "A");

            assertTrue(command1.isCommandRunningInThread());

            Future<String> f1 = command1.queue();
            Future<String> f2 = command2.queue();
            Future<String> f3 = command3.queue();

            try {
                assertEquals("A", f1.get());
                assertEquals("B", f2.get());
                assertEquals("A", f3.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertTrue(command1.executed);
            // both should execute as they are different
            assertTrue(command2.executed);
            // this should also execute since we disabled the cache
            assertTrue(command3.executed);

            // the execution log for command1 should show a SUCCESS
            assertEquals(1, command1.getExecutionEvents().size());
            assertTrue(command1.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command2 should show a SUCCESS
            assertEquals(1, command2.getExecutionEvents().size());
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command3 should show a SUCCESS
            assertEquals(1, command3.getExecutionEvents().size());
            assertTrue(command3.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(3, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test Request scoped caching with a mixture of commands
         */
        @Test
        public void testRequestCacheViaQueueSemaphore1() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SuccessfulCacheableCommandViaSemaphore command1 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, true, "A");
            SuccessfulCacheableCommandViaSemaphore command2 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, true, "B");
            SuccessfulCacheableCommandViaSemaphore command3 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, true, "A");

            assertFalse(command1.isCommandRunningInThread());

            Future<String> f1 = command1.queue();
            Future<String> f2 = command2.queue();
            Future<String> f3 = command3.queue();

            try {
                assertEquals("A", f1.get());
                assertEquals("B", f2.get());
                assertEquals("A", f3.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertTrue(command1.executed);
            // both should execute as they are different
            assertTrue(command2.executed);
            // but the 3rd should come from cache
            assertFalse(command3.executed);

            // the execution log for command1 should show a SUCCESS
            assertEquals(1, command1.getExecutionEvents().size());
            assertTrue(command1.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command2 should show a SUCCESS
            assertEquals(1, command2.getExecutionEvents().size());
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command3 should show it comes from cache
            assertEquals(2, command3.getExecutionEvents().size()); // it will include the SUCCESS + RESPONSE_FROM_CACHE
            assertTrue(command3.getExecutionEvents().contains(HystrixEventType.SUCCESS));
            assertTrue(command3.getExecutionEvents().contains(HystrixEventType.RESPONSE_FROM_CACHE));

            assertTrue(command3.isResponseFromCache());
            assertTrue(command3.getExecutionTimeInMilliseconds() == -1);

            assertEquals(2, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(3, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test Request scoped caching with a mixture of commands
         */
        @Test
        public void testNoRequestCacheViaQueueSemaphore1() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SuccessfulCacheableCommandViaSemaphore command1 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, false, "A");
            SuccessfulCacheableCommandViaSemaphore command2 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, false, "B");
            SuccessfulCacheableCommandViaSemaphore command3 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, false, "A");

            assertFalse(command1.isCommandRunningInThread());

            Future<String> f1 = command1.queue();
            Future<String> f2 = command2.queue();
            Future<String> f3 = command3.queue();

            try {
                assertEquals("A", f1.get());
                assertEquals("B", f2.get());
                assertEquals("A", f3.get());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertTrue(command1.executed);
            // both should execute as they are different
            assertTrue(command2.executed);
            // this should also execute because caching is disabled
            assertTrue(command3.executed);

            // the execution log for command1 should show a SUCCESS
            assertEquals(1, command1.getExecutionEvents().size());
            assertTrue(command1.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command2 should show a SUCCESS
            assertEquals(1, command2.getExecutionEvents().size());
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command3 should show a SUCCESS
            assertEquals(1, command3.getExecutionEvents().size());
            assertTrue(command3.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(3, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test Request scoped caching with a mixture of commands
         */
        @Test
        public void testRequestCacheViaExecuteSemaphore1() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SuccessfulCacheableCommandViaSemaphore command1 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, true, "A");
            SuccessfulCacheableCommandViaSemaphore command2 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, true, "B");
            SuccessfulCacheableCommandViaSemaphore command3 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, true, "A");

            assertFalse(command1.isCommandRunningInThread());

            String f1 = command1.execute();
            String f2 = command2.execute();
            String f3 = command3.execute();

            assertEquals("A", f1);
            assertEquals("B", f2);
            assertEquals("A", f3);

            assertTrue(command1.executed);
            // both should execute as they are different
            assertTrue(command2.executed);
            // but the 3rd should come from cache
            assertFalse(command3.executed);

            // the execution log for command1 should show a SUCCESS
            assertEquals(1, command1.getExecutionEvents().size());
            assertTrue(command1.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command2 should show a SUCCESS
            assertEquals(1, command2.getExecutionEvents().size());
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command3 should show it comes from cache
            assertEquals(2, command3.getExecutionEvents().size()); // it will include the SUCCESS + RESPONSE_FROM_CACHE
            assertTrue(command3.getExecutionEvents().contains(HystrixEventType.RESPONSE_FROM_CACHE));

            assertEquals(2, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(3, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test Request scoped caching with a mixture of commands
         */
        @Test
        public void testNoRequestCacheViaExecuteSemaphore1() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SuccessfulCacheableCommandViaSemaphore command1 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, false, "A");
            SuccessfulCacheableCommandViaSemaphore command2 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, false, "B");
            SuccessfulCacheableCommandViaSemaphore command3 = new SuccessfulCacheableCommandViaSemaphore(circuitBreaker, false, "A");

            assertFalse(command1.isCommandRunningInThread());

            String f1 = command1.execute();
            String f2 = command2.execute();
            String f3 = command3.execute();

            assertEquals("A", f1);
            assertEquals("B", f2);
            assertEquals("A", f3);

            assertTrue(command1.executed);
            // both should execute as they are different
            assertTrue(command2.executed);
            // this should also execute because caching is disabled
            assertTrue(command3.executed);

            // the execution log for command1 should show a SUCCESS
            assertEquals(1, command1.getExecutionEvents().size());
            assertTrue(command1.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command2 should show a SUCCESS
            assertEquals(1, command2.getExecutionEvents().size());
            assertTrue(command2.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            // the execution log for command3 should show a SUCCESS
            assertEquals(1, command3.getExecutionEvents().size());
            assertTrue(command3.getExecutionEvents().contains(HystrixEventType.SUCCESS));

            assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(0, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(3, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        @Test
        public void testNoRequestCacheOnTimeoutThrowsException() throws Exception {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            NoRequestCacheTimeoutWithoutFallback r1 = new NoRequestCacheTimeoutWithoutFallback(circuitBreaker);
            try {
                System.out.println("r1 value: " + r1.execute());
                // we should have thrown an exception
                fail("expected a timeout");
            } catch (HystrixRuntimeException e) {
                assertTrue(r1.isResponseTimedOut());
                // what we want
            }

            NoRequestCacheTimeoutWithoutFallback r2 = new NoRequestCacheTimeoutWithoutFallback(circuitBreaker);
            try {
                r2.execute();
                // we should have thrown an exception
                fail("expected a timeout");
            } catch (HystrixRuntimeException e) {
                assertTrue(r2.isResponseTimedOut());
                // what we want
            }

            NoRequestCacheTimeoutWithoutFallback r3 = new NoRequestCacheTimeoutWithoutFallback(circuitBreaker);
            Future<Boolean> f3 = r3.queue();
            try {
                f3.get();
                // we should have thrown an exception
                fail("expected a timeout");
            } catch (ExecutionException e) {
                e.printStackTrace();
                assertTrue(r3.isResponseTimedOut());
                // what we want
            }

            Thread.sleep(500); // timeout on command is set to 200ms

            NoRequestCacheTimeoutWithoutFallback r4 = new NoRequestCacheTimeoutWithoutFallback(circuitBreaker);
            try {
                r4.execute();
                // we should have thrown an exception
                fail("expected a timeout");
            } catch (HystrixRuntimeException e) {
                assertTrue(r4.isResponseTimedOut());
                assertFalse(r4.isResponseFromFallback());
                // what we want
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(4, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(4, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(4, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        @Test
        public void testRequestCacheOnTimeoutCausesNullPointerException() throws Exception {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            // Expect it to time out - all results should be false
            assertFalse(new RequestCacheNullPointerExceptionCase(circuitBreaker).execute());
            assertFalse(new RequestCacheNullPointerExceptionCase(circuitBreaker).execute()); // return from cache #1
            assertFalse(new RequestCacheNullPointerExceptionCase(circuitBreaker).execute()); // return from cache #2
            Thread.sleep(500); // timeout on command is set to 200ms
            Boolean value = new RequestCacheNullPointerExceptionCase(circuitBreaker).execute(); // return from cache #3
            assertFalse(value);
            RequestCacheNullPointerExceptionCase c = new RequestCacheNullPointerExceptionCase(circuitBreaker);
            Future<Boolean> f = c.queue(); // return from cache #4
            // the bug is that we're getting a null Future back, rather than a Future that returns false
            assertNotNull(f);
            assertFalse(f.get());

            assertTrue(c.isResponseFromFallback());
            assertTrue(c.isResponseTimedOut());
            assertFalse(c.isFailedExecution());
            assertFalse(c.isResponseShortCircuited());

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(4, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(5, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());

            HystrixCommand<?>[] executeCommands = HystrixRequestLog.getCurrentRequest().getExecutedCommands().toArray(new HystrixCommand<?>[] {});

            System.out.println(":executeCommands[0].getExecutionEvents()" + executeCommands[0].getExecutionEvents());
            assertEquals(2, executeCommands[0].getExecutionEvents().size());
            assertTrue(executeCommands[0].getExecutionEvents().contains(HystrixEventType.FALLBACK_SUCCESS));
            assertTrue(executeCommands[0].getExecutionEvents().contains(HystrixEventType.TIMEOUT));
            assertTrue(executeCommands[0].getExecutionTimeInMilliseconds() > -1);
            assertTrue(executeCommands[0].isResponseTimedOut());
            assertTrue(executeCommands[0].isResponseFromFallback());
            assertFalse(executeCommands[0].isResponseFromCache());

            assertEquals(3, executeCommands[1].getExecutionEvents().size()); // it will include FALLBACK_SUCCESS/TIMEOUT + RESPONSE_FROM_CACHE
            assertTrue(executeCommands[1].getExecutionEvents().contains(HystrixEventType.RESPONSE_FROM_CACHE));
            assertTrue(executeCommands[1].getExecutionTimeInMilliseconds() == -1);
            assertTrue(executeCommands[1].isResponseFromCache());
            assertTrue(executeCommands[1].isResponseTimedOut());
            assertTrue(executeCommands[1].isResponseFromFallback());
        }

        @Test
        public void testRequestCacheOnTimeoutThrowsException() throws Exception {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            RequestCacheTimeoutWithoutFallback r1 = new RequestCacheTimeoutWithoutFallback(circuitBreaker);
            try {
                System.out.println("r1 value: " + r1.execute());
                // we should have thrown an exception
                fail("expected a timeout");
            } catch (HystrixRuntimeException e) {
                assertTrue(r1.isResponseTimedOut());
                // what we want
            }

            RequestCacheTimeoutWithoutFallback r2 = new RequestCacheTimeoutWithoutFallback(circuitBreaker);
            try {
                r2.execute();
                // we should have thrown an exception
                fail("expected a timeout");
            } catch (HystrixRuntimeException e) {
                assertTrue(r2.isResponseTimedOut());
                // what we want
            }

            RequestCacheTimeoutWithoutFallback r3 = new RequestCacheTimeoutWithoutFallback(circuitBreaker);
            Future<Boolean> f3 = r3.queue();
            try {
                f3.get();
                // we should have thrown an exception
                fail("expected a timeout");
            } catch (ExecutionException e) {
                e.printStackTrace();
                assertTrue(r3.isResponseTimedOut());
                // what we want
            }

            Thread.sleep(500); // timeout on command is set to 200ms

            RequestCacheTimeoutWithoutFallback r4 = new RequestCacheTimeoutWithoutFallback(circuitBreaker);
            try {
                r4.execute();
                // we should have thrown an exception
                fail("expected a timeout");
            } catch (HystrixRuntimeException e) {
                assertTrue(r4.isResponseTimedOut());
                assertFalse(r4.isResponseFromFallback());
                // what we want
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(4, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        @Test
        public void testRequestCacheOnThreadRejectionThrowsException() throws Exception {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            CountDownLatch completionLatch = new CountDownLatch(1);
            RequestCacheThreadRejectionWithoutFallback r1 = new RequestCacheThreadRejectionWithoutFallback(circuitBreaker, completionLatch);
            try {
                System.out.println("r1: " + r1.execute());
                // we should have thrown an exception
                fail("expected a rejection");
            } catch (HystrixRuntimeException e) {
                assertTrue(r1.isResponseRejected());
                // what we want
            }

            RequestCacheThreadRejectionWithoutFallback r2 = new RequestCacheThreadRejectionWithoutFallback(circuitBreaker, completionLatch);
            try {
                System.out.println("r2: " + r2.execute());
                // we should have thrown an exception
                fail("expected a rejection");
            } catch (HystrixRuntimeException e) {
                //                e.printStackTrace();
                assertTrue(r2.isResponseRejected());
                // what we want
            }

            RequestCacheThreadRejectionWithoutFallback r3 = new RequestCacheThreadRejectionWithoutFallback(circuitBreaker, completionLatch);
            try {
                System.out.println("f3: " + r3.queue().get());
                // we should have thrown an exception
                fail("expected a rejection");
            } catch (HystrixRuntimeException e) {
                //                e.printStackTrace();
                assertTrue(r3.isResponseRejected());
                // what we want
            }

            // let the command finish (only 1 should actually be blocked on this due to the response cache)
            completionLatch.countDown();

            // then another after the command has completed
            RequestCacheThreadRejectionWithoutFallback r4 = new RequestCacheThreadRejectionWithoutFallback(circuitBreaker, completionLatch);
            try {
                System.out.println("r4: " + r4.execute());
                // we should have thrown an exception
                fail("expected a rejection");
            } catch (HystrixRuntimeException e) {
                //                e.printStackTrace();
                assertTrue(r4.isResponseRejected());
                assertFalse(r4.isResponseFromFallback());
                // what we want
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(3, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, circuitBreaker.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(4, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        /**
         * Test that we can do basic execution without a RequestVariable being initialized.
         */
        @Test
        public void testBasicExecutionWorksWithoutRequestVariable() {
            try {
                /* force the RequestVariable to not be initialized */
                HystrixRequestContext.setContextOnCurrentThread(null);

                TestHystrixCommand<Boolean> command = new SuccessfulTestCommand();
                assertEquals(true, command.execute());

                TestHystrixCommand<Boolean> command2 = new SuccessfulTestCommand();
                assertEquals(true, command2.queue().get());

                // we should be able to execute without a RequestVariable if ...
                // 1) We don't have a cacheKey
                // 2) We don't ask for the RequestLog
                // 3) We don't do collapsing

            } catch (Exception e) {
                e.printStackTrace();
                fail("We received an exception => " + e.getMessage());
            }
        }

        /**
         * Test that if we try and execute a command with a cacheKey without initializing RequestVariable that it gives an error.
         */
        @Test
        public void testCacheKeyExecutionRequiresRequestVariable() {
            try {
                /* force the RequestVariable to not be initialized */
                HystrixRequestContext.setContextOnCurrentThread(null);

                TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();

                SuccessfulCacheableCommand command = new SuccessfulCacheableCommand(circuitBreaker, true, "one");
                assertEquals(true, command.execute());

                SuccessfulCacheableCommand command2 = new SuccessfulCacheableCommand(circuitBreaker, true, "two");
                assertEquals(true, command2.queue().get());

                fail("We expect an exception because cacheKey requires RequestVariable.");

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        /**
         * Test that a BadRequestException can be thrown and not count towards errors and bypasses fallback.
         */
        @Test
        public void testBadRequestExceptionViaExecuteInThread() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            try {
                new BadRequestCommand(circuitBreaker, ExecutionIsolationStrategy.THREAD).execute();
                fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
            } catch (HystrixBadRequestException e) {
                // success
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
                fail("We expect a " + HystrixBadRequestException.class.getSimpleName() + " but got a " + e.getClass().getSimpleName());
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
        }

        /**
         * Test that a BadRequestException can be thrown and not count towards errors and bypasses fallback.
         */
        @Test
        public void testBadRequestExceptionViaQueueInThread() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            try {
                new BadRequestCommand(circuitBreaker, ExecutionIsolationStrategy.THREAD).queue().get();
                fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
            } catch (ExecutionException e) {
                e.printStackTrace();
                if (e.getCause() instanceof HystrixBadRequestException) {
                    // success    
                } else {
                    fail("We expect a " + HystrixBadRequestException.class.getSimpleName() + " but got a " + e.getClass().getSimpleName());
                }
            } catch (Exception e) {
                e.printStackTrace();
                fail();
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
        }
        
        /**
         * Test that BadRequestException behavior works the same on a cached response.
         */
        @Test
        public void testBadRequestExceptionViaQueueInThreadOnResponseFromCache() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();

            // execute once to cache the value
            try {
                new BadRequestCommand(circuitBreaker, ExecutionIsolationStrategy.THREAD).execute();
            } catch (Throwable e) {
                // ignore
            }

            try {
                new BadRequestCommand(circuitBreaker, ExecutionIsolationStrategy.THREAD).queue().get();
                fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
            } catch (ExecutionException e) {
                e.printStackTrace();
                if (e.getCause() instanceof HystrixBadRequestException) {
                    // success    
                } else {
                    fail("We expect a " + HystrixBadRequestException.class.getSimpleName() + " but got a " + e.getClass().getSimpleName());
                }
            } catch (Exception e) {
                e.printStackTrace();
                fail();
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
        }

        /**
         * Test that a BadRequestException can be thrown and not count towards errors and bypasses fallback.
         */
        @Test
        public void testBadRequestExceptionViaExecuteInSemaphore() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            try {
                new BadRequestCommand(circuitBreaker, ExecutionIsolationStrategy.SEMAPHORE).execute();
                fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
            } catch (HystrixBadRequestException e) {
                // success
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
                fail("We expect a " + HystrixBadRequestException.class.getSimpleName() + " but got a " + e.getClass().getSimpleName());
            }

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
        }

        /**
         * Test that a BadRequestException can be thrown and not count towards errors and bypasses fallback.
         */
        @Test
        public void testBadRequestExceptionViaQueueInSemaphore() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            try {
                new BadRequestCommand(circuitBreaker, ExecutionIsolationStrategy.SEMAPHORE).queue().get();
                fail("we expect to receive a " + HystrixBadRequestException.class.getSimpleName());
            } catch (ExecutionException e) {
                e.printStackTrace();
                if (e.getCause() instanceof HystrixBadRequestException) {
                    // success    
                } else {
                    fail("We expect a " + HystrixBadRequestException.class.getSimpleName() + " but got a " + e.getClass().getSimpleName());
                }
            } catch (Exception e) {
                e.printStackTrace();
                fail();
            }
            
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
        }

        /**
         * Test a checked Exception being thrown
         */
        @Test
        public void testCheckedExceptionViaExecute() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            CommandWithCheckedException command = new CommandWithCheckedException(circuitBreaker);
            try {
                command.execute();
                fail("we expect to receive a " + Exception.class.getSimpleName());
            } catch (Exception e) {
                assertEquals("simulated checked exception message", e.getCause().getMessage());
            }

            assertEquals("simulated checked exception message", command.getFailedExecutionException().getMessage());

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
        }

        /**
         * Test a java.lang.Error being thrown
         * 
         * @throws InterruptedException
         */
        @Test
        public void testCheckedExceptionViaObserve() throws InterruptedException {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            CommandWithCheckedException command = new CommandWithCheckedException(circuitBreaker);
            final AtomicReference<Throwable> t = new AtomicReference<Throwable>();
            final CountDownLatch latch = new CountDownLatch(1);
            try {
                command.observe().subscribe(new Observer<Boolean>() {

                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable e) {
                        t.set(e);
                        latch.countDown();
                    }

                    @Override
                    public void onNext(Boolean args) {

                    }

                });
            } catch (Exception e) {
                e.printStackTrace();
                fail("we should not get anything thrown, it should be emitted via the Observer#onError method");
            }

            latch.await(1, TimeUnit.SECONDS);
            assertNotNull(t.get());
            t.get().printStackTrace();

            assertTrue(t.get() instanceof HystrixRuntimeException);
            assertEquals("simulated checked exception message", t.get().getCause().getMessage());
            assertEquals("simulated checked exception message", command.getFailedExecutionException().getMessage());

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
        }

        /**
         * Test a java.lang.Error being thrown
         */
        @Test
        public void testErrorThrownViaExecute() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            CommandWithErrorThrown command = new CommandWithErrorThrown(circuitBreaker);
            try {
                command.execute();
                fail("we expect to receive a " + Error.class.getSimpleName());
            } catch (Exception e) {
                // the actual error is an extra cause level deep because Hystrix needs to wrap Throwable/Error as it's public
                // methods only support Exception and it's not a strong enough reason to break backwards compatibility and jump to version 2.x
                // so HystrixRuntimeException -> wrapper Exception -> actual Error
                assertEquals("simulated java.lang.Error message", e.getCause().getCause().getMessage());
            }

            assertEquals("simulated java.lang.Error message", command.getFailedExecutionException().getCause().getMessage());

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
        }

        /**
         * Test a java.lang.Error being thrown
         */
        @Test
        public void testErrorThrownViaQueue() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            CommandWithErrorThrown command = new CommandWithErrorThrown(circuitBreaker);
            try {
                command.queue().get();
                fail("we expect to receive an Exception");
            } catch (Exception e) {
                // one cause down from ExecutionException to HystrixRuntime
                // then the actual error is an extra cause level deep because Hystrix needs to wrap Throwable/Error as it's public
                // methods only support Exception and it's not a strong enough reason to break backwards compatibility and jump to version 2.x
                // so ExecutionException -> HystrixRuntimeException -> wrapper Exception -> actual Error
                assertEquals("simulated java.lang.Error message", e.getCause().getCause().getCause().getMessage());
            }

            assertEquals("simulated java.lang.Error message", command.getFailedExecutionException().getCause().getMessage());

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
        }

        /**
         * Test a java.lang.Error being thrown
         * 
         * @throws InterruptedException
         */
        @Test
        public void testErrorThrownViaObserve() throws InterruptedException {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            CommandWithErrorThrown command = new CommandWithErrorThrown(circuitBreaker);
            final AtomicReference<Throwable> t = new AtomicReference<Throwable>();
            final CountDownLatch latch = new CountDownLatch(1);
            try {
                command.observe().subscribe(new Observer<Boolean>() {

                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable e) {
                        t.set(e);
                        latch.countDown();
                    }

                    @Override
                    public void onNext(Boolean args) {

                    }

                });
            } catch (Exception e) {
                e.printStackTrace();
                fail("we should not get anything thrown, it should be emitted via the Observer#onError method");
            }

            latch.await(1, TimeUnit.SECONDS);
            assertNotNull(t.get());
            t.get().printStackTrace();

            assertTrue(t.get() instanceof HystrixRuntimeException);
            // the actual error is an extra cause level deep because Hystrix needs to wrap Throwable/Error as it's public
            // methods only support Exception and it's not a strong enough reason to break backwards compatibility and jump to version 2.x
            assertEquals("simulated java.lang.Error message", t.get().getCause().getCause().getMessage());
            assertEquals("simulated java.lang.Error message", command.getFailedExecutionException().getCause().getMessage());

            assertTrue(command.getExecutionTimeInMilliseconds() > -1);
            assertTrue(command.isFailedExecution());

            assertEquals(0, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(1, circuitBreaker.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
        }

        /**
         * Execution hook on successful execution
         */
        @Test
        public void testExecutionHookSuccessfulCommand() {
            /* test with execute() */
            TestHystrixCommand<Boolean> command = new SuccessfulTestCommand();
            command.execute();

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we expect a successful response from run()
            assertNotNull(command.builder.executionHook.runSuccessResponse);
            // we do not expect an exception
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should not be run as we were successful
            assertEquals(0, command.builder.executionHook.startFallback.get());
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackFailureException);

            // the execute() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should have a response from execute() since run() succeeded
            assertNotNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should not have an exception since run() succeeded
            assertNull(command.builder.executionHook.endExecuteFailureException);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());
            assertEquals(1, command.builder.executionHook.threadComplete.get());

            /* test with queue() */
            command = new SuccessfulTestCommand();
            try {
                command.queue().get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we expect a successful response from run()
            assertNotNull(command.builder.executionHook.runSuccessResponse);
            // we do not expect an exception
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should not be run as we were successful
            assertEquals(0, command.builder.executionHook.startFallback.get());
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackFailureException);

            // the queue() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should have a response from queue() since run() succeeded
            assertNotNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should not have an exception since run() succeeded
            assertNull(command.builder.executionHook.endExecuteFailureException);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());
            assertEquals(1, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on successful execution with "fire and forget" approach
         */
        @Test
        public void testExecutionHookSuccessfulCommandViaFireAndForget() {
            TestHystrixCommand<Boolean> command = new SuccessfulTestCommand();
            try {
                // do not block on "get()" ... fire this asynchronously
                command.queue();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // wait for command to execute without calling get on the future
            while (!command.isExecutionComplete()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException("interrupted");
                }
            }

            /*
             * All the hooks should still work even though we didn't call get() on the future
             */

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we expect a successful response from run()
            assertNotNull(command.builder.executionHook.runSuccessResponse);
            // we do not expect an exception
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should not be run as we were successful
            assertEquals(0, command.builder.executionHook.startFallback.get());
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackFailureException);

            // the queue() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should have a response from queue() since run() succeeded
            assertNotNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should not have an exception since run() succeeded
            assertNull(command.builder.executionHook.endExecuteFailureException);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());
            assertEquals(1, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on successful execution with multiple get() calls to Future
         */
        @Test
        public void testExecutionHookSuccessfulCommandWithMultipleGetsOnFuture() {
            TestHystrixCommand<Boolean> command = new SuccessfulTestCommand();
            try {
                Future<Boolean> f = command.queue();
                f.get();
                f.get();
                f.get();
                f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            /*
             * Despite multiple calls to get() we should only have 1 call to the hooks.
             */

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we expect a successful response from run()
            assertNotNull(command.builder.executionHook.runSuccessResponse);
            // we do not expect an exception
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should not be run as we were successful
            assertEquals(0, command.builder.executionHook.startFallback.get());
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackFailureException);

            // the queue() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should have a response from queue() since run() succeeded
            assertNotNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should not have an exception since run() succeeded
            assertNull(command.builder.executionHook.endExecuteFailureException);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());
            assertEquals(1, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on failed execution without a fallback
         */
        @Test
        public void testExecutionHookRunFailureWithoutFallback() {
            /* test with execute() */
            TestHystrixCommand<Boolean> command = new UnknownFailureTestCommandWithoutFallback();
            try {
                command.execute();
                fail("Expecting exception");
            } catch (Exception e) {
                // ignore
            }

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we should not have a response
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should have an exception
            assertNotNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run since run() failed
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // no response since fallback is not implemented
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // not null since it's not implemented and throws an exception
            assertNotNull(command.builder.executionHook.fallbackFailureException);

            // the execute() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should not have a response from execute() since we do not have a fallback and run() failed
            assertNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should have an exception since run() failed
            assertNotNull(command.builder.executionHook.endExecuteFailureException);
            // run() failure
            assertEquals(FailureType.COMMAND_EXCEPTION, command.builder.executionHook.endExecuteFailureType);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());
            assertEquals(1, command.builder.executionHook.threadComplete.get());

            /* test with queue() */
            command = new UnknownFailureTestCommandWithoutFallback();
            try {
                command.queue().get();
                fail("Expecting exception");
            } catch (Exception e) {
                // ignore
            }

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we should not have a response
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should have an exception
            assertNotNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run since run() failed
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // no response since fallback is not implemented
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // not null since it's not implemented and throws an exception
            assertNotNull(command.builder.executionHook.fallbackFailureException);

            // the queue() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should not have a response from queue() since we do not have a fallback and run() failed
            assertNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should have an exception since run() failed
            assertNotNull(command.builder.executionHook.endExecuteFailureException);
            // run() failure
            assertEquals(FailureType.COMMAND_EXCEPTION, command.builder.executionHook.endExecuteFailureType);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());
            assertEquals(1, command.builder.executionHook.threadComplete.get());

        }

        /**
         * Execution hook on failed execution with a fallback
         */
        @Test
        public void testExecutionHookRunFailureWithFallback() {
            /* test with execute() */
            TestHystrixCommand<Boolean> command = new KnownFailureTestCommandWithFallback(new TestCircuitBreaker());
            command.execute();

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we should not have a response from run since run() failed
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should have an exception since run() failed
            assertNotNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run since run() failed
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // a response since fallback is implemented
            assertNotNull(command.builder.executionHook.fallbackSuccessResponse);
            // null since it's implemented and succeeds
            assertNull(command.builder.executionHook.fallbackFailureException);

            // the execute() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should have a response from execute() since we expect a fallback despite failure of run()
            assertNotNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should not have an exception because we expect a fallback
            assertNull(command.builder.executionHook.endExecuteFailureException);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());
            assertEquals(1, command.builder.executionHook.threadComplete.get());

            /* test with queue() */
            command = new KnownFailureTestCommandWithFallback(new TestCircuitBreaker());
            try {
                command.queue().get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we should not have a response from run since run() failed
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should have an exception since run() failed
            assertNotNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run since run() failed
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // a response since fallback is implemented
            assertNotNull(command.builder.executionHook.fallbackSuccessResponse);
            // null since it's implemented and succeeds
            assertNull(command.builder.executionHook.fallbackFailureException);

            // the queue() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should have a response from queue() since we expect a fallback despite failure of run()
            assertNotNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should not have an exception because we expect a fallback
            assertNull(command.builder.executionHook.endExecuteFailureException);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());
            assertEquals(1, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on failed execution with a fallback failure
         */
        @Test
        public void testExecutionHookRunFailureWithFallbackFailure() {
            /* test with execute() */
            TestHystrixCommand<Boolean> command = new KnownFailureTestCommandWithFallbackFailure();
            try {
                command.execute();
                fail("Expecting exception");
            } catch (Exception e) {
                // ignore
            }

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we should not have a response because run() and fallback fail
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should have an exception because run() and fallback fail
            assertNotNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run since run() failed
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // no response since fallback fails
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // not null since it's implemented but fails
            assertNotNull(command.builder.executionHook.fallbackFailureException);

            // the execute() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should not have a response because run() and fallback fail
            assertNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should have an exception because run() and fallback fail
            assertNotNull(command.builder.executionHook.endExecuteFailureException);
            // run() failure
            assertEquals(FailureType.COMMAND_EXCEPTION, command.builder.executionHook.endExecuteFailureType);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());
            assertEquals(1, command.builder.executionHook.threadComplete.get());

            /* test with queue() */
            command = new KnownFailureTestCommandWithFallbackFailure();
            try {
                command.queue().get();
                fail("Expecting exception");
            } catch (Exception e) {
                // ignore
            }

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we should not have a response because run() and fallback fail
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should have an exception because run() and fallback fail
            assertNotNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run since run() failed
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // no response since fallback fails
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // not null since it's implemented but fails
            assertNotNull(command.builder.executionHook.fallbackFailureException);

            // the queue() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should not have a response because run() and fallback fail
            assertNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should have an exception because run() and fallback fail
            assertNotNull(command.builder.executionHook.endExecuteFailureException);
            // run() failure
            assertEquals(FailureType.COMMAND_EXCEPTION, command.builder.executionHook.endExecuteFailureType);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());
            assertEquals(1, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on timeout without a fallback
         */
        @Test
        public void testExecutionHookTimeoutWithoutFallback() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_NOT_IMPLEMENTED);
            try {
                command.queue().get();
                fail("Expecting exception");
            } catch (Exception e) {
                // ignore
            }

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we should not have a response because of timeout and no fallback
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should not have an exception because run() didn't fail, it timed out
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run due to timeout
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // no response since no fallback
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // not null since no fallback implementation
            assertNotNull(command.builder.executionHook.fallbackFailureException);

            // execution occurred
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should not have a response because of timeout and no fallback
            assertNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should have an exception because of timeout and no fallback
            assertNotNull(command.builder.executionHook.endExecuteFailureException);
            // timeout failure
            assertEquals(FailureType.TIMEOUT, command.builder.executionHook.endExecuteFailureType);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());

            // we need to wait for the thread to complete before the onThreadComplete hook will be called
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                // ignore
            }
            assertEquals(1, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on timeout with a fallback
         */
        @Test
        public void testExecutionHookTimeoutWithFallback() {
            TestHystrixCommand<Boolean> command = new TestCommandWithTimeout(50, TestCommandWithTimeout.FALLBACK_SUCCESS);
            try {
                command.queue().get();
            } catch (Exception e) {
                throw new RuntimeException("not expecting", e);
            }

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we should not have a response because of timeout
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should not have an exception because run() didn't fail, it timed out
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run due to timeout
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // response since we have a fallback
            assertNotNull(command.builder.executionHook.fallbackSuccessResponse);
            // null since fallback succeeds
            assertNull(command.builder.executionHook.fallbackFailureException);

            // execution occurred
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should have a response because of fallback
            assertNotNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should not have an exception because of fallback
            assertNull(command.builder.executionHook.endExecuteFailureException);

            // thread execution
            assertEquals(1, command.builder.executionHook.threadStart.get());

            // we need to wait for the thread to complete before the onThreadComplete hook will be called
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                // ignore
            }
            assertEquals(1, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on rejected with a fallback
         */
        @Test
        public void testExecutionHookRejectedWithFallback() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker();
            SingleThreadedPool pool = new SingleThreadedPool(1);

            try {
                // fill the queue
                new TestCommandRejection(circuitBreaker, pool, 500, 600, TestCommandRejection.FALLBACK_SUCCESS).queue();
                new TestCommandRejection(circuitBreaker, pool, 500, 600, TestCommandRejection.FALLBACK_SUCCESS).queue();
            } catch (Exception e) {
                // ignore
            }

            TestCommandRejection command = new TestCommandRejection(circuitBreaker, pool, 500, 600, TestCommandRejection.FALLBACK_SUCCESS);
            try {
                // now execute one that will be rejected
                command.queue().get();
            } catch (Exception e) {
                throw new RuntimeException("not expecting", e);
            }

            assertTrue(command.isResponseRejected());

            // the run() method should not run as we're rejected
            assertEquals(0, command.builder.executionHook.startRun.get());
            // we should not have a response because of rejection
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should not have an exception because we didn't run
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run due to rejection
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // response since we have a fallback
            assertNotNull(command.builder.executionHook.fallbackSuccessResponse);
            // null since fallback succeeds
            assertNull(command.builder.executionHook.fallbackFailureException);

            // execution occurred
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should have a response because of fallback
            assertNotNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should not have an exception because of fallback
            assertNull(command.builder.executionHook.endExecuteFailureException);

            // thread execution
            assertEquals(0, command.builder.executionHook.threadStart.get());
            assertEquals(0, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on short-circuit with a fallback
         */
        @Test
        public void testExecutionHookShortCircuitedWithFallbackViaQueue() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker().setForceShortCircuit(true);
            KnownFailureTestCommandWithoutFallback command = new KnownFailureTestCommandWithoutFallback(circuitBreaker);
            try {
                // now execute one that will be short-circuited
                command.queue().get();
                fail("we expect an error as there is no fallback");
            } catch (Exception e) {
                // expecting
            }

            assertTrue(command.isResponseShortCircuited());

            // the run() method should not run as we're rejected
            assertEquals(0, command.builder.executionHook.startRun.get());
            // we should not have a response because of rejection
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should not have an exception because we didn't run
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run due to rejection
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // no response since we don't have a fallback
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // not null since fallback fails and throws an exception
            assertNotNull(command.builder.executionHook.fallbackFailureException);

            // execution occurred
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should not have a response because fallback fails
            assertNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we won't have an exception because short-circuit doesn't have one
            assertNull(command.builder.executionHook.endExecuteFailureException);
            // but we do expect to receive a onError call with FailureType.SHORTCIRCUIT
            assertEquals(FailureType.SHORTCIRCUIT, command.builder.executionHook.endExecuteFailureType);

            // thread execution
            assertEquals(0, command.builder.executionHook.threadStart.get());
            assertEquals(0, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on short-circuit with a fallback
         */
        @Test
        public void testExecutionHookShortCircuitedWithFallbackViaExecute() {
            TestCircuitBreaker circuitBreaker = new TestCircuitBreaker().setForceShortCircuit(true);
            KnownFailureTestCommandWithoutFallback command = new KnownFailureTestCommandWithoutFallback(circuitBreaker);
            try {
                // now execute one that will be short-circuited
                command.execute();
                fail("we expect an error as there is no fallback");
            } catch (Exception e) {
                // expecting
            }

            assertTrue(command.isResponseShortCircuited());

            // the run() method should not run as we're rejected
            assertEquals(0, command.builder.executionHook.startRun.get());
            // we should not have a response because of rejection
            assertNull(command.builder.executionHook.runSuccessResponse);
            // we should not have an exception because we didn't run
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should be run due to rejection
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // no response since we don't have a fallback
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // not null since fallback fails and throws an exception
            assertNotNull(command.builder.executionHook.fallbackFailureException);

            // execution occurred
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should not have a response because fallback fails
            assertNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we won't have an exception because short-circuit doesn't have one
            assertNull(command.builder.executionHook.endExecuteFailureException);
            // but we do expect to receive a onError call with FailureType.SHORTCIRCUIT
            assertEquals(FailureType.SHORTCIRCUIT, command.builder.executionHook.endExecuteFailureType);

            // thread execution
            assertEquals(0, command.builder.executionHook.threadStart.get());
            assertEquals(0, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on successful execution with semaphore isolation
         */
        @Test
        public void testExecutionHookSuccessfulCommandWithSemaphoreIsolation() {
            /* test with execute() */
            TestSemaphoreCommand command = new TestSemaphoreCommand(new TestCircuitBreaker(), 1, 10);
            command.execute();

            assertFalse(command.isExecutedInThread());

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we expect a successful response from run()
            assertNotNull(command.builder.executionHook.runSuccessResponse);
            // we do not expect an exception
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should not be run as we were successful
            assertEquals(0, command.builder.executionHook.startFallback.get());
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackFailureException);

            // the execute() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should have a response from execute() since run() succeeded
            assertNotNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should not have an exception since run() succeeded
            assertNull(command.builder.executionHook.endExecuteFailureException);

            // thread execution
            assertEquals(0, command.builder.executionHook.threadStart.get());
            assertEquals(0, command.builder.executionHook.threadComplete.get());

            /* test with queue() */
            command = new TestSemaphoreCommand(new TestCircuitBreaker(), 1, 10);
            try {
                command.queue().get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertFalse(command.isExecutedInThread());

            // the run() method should run as we're not short-circuited or rejected
            assertEquals(1, command.builder.executionHook.startRun.get());
            // we expect a successful response from run()
            assertNotNull(command.builder.executionHook.runSuccessResponse);
            // we do not expect an exception
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should not be run as we were successful
            assertEquals(0, command.builder.executionHook.startFallback.get());
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // null since it didn't run
            assertNull(command.builder.executionHook.fallbackFailureException);

            // the queue() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should have a response from queue() since run() succeeded
            assertNotNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we should not have an exception since run() succeeded
            assertNull(command.builder.executionHook.endExecuteFailureException);

            // thread execution
            assertEquals(0, command.builder.executionHook.threadStart.get());
            assertEquals(0, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Execution hook on successful execution with semaphore isolation
         */
        @Test
        public void testExecutionHookFailureWithSemaphoreIsolation() {
            /* test with execute() */
            final TryableSemaphore semaphore =
                    new TryableSemaphore(HystrixProperty.Factory.asProperty(0));

            TestSemaphoreCommand command = new TestSemaphoreCommand(new TestCircuitBreaker(), semaphore, 200);
            try {
                command.execute();
                fail("we expect a failure");
            } catch (Exception e) {
                // expected
            }

            assertFalse(command.isExecutedInThread());
            assertTrue(command.isResponseRejected());

            // the run() method should not run as we are rejected
            assertEquals(0, command.builder.executionHook.startRun.get());
            // null as run() does not get invoked
            assertNull(command.builder.executionHook.runSuccessResponse);
            // null as run() does not get invoked
            assertNull(command.builder.executionHook.runFailureException);

            // the fallback() method should run because of rejection
            assertEquals(1, command.builder.executionHook.startFallback.get());
            // null since there is no fallback
            assertNull(command.builder.executionHook.fallbackSuccessResponse);
            // not null since the fallback is not implemented
            assertNotNull(command.builder.executionHook.fallbackFailureException);

            // the execute() method was used
            assertEquals(1, command.builder.executionHook.startExecute.get());
            // we should not have a response since fallback has nothing
            assertNull(command.builder.executionHook.endExecuteSuccessResponse);
            // we won't have an exception because rejection doesn't have one
            assertNull(command.builder.executionHook.endExecuteFailureException);
            // but we do expect to receive a onError call with FailureType.SHORTCIRCUIT
            assertEquals(FailureType.REJECTED_SEMAPHORE_EXECUTION, command.builder.executionHook.endExecuteFailureType);

            // thread execution
            assertEquals(0, command.builder.executionHook.threadStart.get());
            assertEquals(0, command.builder.executionHook.threadComplete.get());
        }

        /**
         * Test a command execution that fails but has a fallback.
         */
        @Test
        public void testExecutionFailureWithFallbackImplementedButDisabled() {
            TestHystrixCommand<Boolean> commandEnabled = new KnownFailureTestCommandWithFallback(new TestCircuitBreaker(), true);
            try {
                assertEquals(false, commandEnabled.execute());
            } catch (Exception e) {
                e.printStackTrace();
                fail("We should have received a response from the fallback.");
            }

            TestHystrixCommand<Boolean> commandDisabled = new KnownFailureTestCommandWithFallback(new TestCircuitBreaker(), false);
            try {
                assertEquals(false, commandDisabled.execute());
                fail("expect exception thrown");
            } catch (Exception e) {
                // expected
            }

            assertEquals("we failed with a simulated issue", commandDisabled.getFailedExecutionException().getMessage());

            assertTrue(commandDisabled.isFailedExecution());

            assertEquals(0, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SUCCESS));
            assertEquals(1, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.EXCEPTION_THROWN));
            assertEquals(1, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FAILURE));
            assertEquals(0, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_REJECTION));
            assertEquals(0, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_FAILURE));
            assertEquals(0, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.FALLBACK_SUCCESS));
            assertEquals(0, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SEMAPHORE_REJECTED));
            assertEquals(0, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.SHORT_CIRCUITED));
            assertEquals(0, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.THREAD_POOL_REJECTED));
            assertEquals(0, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.TIMEOUT));
            assertEquals(0, commandDisabled.builder.metrics.getRollingCount(HystrixRollingNumberEvent.RESPONSE_FROM_CACHE));

            assertEquals(100, commandDisabled.builder.metrics.getHealthCounts().getErrorPercentage());

            assertEquals(2, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
        }

        @Test
        public void testExecutionTimeoutValue() {
            HystrixCommand.Setter properties = HystrixCommand.Setter
                    .withGroupKey(HystrixCommandGroupKey.Factory.asKey("TestKey"))
                    .andCommandPropertiesDefaults(HystrixCommandProperties.Setter()
                            .withExecutionIsolationThreadTimeoutInMilliseconds(50));

            HystrixCommand<String> command = new HystrixCommand<String>(properties) {
                @Override
                protected String run() throws Exception {
                    Thread.sleep(3000);
                    // should never reach here
                    return "hello";
                }

                @Override
                protected String getFallback() {
                    if (isResponseTimedOut()) {
                        return "timed-out";
                    } else {
                        return "abc";
                    }
                }
            };

            String value = command.execute();
            assertTrue(command.isResponseTimedOut());
            assertEquals("expected fallback value", "timed-out", value);

        }

        /* ******************************************************************************** */
        /* ******************************************************************************** */
        /* private HystrixCommand class implementations for unit testing */
        /* ******************************************************************************** */
        /* ******************************************************************************** */

        /**
         * Used by UnitTest command implementations to provide base defaults for constructor and a builder pattern for the arguments being passed in.
         */
        /* package */static abstract class TestHystrixCommand<K> extends HystrixCommand<K> {

            final TestCommandBuilder builder;

            TestHystrixCommand(TestCommandBuilder builder) {
                super(builder.owner, builder.dependencyKey, builder.threadPoolKey, builder.circuitBreaker, builder.threadPool,
                        builder.commandPropertiesDefaults, builder.threadPoolPropertiesDefaults, builder.metrics,
                        builder.fallbackSemaphore, builder.executionSemaphore, TEST_PROPERTIES_FACTORY, builder.executionHook);
                this.builder = builder;
            }

            static TestCommandBuilder testPropsBuilder() {
                return new TestCommandBuilder();
            }

            static class TestCommandBuilder {
                TestCircuitBreaker _cb = new TestCircuitBreaker();
                HystrixCommandGroupKey owner = CommandGroupForUnitTest.OWNER_ONE;
                HystrixCommandKey dependencyKey = null;
                HystrixThreadPoolKey threadPoolKey = null;
                HystrixCircuitBreaker circuitBreaker = _cb;
                HystrixThreadPool threadPool = null;
                HystrixCommandProperties.Setter commandPropertiesDefaults = HystrixCommandProperties.Setter.getUnitTestPropertiesSetter();
                HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults = HystrixThreadPoolProperties.Setter.getUnitTestPropertiesBuilder();
                HystrixCommandMetrics metrics = _cb.metrics;
                TryableSemaphore fallbackSemaphore = null;
                TryableSemaphore executionSemaphore = null;
                TestExecutionHook executionHook = new TestExecutionHook();

                TestCommandBuilder setOwner(HystrixCommandGroupKey owner) {
                    this.owner = owner;
                    return this;
                }

                TestCommandBuilder setCommandKey(HystrixCommandKey dependencyKey) {
                    this.dependencyKey = dependencyKey;
                    return this;
                }

                TestCommandBuilder setThreadPoolKey(HystrixThreadPoolKey threadPoolKey) {
                    this.threadPoolKey = threadPoolKey;
                    return this;
                }

                TestCommandBuilder setCircuitBreaker(HystrixCircuitBreaker circuitBreaker) {
                    this.circuitBreaker = circuitBreaker;
                    return this;
                }

                TestCommandBuilder setThreadPool(HystrixThreadPool threadPool) {
                    this.threadPool = threadPool;
                    return this;
                }

                TestCommandBuilder setCommandPropertiesDefaults(HystrixCommandProperties.Setter commandPropertiesDefaults) {
                    this.commandPropertiesDefaults = commandPropertiesDefaults;
                    return this;
                }

                TestCommandBuilder setThreadPoolPropertiesDefaults(HystrixThreadPoolProperties.Setter threadPoolPropertiesDefaults) {
                    this.threadPoolPropertiesDefaults = threadPoolPropertiesDefaults;
                    return this;
                }

                TestCommandBuilder setMetrics(HystrixCommandMetrics metrics) {
                    this.metrics = metrics;
                    return this;
                }

                TestCommandBuilder setFallbackSemaphore(TryableSemaphore fallbackSemaphore) {
                    this.fallbackSemaphore = fallbackSemaphore;
                    return this;
                }

                TestCommandBuilder setExecutionSemaphore(TryableSemaphore executionSemaphore) {
                    this.executionSemaphore = executionSemaphore;
                    return this;
                }

            }

        }

        /**
         * Successful execution - no fallback implementation.
         */
        private static class SuccessfulTestCommand extends TestHystrixCommand<Boolean> {

            public SuccessfulTestCommand() {
                this(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter());
            }

            public SuccessfulTestCommand(HystrixCommandProperties.Setter properties) {
                super(testPropsBuilder().setCommandPropertiesDefaults(properties));
            }

            @Override
            protected Boolean run() {
                return true;
            }

        }

        /**
         * Successful execution - no fallback implementation.
         */
        private static class DynamicOwnerTestCommand extends TestHystrixCommand<Boolean> {

            public DynamicOwnerTestCommand(HystrixCommandGroupKey owner) {
                super(testPropsBuilder().setOwner(owner));
            }

            @Override
            protected Boolean run() {
                System.out.println("successfully executed");
                return true;
            }

        }

        /**
         * Successful execution - no fallback implementation.
         */
        private static class DynamicOwnerAndKeyTestCommand extends TestHystrixCommand<Boolean> {

            public DynamicOwnerAndKeyTestCommand(HystrixCommandGroupKey owner, HystrixCommandKey key) {
                super(testPropsBuilder().setOwner(owner).setCommandKey(key).setCircuitBreaker(null).setMetrics(null));
                // we specifically are NOT passing in a circuit breaker here so we test that it creates a new one correctly based on the dynamic key
            }

            @Override
            protected Boolean run() {
                System.out.println("successfully executed");
                return true;
            }

        }

        /**
         * Failed execution with unknown exception (not HystrixException) - no fallback implementation.
         */
        private static class UnknownFailureTestCommandWithoutFallback extends TestHystrixCommand<Boolean> {

            private UnknownFailureTestCommandWithoutFallback() {
                super(testPropsBuilder());
            }

            @Override
            protected Boolean run() {
                System.out.println("*** simulated failed execution ***");
                throw new RuntimeException("we failed with an unknown issue");
            }

        }

        /**
         * Failed execution with known exception (HystrixException) - no fallback implementation.
         */
        private static class KnownFailureTestCommandWithoutFallback extends TestHystrixCommand<Boolean> {

            private KnownFailureTestCommandWithoutFallback(TestCircuitBreaker circuitBreaker) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics));
            }

            @Override
            protected Boolean run() {
                System.out.println("*** simulated failed execution ***");
                throw new RuntimeException("we failed with a simulated issue");
            }

        }

        /**
         * Failed execution - fallback implementation successfully returns value.
         */
        private static class KnownFailureTestCommandWithFallback extends TestHystrixCommand<Boolean> {

            public KnownFailureTestCommandWithFallback(TestCircuitBreaker circuitBreaker) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics));
            }

            public KnownFailureTestCommandWithFallback(TestCircuitBreaker circuitBreaker, boolean fallbackEnabled) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withFallbackEnabled(fallbackEnabled)));
            }

            @Override
            protected Boolean run() {
                System.out.println("*** simulated failed execution ***");
                throw new RuntimeException("we failed with a simulated issue");
            }

            @Override
            protected Boolean getFallback() {
                return false;
            }
        }

        /**
         * Failed execution - fallback implementation throws exception.
         */
        private static class KnownFailureTestCommandWithFallbackFailure extends TestHystrixCommand<Boolean> {

            private KnownFailureTestCommandWithFallbackFailure() {
                super(testPropsBuilder());
            }

            @Override
            protected Boolean run() {
                System.out.println("*** simulated failed execution ***");
                throw new RuntimeException("we failed with a simulated issue");
            }

            @Override
            protected Boolean getFallback() {
                throw new RuntimeException("failed while getting fallback");
            }
        }

        /**
         * A Command implementation that supports caching.
         */
        private static class SuccessfulCacheableCommand extends TestHystrixCommand<String> {

            private final boolean cacheEnabled;
            private volatile boolean executed = false;
            private final String value;

            public SuccessfulCacheableCommand(TestCircuitBreaker circuitBreaker, boolean cacheEnabled, String value) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics));
                this.value = value;
                this.cacheEnabled = cacheEnabled;
            }

            @Override
            protected String run() {
                executed = true;
                System.out.println("successfully executed");
                return value;
            }

            public boolean isCommandRunningInThread() {
                return super.getProperties().executionIsolationStrategy().get().equals(ExecutionIsolationStrategy.THREAD);
            }

            @Override
            public String getCacheKey() {
                if (cacheEnabled)
                    return value;
                else
                    return null;
            }
        }

        /**
         * A Command implementation that supports caching.
         */
        private static class SuccessfulCacheableCommandViaSemaphore extends TestHystrixCommand<String> {

            private final boolean cacheEnabled;
            private volatile boolean executed = false;
            private final String value;

            public SuccessfulCacheableCommandViaSemaphore(TestCircuitBreaker circuitBreaker, boolean cacheEnabled, String value) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)));
                this.value = value;
                this.cacheEnabled = cacheEnabled;
            }

            @Override
            protected String run() {
                executed = true;
                System.out.println("successfully executed");
                return value;
            }

            public boolean isCommandRunningInThread() {
                return super.getProperties().executionIsolationStrategy().get().equals(ExecutionIsolationStrategy.THREAD);
            }

            @Override
            public String getCacheKey() {
                if (cacheEnabled)
                    return value;
                else
                    return null;
            }
        }

        /**
         * A Command implementation that supports caching and execution takes a while.
         * <p>
         * Used to test scenario where Futures are returned with a backing call still executing.
         */
        private static class SlowCacheableCommand extends TestHystrixCommand<String> {

            private final String value;
            private final int duration;
            private volatile boolean executed = false;

            public SlowCacheableCommand(TestCircuitBreaker circuitBreaker, String value, int duration) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics));
                this.value = value;
                this.duration = duration;
            }

            @Override
            protected String run() {
                executed = true;
                try {
                    Thread.sleep(duration);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                System.out.println("successfully executed");
                return value;
            }

            @Override
            public String getCacheKey() {
                return value;
            }
        }

        /**
         * Successful execution - no fallback implementation, circuit-breaker disabled.
         */
        private static class TestCommandWithoutCircuitBreaker extends TestHystrixCommand<Boolean> {

            private TestCommandWithoutCircuitBreaker() {
                super(testPropsBuilder().setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withCircuitBreakerEnabled(false)));
            }

            @Override
            protected Boolean run() {
                System.out.println("successfully executed");
                return true;
            }

        }

        /**
         * This should timeout.
         */
        private static class TestCommandWithTimeout extends TestHystrixCommand<Boolean> {

            private final long timeout;

            private final static int FALLBACK_NOT_IMPLEMENTED = 1;
            private final static int FALLBACK_SUCCESS = 2;
            private final static int FALLBACK_FAILURE = 3;

            private final int fallbackBehavior;

            private TestCommandWithTimeout(long timeout, int fallbackBehavior) {
                super(testPropsBuilder().setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationThreadTimeoutInMilliseconds((int) timeout)));
                this.timeout = timeout;
                this.fallbackBehavior = fallbackBehavior;
            }

            @Override
            protected Boolean run() {
                System.out.println("***** running");
                try {
                    Thread.sleep(timeout * 10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    // ignore and sleep some more to simulate a dependency that doesn't obey interrupts
                    try {
                        Thread.sleep(timeout * 2);
                    } catch (Exception e2) {
                        // ignore
                    }
                    System.out.println("after interruption with extra sleep");
                }
                return true;
            }

            @Override
            protected Boolean getFallback() {
                if (fallbackBehavior == FALLBACK_SUCCESS) {
                    return false;
                } else if (fallbackBehavior == FALLBACK_FAILURE) {
                    throw new RuntimeException("failed on fallback");
                } else {
                    // FALLBACK_NOT_IMPLEMENTED
                    return super.getFallback();
                }
            }
        }

        /**
         * Threadpool with 1 thread, queue of size 1
         */
        private static class SingleThreadedPool implements HystrixThreadPool {

            final LinkedBlockingQueue<Runnable> queue;
            final ThreadPoolExecutor pool;
            private final int rejectionQueueSizeThreshold;

            public SingleThreadedPool(int queueSize) {
                this(queueSize, 100);
            }

            public SingleThreadedPool(int queueSize, int rejectionQueueSizeThreshold) {
                queue = new LinkedBlockingQueue<Runnable>(queueSize);
                pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.MINUTES, queue);
                this.rejectionQueueSizeThreshold = rejectionQueueSizeThreshold;
            }

            @Override
            public ThreadPoolExecutor getExecutor() {
                return pool;
            }

            @Override
            public void markThreadExecution() {
                // not used for this test
            }

            @Override
            public void markThreadCompletion() {
                // not used for this test
            }

            @Override
            public boolean isQueueSpaceAvailable() {
                return queue.size() < rejectionQueueSizeThreshold;
            }

        }

        /**
         * This has a ThreadPool that has a single thread and queueSize of 1.
         */
        private static class TestCommandRejection extends TestHystrixCommand<Boolean> {

            private final static int FALLBACK_NOT_IMPLEMENTED = 1;
            private final static int FALLBACK_SUCCESS = 2;
            private final static int FALLBACK_FAILURE = 3;

            private final int fallbackBehavior;

            private final int sleepTime;

            private TestCommandRejection(TestCircuitBreaker circuitBreaker, HystrixThreadPool threadPool, int sleepTime, int timeout, int fallbackBehavior) {
                super(testPropsBuilder().setThreadPool(threadPool).setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationThreadTimeoutInMilliseconds(timeout)));
                this.fallbackBehavior = fallbackBehavior;
                this.sleepTime = sleepTime;
            }

            @Override
            protected Boolean run() {
                System.out.println(">>> TestCommandRejection running");
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }

            @Override
            protected Boolean getFallback() {
                if (fallbackBehavior == FALLBACK_SUCCESS) {
                    return false;
                } else if (fallbackBehavior == FALLBACK_FAILURE) {
                    throw new RuntimeException("failed on fallback");
                } else {
                    // FALLBACK_NOT_IMPLEMENTED
                    return super.getFallback();
                }
            }
        }

        /**
         * Command that receives a custom thread-pool, sleepTime, timeout
         */
        private static class CommandWithCustomThreadPool extends TestHystrixCommand<Boolean> {

            public boolean didExecute = false;

            private final int sleepTime;

            private CommandWithCustomThreadPool(TestCircuitBreaker circuitBreaker, HystrixThreadPool threadPool, int sleepTime, HystrixCommandProperties.Setter properties) {
                super(testPropsBuilder().setThreadPool(threadPool).setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics).setCommandPropertiesDefaults(properties));
                this.sleepTime = sleepTime;
            }

            @Override
            protected Boolean run() {
                System.out.println("**** Executing CommandWithCustomThreadPool. Execution => " + sleepTime);
                didExecute = true;
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }
        }

        /**
         * The run() will fail and getFallback() take a long time.
         */
        private static class TestSemaphoreCommandWithSlowFallback extends TestHystrixCommand<Boolean> {

            private final long fallbackSleep;

            private TestSemaphoreCommandWithSlowFallback(TestCircuitBreaker circuitBreaker, int fallbackSemaphoreExecutionCount, long fallbackSleep) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withFallbackIsolationSemaphoreMaxConcurrentRequests(fallbackSemaphoreExecutionCount)));
                this.fallbackSleep = fallbackSleep;
            }

            @Override
            protected Boolean run() {
                throw new RuntimeException("run fails");
            }

            @Override
            protected Boolean getFallback() {
                try {
                    Thread.sleep(fallbackSleep);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }
        }

        private static class NoRequestCacheTimeoutWithoutFallback extends TestHystrixCommand<Boolean> {
            public NoRequestCacheTimeoutWithoutFallback(TestCircuitBreaker circuitBreaker) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationThreadTimeoutInMilliseconds(200)));

                // we want it to timeout
            }

            @Override
            protected Boolean run() {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    System.out.println(">>>> Sleep Interrupted: " + e.getMessage());
                    //                    e.printStackTrace();
                }
                return true;
            }

            @Override
            public String getCacheKey() {
                return null;
            }
        }

        /**
         * The run() will take time. No fallback implementation.
         */
        private static class TestSemaphoreCommand extends TestHystrixCommand<Boolean> {

            private final long executionSleep;

            private TestSemaphoreCommand(TestCircuitBreaker circuitBreaker, int executionSemaphoreCount, long executionSleep) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter()
                                .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE)
                                .withExecutionIsolationSemaphoreMaxConcurrentRequests(executionSemaphoreCount)));
                this.executionSleep = executionSleep;
            }

            private TestSemaphoreCommand(TestCircuitBreaker circuitBreaker, TryableSemaphore semaphore, long executionSleep) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter()
                                .withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE))
                        .setExecutionSemaphore(semaphore));
                this.executionSleep = executionSleep;
            }

            @Override
            protected Boolean run() {
                try {
                    Thread.sleep(executionSleep);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }
        }

        /**
         * Semaphore based command that allows caller to use latches to know when it has started and signal when it
         * would like the command to finish
         */
        private static class LatchedSemaphoreCommand extends TestHystrixCommand<Boolean> {

            private final CountDownLatch startLatch, waitLatch;

            /**
             * 
             * @param circuitBreaker
             * @param semaphore
             * @param startLatch
             *            this command calls {@link java.util.concurrent.CountDownLatch#countDown()} immediately
             *            upon running
             * @param waitLatch
             *            this command calls {@link java.util.concurrent.CountDownLatch#await()} once it starts
             *            to run. The caller can use the latch to signal the command to finish
             */
            private LatchedSemaphoreCommand(TestCircuitBreaker circuitBreaker, TryableSemaphore semaphore,
                    CountDownLatch startLatch, CountDownLatch waitLatch) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE))
                        .setExecutionSemaphore(semaphore));
                this.startLatch = startLatch;
                this.waitLatch = waitLatch;
            }

            @Override
            protected Boolean run() {
                // signals caller that run has started
                this.startLatch.countDown();

                try {
                    // waits for caller to countDown latch
                    this.waitLatch.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    return false;
                }
                return true;
            }
        }

        /**
         * The run() will take time. Contains fallback.
         */
        private static class TestSemaphoreCommandWithFallback extends TestHystrixCommand<Boolean> {

            private final long executionSleep;
            private final Boolean fallback;

            private TestSemaphoreCommandWithFallback(TestCircuitBreaker circuitBreaker, int executionSemaphoreCount, long executionSleep, Boolean fallback) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(ExecutionIsolationStrategy.SEMAPHORE).withExecutionIsolationSemaphoreMaxConcurrentRequests(executionSemaphoreCount)));
                this.executionSleep = executionSleep;
                this.fallback = fallback;
            }

            @Override
            protected Boolean run() {
                try {
                    Thread.sleep(executionSleep);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }

            @Override
            protected Boolean getFallback() {
                return fallback;
            }

        }

        private static class RequestCacheNullPointerExceptionCase extends TestHystrixCommand<Boolean> {
            public RequestCacheNullPointerExceptionCase(TestCircuitBreaker circuitBreaker) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationThreadTimeoutInMilliseconds(200)));
                // we want it to timeout
            }

            @Override
            protected Boolean run() {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }

            @Override
            protected Boolean getFallback() {
                return false;
            }

            @Override
            public String getCacheKey() {
                return "A";
            }
        }

        private static class RequestCacheTimeoutWithoutFallback extends TestHystrixCommand<Boolean> {
            public RequestCacheTimeoutWithoutFallback(TestCircuitBreaker circuitBreaker) {
                super(testPropsBuilder().setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationThreadTimeoutInMilliseconds(200)));
                // we want it to timeout
            }

            @Override
            protected Boolean run() {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    System.out.println(">>>> Sleep Interrupted: " + e.getMessage());
                    //                    e.printStackTrace();
                }
                return true;
            }

            @Override
            public String getCacheKey() {
                return "A";
            }
        }

        private static class RequestCacheThreadRejectionWithoutFallback extends TestHystrixCommand<Boolean> {

            final CountDownLatch completionLatch;

            public RequestCacheThreadRejectionWithoutFallback(TestCircuitBreaker circuitBreaker, CountDownLatch completionLatch) {
                super(testPropsBuilder()
                        .setCircuitBreaker(circuitBreaker)
                        .setMetrics(circuitBreaker.metrics)
                        .setThreadPool(new HystrixThreadPool() {

                            @Override
                            public ThreadPoolExecutor getExecutor() {
                                return null;
                            }

                            @Override
                            public void markThreadExecution() {

                            }

                            @Override
                            public void markThreadCompletion() {

                            }

                            @Override
                            public boolean isQueueSpaceAvailable() {
                                // always return false so we reject everything
                                return false;
                            }

                        }));
                this.completionLatch = completionLatch;
            }

            @Override
            protected Boolean run() {
                try {
                    if (completionLatch.await(1000, TimeUnit.MILLISECONDS)) {
                        throw new RuntimeException("timed out waiting on completionLatch");
                    }
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return true;
            }

            @Override
            public String getCacheKey() {
                return "A";
            }
        }

        private static class BadRequestCommand extends TestHystrixCommand<Boolean> {

            public BadRequestCommand(TestCircuitBreaker circuitBreaker, ExecutionIsolationStrategy isolationType) {
                super(testPropsBuilder()
                        .setCircuitBreaker(circuitBreaker)
                        .setCommandPropertiesDefaults(HystrixCommandProperties.Setter.getUnitTestPropertiesSetter().withExecutionIsolationStrategy(isolationType)));
            }

            @Override
            protected Boolean run() {
                throw new HystrixBadRequestException("Message to developer that they passed in bad data or something like that.");
            }

            @Override
            protected Boolean getFallback() {
                return false;
            }

            @Override
            protected String getCacheKey() {
                return "one";
            }

        }

        private static class CommandWithErrorThrown extends TestHystrixCommand<Boolean> {

            public CommandWithErrorThrown(TestCircuitBreaker circuitBreaker) {
                super(testPropsBuilder()
                        .setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics));
            }

            @Override
            protected Boolean run() throws Exception {
                throw new Error("simulated java.lang.Error message");
            }

        }

        private static class CommandWithCheckedException extends TestHystrixCommand<Boolean> {

            public CommandWithCheckedException(TestCircuitBreaker circuitBreaker) {
                super(testPropsBuilder()
                        .setCircuitBreaker(circuitBreaker).setMetrics(circuitBreaker.metrics));
            }

            @Override
            protected Boolean run() throws Exception {
                throw new IOException("simulated checked exception message");
            }

        }

        enum CommandKeyForUnitTest implements HystrixCommandKey {
            KEY_ONE, KEY_TWO;
        }

        enum CommandGroupForUnitTest implements HystrixCommandGroupKey {
            OWNER_ONE, OWNER_TWO;
        }

        enum ThreadPoolKeyForUnitTest implements HystrixThreadPoolKey {
            THREAD_POOL_ONE, THREAD_POOL_TWO;
        }

        private static HystrixPropertiesStrategy TEST_PROPERTIES_FACTORY = new TestPropertiesFactory();

        private static class TestPropertiesFactory extends HystrixPropertiesStrategy {

            @Override
            public HystrixCommandProperties getCommandProperties(HystrixCommandKey commandKey, HystrixCommandProperties.Setter builder) {
                if (builder == null) {
                    builder = HystrixCommandProperties.Setter.getUnitTestPropertiesSetter();
                }
                return HystrixCommandProperties.Setter.asMock(builder);
            }

            @Override
            public HystrixThreadPoolProperties getThreadPoolProperties(HystrixThreadPoolKey threadPoolKey, HystrixThreadPoolProperties.Setter builder) {
                if (builder == null) {
                    builder = HystrixThreadPoolProperties.Setter.getUnitTestPropertiesBuilder();
                }
                return HystrixThreadPoolProperties.Setter.asMock(builder);
            }

            @Override
            public HystrixCollapserProperties getCollapserProperties(HystrixCollapserKey collapserKey, HystrixCollapserProperties.Setter builder) {
                throw new IllegalStateException("not expecting collapser properties");
            }

            @Override
            public String getCommandPropertiesCacheKey(HystrixCommandKey commandKey, HystrixCommandProperties.Setter builder) {
                return null;
            }

            @Override
            public String getThreadPoolPropertiesCacheKey(HystrixThreadPoolKey threadPoolKey, com.netflix.hystrix.HystrixThreadPoolProperties.Setter builder) {
                return null;
            }

            @Override
            public String getCollapserPropertiesCacheKey(HystrixCollapserKey collapserKey, com.netflix.hystrix.HystrixCollapserProperties.Setter builder) {
                return null;
            }

        }

        private static class TestExecutionHook extends HystrixCommandExecutionHook {

            AtomicInteger startExecute = new AtomicInteger();

            @Override
            public <T> void onStart(HystrixCommand<T> commandInstance) {
                super.onStart(commandInstance);
                startExecute.incrementAndGet();
            }

            Object endExecuteSuccessResponse = null;

            @Override
            public <T> T onComplete(HystrixCommand<T> commandInstance, T response) {
                endExecuteSuccessResponse = response;
                return super.onComplete(commandInstance, response);
            }

            Exception endExecuteFailureException = null;
            FailureType endExecuteFailureType = null;

            @Override
            public <T> Exception onError(HystrixCommand<T> commandInstance, FailureType failureType, Exception e) {
                endExecuteFailureException = e;
                endExecuteFailureType = failureType;
                return super.onError(commandInstance, failureType, e);
            }

            AtomicInteger startRun = new AtomicInteger();

            @Override
            public <T> void onRunStart(HystrixCommand<T> commandInstance) {
                super.onRunStart(commandInstance);
                startRun.incrementAndGet();
            }

            Object runSuccessResponse = null;

            @Override
            public <T> T onRunSuccess(HystrixCommand<T> commandInstance, T response) {
                runSuccessResponse = response;
                return super.onRunSuccess(commandInstance, response);
            }

            Exception runFailureException = null;

            @Override
            public <T> Exception onRunError(HystrixCommand<T> commandInstance, Exception e) {
                runFailureException = e;
                return super.onRunError(commandInstance, e);
            }

            AtomicInteger startFallback = new AtomicInteger();

            @Override
            public <T> void onFallbackStart(HystrixCommand<T> commandInstance) {
                super.onFallbackStart(commandInstance);
                startFallback.incrementAndGet();
            }

            Object fallbackSuccessResponse = null;

            @Override
            public <T> T onFallbackSuccess(HystrixCommand<T> commandInstance, T response) {
                fallbackSuccessResponse = response;
                return super.onFallbackSuccess(commandInstance, response);
            }

            Exception fallbackFailureException = null;

            @Override
            public <T> Exception onFallbackError(HystrixCommand<T> commandInstance, Exception e) {
                fallbackFailureException = e;
                return super.onFallbackError(commandInstance, e);
            }

            AtomicInteger threadStart = new AtomicInteger();

            @Override
            public <T> void onThreadStart(HystrixCommand<T> commandInstance) {
                super.onThreadStart(commandInstance);
                threadStart.incrementAndGet();
            }

            AtomicInteger threadComplete = new AtomicInteger();

            @Override
            public <T> void onThreadComplete(HystrixCommand<T> commandInstance) {
                super.onThreadComplete(commandInstance);
                threadComplete.incrementAndGet();
            }

        }
    }

}
