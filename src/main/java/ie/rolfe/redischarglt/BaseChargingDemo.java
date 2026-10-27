/*
 * Copyright (C) 2026 David Rolfe
 *
 * Use of this source code is governed by an MIT
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */
/*
 * Copyright (C) 2025 Volt Active Data Inc.
 *
 * Use of this source code is governed by an MIT
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */

package ie.rolfe.redischarglt;

import com.google.gson.Gson;
import ie.rolfe.redischarglt.documents.ExtraUserData;
import ie.rolfe.redischarglt.documents.UserTable;
import ie.rolfe.redischarglt.documents.UserUsageTable;
import org.voltdb.voltutil.stats.SafeHistogramCache;
import redis.clients.jedis.AbstractTransaction;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.json.Path;
import redis.clients.jedis.json.Path2;

import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * This is an abstract class that contains the actual logic of the demo code.
 */
public abstract class BaseChargingDemo {

    public static final long GENERIC_QUERY_USER_ID = 42;
    public static final long NO_SESSION = Long.MIN_VALUE;
    public static final Date NO_EXPIRY = new Date(0);

    public static final String REPORT_QUOTA_USAGE = "ReportQuotaUsage";
    public static final String KV_PUT = "KV_PUT";
    public static final String KV_GET = "KV_GET";
    public static final String DELETE_DOC = "Delete Doc";
    public static final String DELETE_DOC_ERROR = "Delete Doc Error";
    public static final String ADD_DOC = "Add Doc";
    public static final String ADD_DOC_ERROR = "Add Doc Error";
    public static final String UNABLE_TO_MEET_REQUESTED_TPS = "UNABLE_TO_MEET_REQUESTED_TPS";
    public static final String EXTRA_MS = "EXTRA_MS";
    protected static final int REDIS_DEFAULT_PORT = 6379;
    private static final String CHARGLT_USERS = "CHARGLT_USERS";
    private static final String ADD_CREDIT = "ADD_CREDIT";
    private static final String CLEAR_LOCK = "CLEAR_LOCK";
    private static final String CLEAR_UNFINISHED = "CLEAR_UNFINISHED";
    private static final String COUNT_USAGE_TOTAL_BY_DOC = "COUNT_USAGE_TOTAL_BY_DOC";
    private static final String WATCH_CONFLICT = "WATCH_CONFLICT";
    private static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    private static final int MAX_WATCH_RETRIES = 10;
    public static SafeHistogramCache shc = SafeHistogramCache.getInstance();

    /**
     * Print a formatted message.
     *
     * @param message
     */
    public static void msg(String message) {

        SimpleDateFormat sdfDate;
        sdfDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date now = new Date();
        String strDate = sdfDate.format(now);
        System.out.println(strDate + ":" + message);

    }

    /**
     * Connect to REDIS.
     *
     * @param host        REDIS host
     * @param threadCount how many threads will share the connection pool
     * @return a pooled, thread safe client
     */
    protected static JedisPooled connect(String host, int threadCount) {
        msg("Connecting to REDIS at " + host + ":" + REDIS_DEFAULT_PORT);
        return new JedisPooled(buildPoolConfig(threadCount), host, REDIS_DEFAULT_PORT);
    }


    /**
     * Convenience method to generate a JSON payload.
     *
     * @param length
     * @return
     */
    public static ExtraUserData getExtraUserDataAsObject(int length, Gson gson, Random r) {

        ExtraUserData eud = new ExtraUserData();

        eud.loyaltySchemeName = "HelperCard";
        eud.loyaltySchemeNumber = getNewLoyaltyCardNumber(r);

        StringBuffer ourText = new StringBuffer();

        for (int i = 0; i < length / 2; i++) {
            ourText.append(Integer.toHexString(r.nextInt(256)));
        }

        eud.mysteriousHexPayload = ourText.toString();

        return eud;
    }


    protected static void upsertAllUsers(int userCount, int tpMs, ExtraUserData ourEud, int initialCredit,
                                         JedisPooled redisClient, int threadCount, int extraMs)
            throws InterruptedException {

        final long startMsUpsert = System.currentTimeMillis();
        final int usableThreadCount = getUsableThreadCount(threadCount, tpMs, userCount);
        final AtomicLong upsertCount = new AtomicLong();

        msg("Upserting " + userCount + " users using " + usableThreadCount + " threads");

        runInThreads(usableThreadCount, threadId -> () -> {

            TpMsThrottle throttle = new TpMsThrottle(getThreadTpMs(tpMs, usableThreadCount, threadId), extraMs);
            Random r = new Random();

            for (int i = threadId; i < userCount; i += usableThreadCount) {

                throttle.await();

                UserTable newUser = UserTable.getUserTable(ourEud, r.nextInt(initialCredit), i, startMsUpsert);
                newUser.addCredit(100, "Txn_" + i);
                newUser.reportQuotaUsage(100, 10, 100, "TX2_" + i);

                final long startMs = System.currentTimeMillis();

                try {
                    redisClient.jsonSet(getKey(i), Path2.ROOT_PATH, newUser.toJson());
                    shc.reportLatency(BaseChargingDemo.ADD_DOC, startMs, "Add time", 2000);
                    shc.incCounter(BaseChargingDemo.ADD_DOC);
                } catch (Exception e) {
                    msg("Upsert of user " + i + " failed: " + e.getMessage());
                    shc.incCounter(BaseChargingDemo.ADD_DOC_ERROR);
                }

                long upserted = upsertCount.incrementAndGet();

                if (upserted % 100000 == 1) {
                    msg("Upserted " + upserted + " users...");

                    if (shc.getCounter(BaseChargingDemo.ADD_DOC_ERROR) > 0) {
                        msg("Errors detected. Halting...");
                        break;
                    } else if (threadId == 0) {
                        queryUserAndStats(redisClient, i, userCount);
                    }

                }

            }

            return null;
        });


        long entriesPerMS = userCount / Math.max(1, System.currentTimeMillis() - startMsUpsert);
        msg("Upserted " + entriesPerMS + " users per ms...");
        msg(shc.toString());
    }

    private static String getKey(long i) {
        return CHARGLT_USERS + ":" + i;
    }


    protected static void deleteAllUsers(JedisPooled redisClient, int userCount, int tpMs, int threadCount,
                                         int extraMs) throws InterruptedException {

        final long startMsUpsert = System.currentTimeMillis();
        final int usableThreadCount = getUsableThreadCount(threadCount, tpMs, userCount);
        final AtomicLong deleteCount = new AtomicLong();

        msg("Deleting " + userCount + " users using " + usableThreadCount + " threads");

        runInThreads(usableThreadCount, threadId -> () -> {

            TpMsThrottle throttle = new TpMsThrottle(getThreadTpMs(tpMs, usableThreadCount, threadId), extraMs);

            for (int i = threadId; i < userCount; i += usableThreadCount) {

                throttle.await();

                long startNs = System.currentTimeMillis();
                long howMany = redisClient.del(getKey(i));
                shc.reportLatency(BaseChargingDemo.DELETE_DOC, startNs, "Delete time", 2000);
                if (howMany == 1) {
                    shc.incCounter(BaseChargingDemo.DELETE_DOC);
                } else {
                    shc.incCounter(BaseChargingDemo.DELETE_DOC_ERROR);
                }

                long deleted = deleteCount.incrementAndGet();
                if (deleted % 100000 == 1) {
                    msg("Deleted " + deleted + " users...");
                }

            }

            return null;
        });

        long entriesPerMS = userCount / Math.max(1, System.currentTimeMillis() - startMsUpsert);
        msg("Deleted " + entriesPerMS + " users per ms...");
        msg(shc.toString());

    }

    /**
     * Convenience method to query a user a general stats and log the results
     */
    protected static void queryUserAndStats(JedisPooled redisClient, int queryUserId, int userCount) {

        // Query user #queryUserId...
        msg("Query user #" + queryUserId + "...");
        UserTable ut = getUser(redisClient, queryUserId);
        reportDocument(ut);
        msg("Show amount of credit currently reserved for products...");
        getCurrentReservedCredit(redisClient, userCount);

    }

    private static void getCurrentReservedCredit(JedisPooled theClient, int userCount) {

        final long getDocByDocMs = System.currentTimeMillis();

        long total = 0;

        for (int i = 0; i < userCount; i++) {

            UserTable ut = getUser(theClient, i);
            if (ut != null) {
                total += ut.getUsageBalance();
            }


            if (i % 100000 == 1) {
                msg("Queried " + i + " users. Total is " + total);

            }

        }


        msg("Total for " + userCount + " users is " + total);

        shc.reportLatency(BaseChargingDemo.COUNT_USAGE_TOTAL_BY_DOC, getDocByDocMs, "Time to count usage", 10000);
    }

    private static UserTable getUser(JedisPooled theClient, long queryUserId) {
        return UserTable.fromJson(theClient.jsonGetAsPlainString(getKey(queryUserId), Path.ROOT_PATH));
    }

    /**
     * Read a user, change it, and write it back only if nobody else changed it in the meantime.
     * WATCH makes EXEC fail if the key changes after we start looking at it, in which case we
     * re-read and retry.
     *
     * @param redisClient
     * @param userId
     * @param change      changes the UserTable and returns true if it needs to be written
     * @return the UserTable as written, or null if the user doesn't exist
     */
    private static UserTable readModifyWrite(JedisPooled redisClient, long userId, Predicate<UserTable> change) {

        final String key = getKey(userId);

        for (int attempt = 0; attempt < MAX_WATCH_RETRIES; attempt++) {

            // WATCH needs a connection of its own until EXEC...
            try (AbstractTransaction tx = redisClient.transaction(false)) {

                tx.watch(key);

                // ...but the read can use any connection, as long as it happens after the WATCH.
                UserTable ut = getUser(redisClient, userId);

                if (ut == null) {
                    tx.unwatch();
                    shc.incCounter(USER_NOT_FOUND);
                    msg("User " + userId + " not found");
                    return null;
                }

                if (!change.test(ut)) {
                    tx.unwatch();
                    return ut;
                }

                tx.multi();
                tx.jsonSet(key, Path2.ROOT_PATH, ut.toJson());

                if (tx.exec() != null) {
                    return ut;
                }

                shc.incCounter(WATCH_CONFLICT);
            }
        }

        throw new IllegalStateException("User " + userId + " still changing after " + MAX_WATCH_RETRIES + " attempts");
    }


    static protected void reportDocument(UserTable ut) {
        if (ut == null) {
            msg("Document is null...");
        } else {
            msg(ut.toString());

        }
    }


    /**
     *
     * Run a key value store benchmark for userCount users at tpMs transactions per
     * millisecond and with deltaProportion records sending the entire record.
     *
     * @param userCount
     * @param tpMs
     * @param durationSeconds
     * @param globalQueryFreqSeconds
     * @param jsonsize
     * @param mainClient
     * @param deltaProportion
     * @param extraMs
     * @param threadCount            how many threads to spread the work over
     * @return true if >=90% of requested throughput was achieved.
     * @throws InterruptedException
     */
    protected static boolean runKVBenchmark(int userCount, int tpMs, int durationSeconds, int globalQueryFreqSeconds,
                                            int jsonsize, JedisPooled mainClient, int deltaProportion, int extraMs,
                                            int threadCount)
            throws InterruptedException {

        final int usableThreadCount = getUsableThreadCount(threadCount, tpMs, userCount);
        final AtomicLong progressCount = new AtomicLong();

        final long startMsRun = System.currentTimeMillis();
        final long endtimeMs = System.currentTimeMillis() + (durationSeconds * 1000L);

        msg("Running KV benchmark for " + userCount + " users using " + usableThreadCount + " threads");

        // Keep an eye on one user while we run...
        final int watchedUser = new Random().nextInt(userCount);
        ScheduledExecutorService globalQueries = startGlobalQueries(mainClient, watchedUser, userCount,
                globalQueryFreqSeconds);

        KVResults results = new KVResults();

        try {
            for (KVResults threadResults : runInThreads(usableThreadCount, threadId -> () ->
                    runKVWorker(threadId, usableThreadCount, userCount, tpMs, endtimeMs, jsonsize, mainClient,
                            deltaProportion, extraMs, progressCount))) {
                results.add(threadResults);
            }
        } finally {
            globalQueries.shutdownNow();
        }

        msg(results.tranCount + " transactions done...");

        long transactionsPerMs = results.tranCount / Math.max(1, System.currentTimeMillis() - startMsRun);
        msg("processed " + transactionsPerMs + " entries per ms while doing transactions...");

        msg(results.inFlightCount + " events where a tx was in flight were observed");
        msg(results.lockCount + " lock attempts");
        msg(results.contestedLockCount + " contested lock attempts");
        msg(results.lockFailCount + " lock attempt failures");
        msg(results.fullUpdate + " full updates");
        msg(results.deltaUpdate + " delta updates");
        msg("Retries caused by concurrent changes = " + shc.getCounter(WATCH_CONFLICT));

        double tps = results.tranCount;
        tps = tps / (System.currentTimeMillis() - startMsRun);
        tps = tps * 1000;

        reportRunLatencyStats(tpMs, tps);

        // Declare victory if we got >= 90% of requested TPS...
        return tps / (tpMs * 1000) > .9;
    }

    /**
     * One thread's share of the KV benchmark. It owns every user whose id % threadCount == threadId,
     * so no other thread in this process touches its users.
     */
    private static KVResults runKVWorker(int threadId, int threadCount, int userCount, int tpMs, long endtimeMs,
                                         int jsonsize, JedisPooled mainClient, int deltaProportion, int extraMs,
                                         AtomicLong progressCount)
            throws InterruptedException {

        UserKVState[] userState = new UserKVState[getThreadUserCount(userCount, threadCount, threadId)];

        for (int i = 0; i < userState.length; i++) {
            userState[i] = new UserKVState(i * threadCount + threadId, shc);
        }

        Random r = new Random();
        Gson gson = new Gson();
        TpMsThrottle throttle = new TpMsThrottle(getThreadTpMs(tpMs, threadCount, threadId), extraMs);
        KVResults results = new KVResults();

        while (endtimeMs > System.currentTimeMillis()) {

            throttle.await();

            // Find session to do a transaction for...
            UserKVState ourState = userState[r.nextInt(userState.length)];
            int oursession = ourState.id;

            // See if session already has an active transaction and avoid
            // it if it does.
            if (ourState.isTxInFlight()) {

                results.inFlightCount++;

            } else if (ourState.getUserStatus() == UserKVState.STATUS_LOCKED_BY_SOMEONE_ELSE) {

                if (ourState.getOtherLockTimeMs() + ReferenceData.LOCK_TIMEOUT_MS < System
                        .currentTimeMillis()) {

                    ourState.startTran();
                    ourState.setStatus(UserKVState.STATUS_TRYING_TO_LOCK);
                    GetAndLockUser(mainClient, ourState, oursession);
                    results.lockCount++;

                } else {
                    results.contestedLockCount++;
                }

            } else if (ourState.getUserStatus() == UserKVState.STATUS_UNLOCKED) {

                ourState.startTran();
                ourState.setStatus(UserKVState.STATUS_TRYING_TO_LOCK);
                GetAndLockUser(mainClient, ourState, oursession);
                results.lockCount++;

            } else if (ourState.getUserStatus() == UserKVState.STATUS_LOCKED) {

                ourState.startTran();
                ourState.setStatus(UserKVState.STATUS_UPDATING);

                if (deltaProportion > r.nextInt(101)) {
                    results.deltaUpdate++;
                    // Instead of sending entire JSON object across wire ask app to update loyalty
                    // number. For
                    // large values stored as JSON this can have a dramatic effect on network
                    // bandwidth
                    UpdateLockedUser(mainClient, ourState, ourState.getLockId(), oursession,
                            getNewLoyaltyCardNumber(r) + "", ExtraUserData.NEW_LOYALTY_NUMBER);
                } else {
                    results.fullUpdate++;
                    UpdateLockedUser(mainClient, ourState, ourState.getLockId(), oursession,
                            getExtraUserDataAsObject(jsonsize, gson, r), null);
                }

            }

            results.tranCount++;
            ourState.endTran(); //TODO - Fix when we make async

            long progress = progressCount.incrementAndGet();
            if (progress % 100000 == 1) {
                msg("Transaction " + progress);
            }

        }

        for (UserKVState state : userState) {
            results.lockFailCount += state.getLockedBySomeoneElseCount();
        }

        return results;
    }

    private static void GetAndLockUser(JedisPooled redisClient, UserKVState userKVState, int sessionId) {

        final long startMs = System.currentTimeMillis();
        final long[] newLockId = {NO_SESSION};

        try {
            UserTable ut = readModifyWrite(redisClient, sessionId, theUser -> {
                // lock() returns NO_SESSION if someone else holds an unexpired lock
                newLockId[0] = theUser.lock();
                return newLockId[0] != NO_SESSION;
            });

            if (ut == null) {
                userKVState.setLockId(NO_SESSION);
            } else if (newLockId[0] == NO_SESSION) {
                userKVState.setLockId(NO_SESSION);
                userKVState.setStatus(UserKVState.STATUS_LOCKED_BY_SOMEONE_ELSE);
                userKVState.lockedBySomeoneElseCount++;
                userKVState.otherLockTimeMs = System.currentTimeMillis();
            } else {
                userKVState.setLockId(newLockId[0]);
            }

            shc.reportLatency(BaseChargingDemo.KV_GET, startMs, "KV Get time", 2000);
        } catch (Exception e) {
            e.printStackTrace();
            userKVState.setLockId(NO_SESSION);
        }


    }

    private static void UpdateLockedUser(JedisPooled redisClient, UserKVState userKVState, long lockId, int sessionId,
                                         Object extraPayload, String deltaOperationName) {

        final long startMs = System.currentTimeMillis();

        try {
            readModifyWrite(redisClient, sessionId, ut -> {

                if (ut.isLockedBySomeoneElse(lockId)) {
                    userKVState.lockedBySomeoneElseCount++;
                    msg(sessionId + ": locked by someone else");
                    return false;
                }

                if (deltaOperationName != null && deltaOperationName.equals(ExtraUserData.NEW_LOYALTY_NUMBER)) {
                    ut.userDataObject.loyaltySchemeNumber = Long.parseLong((String) extraPayload);
                } else {
                    ut.setUserDataObject((ExtraUserData) extraPayload);
                }

                ut.unLock();
                return true;
            });

            shc.reportLatency(BaseChargingDemo.KV_PUT, startMs, "KV Put time", 2000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        userKVState.setLockId(NO_SESSION);

    }

    /**
     * Run one worker per thread and wait for them all to finish.
     *
     * @param threadCount     how many threads
     * @param workerForThread given a thread id, returns the work for that thread
     * @return each thread's result, in thread id order
     */
    protected static <T> List<T> runInThreads(int threadCount, IntFunction<Callable<T>> workerForThread)
            throws InterruptedException {

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        try {
            List<Callable<T>> workers = new ArrayList<>(threadCount);
            for (int threadId = 0; threadId < threadCount; threadId++) {
                workers.add(workerForThread.apply(threadId));
            }

            List<T> results = new ArrayList<>(threadCount);
            for (Future<T> future : pool.invokeAll(workers)) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    throw new RuntimeException(e.getCause());
                }
            }
            return results;

        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Each thread needs at least one tx per ms and one user to work with.
     */
    protected static int getUsableThreadCount(int threadCount, int tpMs, int userCount) {
        return Math.max(1, Math.min(threadCount, Math.min(tpMs, userCount)));
    }

    /**
     * @return threadId's share of tpMs
     */
    private static int getThreadTpMs(int tpMs, int threadCount, int threadId) {
        return tpMs / threadCount + (threadId < tpMs % threadCount ? 1 : 0);
    }

    /**
     * @return how many users have id % threadCount == threadId
     */
    private static int getThreadUserCount(int userCount, int threadCount, int threadId) {
        return (userCount - threadId + threadCount - 1) / threadCount;
    }

    /**
     * Report on one user and global stats every freqSeconds, on a thread of its own so the
     * workers aren't held up.
     */
    private static ScheduledExecutorService startGlobalQueries(JedisPooled redisClient, int queryUserId,
                                                               int userCount, int freqSeconds) {

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                queryUserAndStats(redisClient, queryUserId, userCount);
            } catch (Exception e) {
                msg("Global query failed: " + e.getMessage());
            }
        }, freqSeconds, Math.max(1, freqSeconds), TimeUnit.SECONDS);

        return scheduler;
    }

    /**
     * Convenience method to remove unneeded records storing old allotments of
     * credit.
     *
     */
    protected static void clearUnfinishedTransactions(JedisPooled redisClient, int userCount, int threadCount)
            throws Exception {

        msg("clearUnfinishedTransactions...");
        final long startMs = System.currentTimeMillis();
        final int usableThreadCount = getUsableThreadCount(threadCount, Integer.MAX_VALUE, userCount);

        runInThreads(usableThreadCount, threadId -> () -> {
            for (int id = threadId; id < userCount; id += usableThreadCount) {
                try {
                    // Most users won't need changing, so check cheaply before paying for WATCH/MULTI/EXEC
                    UserTable ut = getUser(redisClient, id);
                    if (ut != null && ut.getUserUsageCount() > 0) {
                        readModifyWrite(redisClient, id, theUser -> theUser.clearSessions() > 0);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return null;
        });

        shc.reportLatency(BaseChargingDemo.CLEAR_UNFINISHED, startMs, "clear unfinished", 2000);
        msg("...done");

    }

    /**
     *
     * Convenience method to clear outstanding locks between runs
     *
     * @param redisClient
     */
    protected static void unlockAllRecords(JedisPooled redisClient, int userCount, int threadCount)
            throws InterruptedException {

        msg("Clearing locked sessions from prior runs...");
        final long startMs = System.currentTimeMillis();
        final int usableThreadCount = getUsableThreadCount(threadCount, Integer.MAX_VALUE, userCount);

        runInThreads(usableThreadCount, threadId -> () -> {
            for (int id = threadId; id < userCount; id += usableThreadCount) {
                try {
                    // Most users won't be locked, so check cheaply before paying for WATCH/MULTI/EXEC
                    UserTable ut = getUser(redisClient, id);
                    if (ut != null && isLocked(ut)) {
                        readModifyWrite(redisClient, id, theUser -> {
                            if (!isLocked(theUser)) {
                                return false;
                            }
                            theUser.unLock();
                            return true;
                        });
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return null;
        });

        shc.reportLatency(BaseChargingDemo.CLEAR_LOCK, startMs, "clear locks", 2000);
        msg("...done");

    }


    private static boolean isLocked(UserTable ut) {
        return ut.userSoftlockExpiry != null || ut.userSoftLockSessionId != NO_SESSION;
    }


    /**
     *
     * Run a transaction benchmark for userCount users at tpMs per ms.
     *
     * @param userCount              number of users
     * @param tpMs                   transactions per milliseconds
     * @param durationSeconds
     * @param globalQueryFreqSeconds how often we check on global stats and a single
     *                               user
     * @param mainClient
     * @param threadCount            how many threads to spread the work over
     * @return true if within 90% of targeted TPS
     * @throws InterruptedException
     */
    protected static boolean runTransactionBenchmark(int userCount, int tpMs, int durationSeconds,
                                                     int globalQueryFreqSeconds, JedisPooled mainClient, int extraMs,
                                                     int threadCount)
            throws InterruptedException {

        final int usableThreadCount = getUsableThreadCount(threadCount, tpMs, userCount);
        final AtomicLong progressCount = new AtomicLong();

        final long startMsRun = System.currentTimeMillis();
        final long endtimeMs = System.currentTimeMillis() + (durationSeconds * 1000L);

        msg("starting " + usableThreadCount + " threads for " + userCount + " users...");

        ScheduledExecutorService globalQueries = startGlobalQueries(mainClient, (int) GENERIC_QUERY_USER_ID,
                userCount, globalQueryFreqSeconds);

        TransactionResults results = new TransactionResults();

        try {
            for (TransactionResults threadResults : runInThreads(usableThreadCount, threadId -> () ->
                    runTransactionWorker(threadId, usableThreadCount, userCount, tpMs, endtimeMs, mainClient, extraMs,
                            progressCount))) {
                results.add(threadResults);
            }
        } finally {
            globalQueries.shutdownNow();
        }

        long elapsedTimeMs = System.currentTimeMillis() - startMsRun;
        msg("Processed " + results.tranCount + " transactions in " + elapsedTimeMs + " milliseconds");

        double tps = results.tranCount;
        tps = tps / (elapsedTimeMs / 1000d);

        msg("TPS = " + tps);

        msg("Add Credit calls = " + results.addCreditCount);
        msg("Report Usage calls = " + results.reportUsageCount);
        msg("Skipped because transaction was in flight = " + results.inFlightCount);
        msg("Retries caused by concurrent changes = " + shc.getCounter(WATCH_CONFLICT));

        reportRunLatencyStats(tpMs, tps);

        // Declare victory if we got >= 90% of requested TPS...
        return tps / (tpMs * 1000) > .9;
    }

    /**
     * One thread's share of the transaction benchmark. It owns every user whose id % threadCount == threadId,
     * so no other thread in this process touches its users.
     */
    private static TransactionResults runTransactionWorker(int threadId, int threadCount, int userCount, int tpMs,
                                                           long endtimeMs, JedisPooled mainClient, int extraMs,
                                                           AtomicLong progressCount)
            throws InterruptedException {

        // Used to track changes and be unique when we are running multiple threads
        final String txnPrefix = "ReportQuotaUsage_" + getPid() + "_" + threadId + "_";

        Random r = new Random();

        UserTransactionState[] users = new UserTransactionState[getThreadUserCount(userCount, threadCount, threadId)];

        for (int i = 0; i < users.length; i++) {
            // We don't know a users credit till we've spoken to the server, so
            // we make an optimistic assumption...
            users[i] = new UserTransactionState(i * threadCount + threadId, 2000);
        }

        TpMsThrottle throttle = new TpMsThrottle(getThreadTpMs(tpMs, threadCount, threadId), extraMs);
        TransactionResults results = new TransactionResults();

        while (endtimeMs > System.currentTimeMillis()) {

            throttle.await();

            UserTransactionState user = users[r.nextInt(users.length)];

            if (user.isTxInFlight()) {
                results.inFlightCount++;
            } else {

                user.startTran();

                if (user.spendableBalance < 1000) {

                    results.addCreditCount++;

                    final long extraCredit = r.nextInt(1000) + 1000;

                    final long startMs = System.currentTimeMillis();
                    addCredit(mainClient, user.id, extraCredit, user);
                    shc.reportLatency(BaseChargingDemo.ADD_CREDIT, startMs, "ADD_CREDIT", 2000);
                    shc.incCounter(BaseChargingDemo.ADD_CREDIT);
                    user.endTran();


                } else {

                    results.reportUsageCount++;

                    int unitsUsed = (int) (user.currentlyReserved * 0.9);
                    int unitsWanted = r.nextInt(100);
                    final long startMs = System.currentTimeMillis();
                    reportQuotaUsage(mainClient, user.id, unitsUsed, unitsWanted, user.sessionId,
                            txnPrefix + results.reportUsageCount + "_" + System.currentTimeMillis(), user);
                    shc.reportLatency(BaseChargingDemo.REPORT_QUOTA_USAGE, startMs, "REPORT_QUOTA_USAGE", 2000);
                    shc.incCounter(BaseChargingDemo.REPORT_QUOTA_USAGE);
                    user.endTran();

                }
            }

            results.tranCount++;

            long progress = progressCount.incrementAndGet();
            if (progress % 100000 == 1) {
                msg("On transaction #" + progress);
            }

        }

        return results;
    }

    /**
     * Counters from one KV benchmark thread
     */
    private static class KVResults {
        long tranCount = 0;
        long inFlightCount = 0;
        long lockCount = 0;
        long contestedLockCount = 0;
        long lockFailCount = 0;
        long fullUpdate = 0;
        long deltaUpdate = 0;

        void add(KVResults other) {
            tranCount += other.tranCount;
            inFlightCount += other.inFlightCount;
            lockCount += other.lockCount;
            contestedLockCount += other.contestedLockCount;
            lockFailCount += other.lockFailCount;
            fullUpdate += other.fullUpdate;
            deltaUpdate += other.deltaUpdate;
        }
    }

    /**
     * Counters from one transaction benchmark thread
     */
    private static class TransactionResults {
        long tranCount = 0;
        long inFlightCount = 0;
        long addCreditCount = 0;
        long reportUsageCount = 0;

        void add(TransactionResults other) {
            tranCount += other.tranCount;
            inFlightCount += other.inFlightCount;
            addCreditCount += other.addCreditCount;
            reportUsageCount += other.reportUsageCount;
        }
    }


    private static void addCredit(JedisPooled redisClient, int sessionId, long extraCredit,
                                  UserTransactionState userTransactionState) {

        final long startMs = System.currentTimeMillis();
        final String txnId = "AC_" + getPid() + "_" + sessionId + "_" + System.nanoTime();

        try {
            UserTable ut = readModifyWrite(redisClient, sessionId, theUser -> {
                theUser.addCredit(extraCredit, txnId);
                return true;
            });

            if (ut != null) {
                userTransactionState.spendableBalance = ut.getAvailableCredit();
                shc.reportLatency(BaseChargingDemo.KV_PUT, startMs, "KV Put time", 2000);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static void reportQuotaUsage(JedisPooled redisClient
            , int randomuser, int unitsUsed, int unitsWanted, long sessionId
            , String txnId, UserTransactionState userTransactionState) {


        final long startMs = System.currentTimeMillis();

        try {
            UserTable ut = readModifyWrite(redisClient, randomuser, theUser -> {
                theUser.reportQuotaUsage(unitsUsed, unitsWanted, sessionId, txnId);
                return true;
            });

            if (ut != null) {
                userTransactionState.spendableBalance = ut.getAvailableCredit();
                UserUsageTable reservation = ut.getUserUsage(sessionId);
                userTransactionState.currentlyReserved = reservation == null ? 0 : reservation.allocatedAmount;
                shc.reportLatency(BaseChargingDemo.KV_PUT, startMs, "KV Put time", 2000);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * Turn latency stats into a grepable string
     *
     * @param tpMs target transactions per millisecond
     * @param tps  observed TPS
     */
    private static void reportRunLatencyStats(int tpMs, double tps) {
        StringBuffer oneLineSummary = new StringBuffer("GREPABLE SUMMARY:");

        oneLineSummary.append(tpMs);
        oneLineSummary.append(':');

        oneLineSummary.append(tps);
        oneLineSummary.append(':');

        SafeHistogramCache.getProcPercentiles(shc, oneLineSummary, REPORT_QUOTA_USAGE);

        SafeHistogramCache.getProcPercentiles(shc, oneLineSummary, KV_PUT);

        SafeHistogramCache.getProcPercentiles(shc, oneLineSummary, KV_GET);

        msg(oneLineSummary.toString());

        msg(shc.toString());
    }

    /**
     * Get Linux process ID - used for pseudo unique ids
     *
     * @return Linux process ID
     */
    private static long getPid() {
        return ProcessHandle.current().pid();
    }

    /**
     * Return a loyalty card number
     *
     * @param r instance of Random
     * @return a random loyalty card number between 0 and 1 million
     */
    private static long getNewLoyaltyCardNumber(Random r) {
        return System.currentTimeMillis() % 1000000;
    }

    /**
     * Get the optional thread count argument.
     *
     * @param args  command line
     * @param index where the thread count would be
     * @return thread count, or the number of CPUs if not given
     */
    public static int getThreadCount(String[] args, int index) {

        if (args.length > index) {
            return Integer.parseInt(args[index]);
        }

        return Runtime.getRuntime().availableProcessors();
    }

    /**
     * get EXTRA_MS env variable if set
     *
     * @return extraMs
     */
    public static int getExtraMsIfSet() {

        int extraMs = 0;

        String extraMsEnv = System.getenv(EXTRA_MS);

        if (extraMsEnv != null && !extraMsEnv.isEmpty()) {
            msg("EXTRA_MS is '" + extraMsEnv + "'");
            extraMs = Integer.parseInt(extraMsEnv);
        }

        return extraMs;
    }


    /**
     * Each thread can hold two connections at once - one for WATCH/MULTI/EXEC and one for the read -
     * plus one for global queries.
     */
    private static ConnectionPoolConfig buildPoolConfig(int threadCount) {
        final ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        final int maxConnections = Math.max(128, threadCount * 2 + 2);
        poolConfig.setMaxTotal(maxConnections);
        poolConfig.setMaxIdle(maxConnections);
        poolConfig.setMinIdle(16);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(60));
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        poolConfig.setNumTestsPerEvictionRun(3);
        poolConfig.setBlockWhenExhausted(true);
        return poolConfig;
    }

}
